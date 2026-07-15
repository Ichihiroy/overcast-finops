package com.ironhack.backend.overcast.rules;

import com.ironhack.backend.overcast.domain.NormalizedResource;
import com.ironhack.backend.overcast.domain.ResourceKind;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * THE deterministic owner of the savings number. Every dollar in
 * scan.total_monthly_waste is produced here, from rules-config.yaml constants
 * and CSV facts — no AI, no randomness, no clock. Same CSV in, same dollars
 * out, forever.
 *
 * Implementations are keyed 1:1 to the YAML rule specs; the constructor
 * refuses to start if the two sets diverge, so the YAML can be audited as the
 * authoritative rule list.
 */
public class RulesEngine {

    private final RulesConfig config;
    private final Map<String, Function<NormalizedResource, Optional<BigDecimal>>> implementations;

    public RulesEngine(RulesConfig config) {
        this.config = config;
        Map<String, Function<NormalizedResource, Optional<BigDecimal>>> impls = new LinkedHashMap<>();
        impls.put("unattached_disk", this::unattachedDisk);
        impls.put("orphaned_public_ip", this::orphanedPublicIp);
        impls.put("old_snapshot", this::oldSnapshot);
        impls.put("prev_gen_vm", this::prevGenVm);
        impls.put("nonprod_247", this::nonprod247);
        impls.put("ondemand_vs_reserved", this::ondemandVsReserved);
        impls.put("premium_storage_nonprod", this::premiumStorageNonprod);
        impls.put("untagged", this::untagged);
        this.implementations = impls;

        if (!impls.keySet().equals(config.rules().keySet())) {
            throw new IllegalStateException("rules-config.yaml rules " + config.rules().keySet()
                    + " do not match engine implementations " + impls.keySet());
        }
    }

    public record Result(List<RuleMatch> matches, BigDecimal totalMonthlyWaste) {}

    public Result evaluate(List<NormalizedResource> resources) {
        List<RuleMatch> all = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (NormalizedResource resource : resources) {
            List<RuleMatch> matches = evaluateResource(resource);
            for (RuleMatch m : matches) {
                total = total.add(m.monthlySaving());
            }
            all.addAll(matches);
        }
        return new Result(all, total.setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * At most ONE saving-bearing rule per resource — the highest-value one —
     * so two optimizations (e.g. resize + reserve) can never double-count the
     * same dollar. This makes sum(savings) &le; sum(cost) structurally true and
     * is the core of the trustworthy-number guarantee. The untagged rule is a
     * $0 governance flag and is emitted additionally when it matches.
     */
    private List<RuleMatch> evaluateResource(NormalizedResource r) {
        RuleMatch best = null;          // highest-saving cost rule, ties → earlier rule in config
        RuleMatch untaggedFlag = null;  // $0 governance flag, emitted alongside

        for (var entry : implementations.entrySet()) {
            String ruleId = entry.getKey();
            Optional<BigDecimal> saving = entry.getValue().apply(r);
            if (saving.isEmpty()) continue;

            RulesConfig.RuleSpec spec = config.rules().get(ruleId);
            if (ruleId.equals("untagged")) {
                untaggedFlag = new RuleMatch(r, ruleId, spec.category(), BigDecimal.ZERO,
                        interpolate(spec.remediation(), r));
                continue;
            }

            // Safety cap: a resource can never "save" more than it costs.
            BigDecimal amount = saving.get().setScale(2, RoundingMode.HALF_UP);
            if (amount.compareTo(r.monthlyCost()) > 0) amount = r.monthlyCost();

            if (best == null || amount.compareTo(best.monthlySaving()) > 0) {
                best = new RuleMatch(r, ruleId, spec.category(), amount, interpolate(spec.remediation(), r));
            }
        }

        List<RuleMatch> matches = new ArrayList<>(2);
        if (best != null) matches.add(best);
        if (untaggedFlag != null) matches.add(untaggedFlag);
        return matches;
    }

    // ── Rule predicates + saving formulas (spec: rules-config.yaml) ──────

    private Optional<BigDecimal> unattachedDisk(NormalizedResource r) {
        return r.kind() == ResourceKind.DISK && knownUnattached(r)
                ? Optional.of(r.monthlyCost())
                : Optional.empty();
    }

    private Optional<BigDecimal> orphanedPublicIp(NormalizedResource r) {
        return r.kind() == ResourceKind.PUBLIC_IP && knownUnattached(r)
                ? Optional.of(r.monthlyCost())
                : Optional.empty();
    }

    private Optional<BigDecimal> oldSnapshot(NormalizedResource r) {
        return r.kind() == ResourceKind.SNAPSHOT
                && r.ageDays() != null && r.ageDays() > config.snapshotAgeDays()
                ? Optional.of(r.monthlyCost())
                : Optional.empty();
    }

    private Optional<BigDecimal> prevGenVm(NormalizedResource r) {
        if (r.kind() != ResourceKind.VM) return Optional.empty();
        boolean prevGen = config.prevGenSkus().stream().anyMatch(s -> skuMatches(s, r.sku()));
        return prevGen ? Optional.of(r.monthlyCost().multiply(config.prevGenDelta())) : Optional.empty();
    }

    private Optional<BigDecimal> nonprod247(NormalizedResource r) {
        return r.kind() == ResourceKind.VM && nonprod(r) && sustained(r)
                ? Optional.of(r.monthlyCost().multiply(config.offhoursFactor()))
                : Optional.empty();
    }

    private Optional<BigDecimal> ondemandVsReserved(NormalizedResource r) {
        // Prod-only: steady production load is the reservation candidate;
        // sustained nonprod compute is already claimed by nonprod_247.
        return r.kind() == ResourceKind.VM && !nonprod(r) && sustained(r)
                ? Optional.of(r.monthlyCost().multiply(config.riDiscount()))
                : Optional.empty();
    }

    private Optional<BigDecimal> premiumStorageNonprod(NormalizedResource r) {
        boolean premium = r.sku().toLowerCase(Locale.ROOT).contains("premium")
                || r.sku().toUpperCase(Locale.ROOT).matches("P\\d+.*");
        boolean attached = r.associatedResource() != null && !r.associatedResource().isBlank();
        return r.kind() == ResourceKind.DISK && premium && nonprod(r) && attached
                ? Optional.of(r.monthlyCost().multiply(config.premiumDelta()))
                : Optional.empty();
    }

    private Optional<BigDecimal> untagged(NormalizedResource r) {
        // A required-tags entry may list accepted aliases: "env|environment".
        boolean missing = config.requiredTags().stream()
                .anyMatch(tag -> java.util.Arrays.stream(tag.split("\\|"))
                        .map(alias -> alias.trim().toLowerCase(Locale.ROOT))
                        .noneMatch(alias -> r.tags().containsKey(alias)
                                && !r.tags().get(alias).isBlank()));
        return missing ? Optional.of(BigDecimal.ZERO) : Optional.empty();
    }

    // ── shared predicate helpers ─────────────────────────────────────────

    /** True only when the CSV carried an association column AND it was blank. */
    private boolean knownUnattached(NormalizedResource r) {
        return r.associatedResource() != null && r.associatedResource().isBlank();
    }

    /**
     * A prev_gen_skus entry ending with '*' is a case-insensitive prefix
     * (covers AWS instance families like "t2.*" and GCP "n1*" SKU
     * descriptions); anything else must match exactly.
     */
    private boolean skuMatches(String entry, String sku) {
        if (entry.endsWith("*")) {
            return sku.regionMatches(true, 0, entry, 0, entry.length() - 1);
        }
        return entry.equalsIgnoreCase(sku);
    }

    /**
     * Nonprod by resource-group/project name OR by the env|environment tag
     * value — AWS bills carry a numeric account id in the group slot, so the
     * tag is the only classification signal there.
     */
    private boolean nonprod(NormalizedResource r) {
        if (config.nonprodRgPattern().matcher(r.resourceGroup()).matches()) return true;
        String env = r.tags().getOrDefault("env", r.tags().get("environment"));
        return env != null && config.nonprodRgPattern().matcher(env).matches();
    }

    private boolean sustained(NormalizedResource r) {
        // null = the export had no usage column (cost-by-resource downloads);
        // a machine billing a full month with unknown hours is treated as
        // always-on rather than silently passing the check.
        return r.quantity() == null || r.quantity().compareTo(config.sustainedHours()) >= 0;
    }

    private String interpolate(String template, NormalizedResource r) {
        return template
                .replace("{resource}", r.shortName())
                .replace("{rg}", r.resourceGroup())
                .replace("{sku}", r.sku())
                .replace("{region}", r.region())
                .replace("{age}", r.ageDays() == null ? "?" : String.valueOf(r.ageDays()));
    }
}
