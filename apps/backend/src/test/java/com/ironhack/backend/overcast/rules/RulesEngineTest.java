package com.ironhack.backend.overcast.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.ironhack.backend.overcast.csv.AzureUsageCsvParser;
import com.ironhack.backend.overcast.domain.Category;
import com.ironhack.backend.overcast.domain.NormalizedResource;
import com.ironhack.backend.overcast.domain.ResourceKind;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE deterministic-money guarantee, locked as a regression test. If any
 * constant, predicate, or the hero sample changes, this ledger must be
 * re-derived on purpose — the savings number can never drift silently.
 *
 * No Spring, no DB, no AI, no clock: same CSV in, same dollars out.
 */
class RulesEngineTest {

    private final RulesConfig config = RulesConfig.load();
    private final RulesEngine engine = new RulesEngine(config);
    private final AzureUsageCsvParser parser = new AzureUsageCsvParser();

    private RulesEngine.Result evaluateSample(String resource) {
        try (var reader = new InputStreamReader(
                getClass().getResourceAsStream(resource), StandardCharsets.UTF_8)) {
            return engine.evaluate(parser.parse(reader).resources());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void heroBillTotalsExactlyTheSeededWaste() {
        var result = evaluateSample("/samples/azure-hero-messy.csv");

        // Hand-verified ledger (see docs/csv-schema.md); every cent accounted for.
        assertThat(result.totalMonthlyWaste()).isEqualByComparingTo("2300.42");
    }

    @Test
    void heroBillPerRuleBreakdownIsStable() {
        var result = evaluateSample("/samples/azure-hero-messy.csv");

        Map<String, BigDecimal> byRule = new LinkedHashMap<>();
        for (RuleMatch m : result.matches()) {
            byRule.merge(m.ruleId(), m.monthlySaving(), BigDecimal::add);
        }

        assertThat(byRule.get("unattached_disk")).isEqualByComparingTo("195.25");
        assertThat(byRule.get("orphaned_public_ip")).isEqualByComparingTo("10.95");
        assertThat(byRule.get("old_snapshot")).isEqualByComparingTo("231.63");
        assertThat(byRule.get("nonprod_247")).isEqualByComparingTo("1024.92");
        assertThat(byRule.get("ondemand_vs_reserved")).isEqualByComparingTo("705.46");
        assertThat(byRule.get("premium_storage_nonprod")).isEqualByComparingTo("132.21");
        assertThat(byRule.get("untagged")).isEqualByComparingTo("0");
    }

    @Test
    void cleanBillHasZeroWasteAndNoFindings() {
        var result = evaluateSample("/samples/azure-small-clean.csv");

        assertThat(result.matches()).isEmpty();
        assertThat(result.totalMonthlyWaste()).isEqualByComparingTo("0");
    }

    @Test
    void everySavingIsCappedAtTheResourceCostAndNeverDoubleCounts() {
        var result = evaluateSample("/samples/azure-hero-messy.csv");

        // Invariant that makes the number trustworthy: for any resource, the
        // sum of its findings' savings never exceeds its monthly cost.
        Map<String, BigDecimal> savingByResource = new LinkedHashMap<>();
        Map<String, BigDecimal> costByResource = new LinkedHashMap<>();
        for (RuleMatch m : result.matches()) {
            savingByResource.merge(m.resource().resourceId(), m.monthlySaving(), BigDecimal::add);
            costByResource.putIfAbsent(m.resource().resourceId(), m.resource().monthlyCost());
        }
        savingByResource.forEach((id, saving) ->
                assertThat(saving).as("saving <= cost for %s", id)
                        .isLessThanOrEqualTo(costByResource.get(id)));
    }

    @Test
    void untaggedFlagsCarryZeroDollarsSoGovernanceNeverInflatesWaste() {
        var result = evaluateSample("/samples/azure-hero-messy.csv");

        assertThat(result.matches())
                .filteredOn(m -> m.ruleId().equals("untagged"))
                .isNotEmpty()
                .allSatisfy(m -> {
                    assertThat(m.category()).isEqualTo(Category.GOVERNANCE);
                    assertThat(m.monthlySaving()).isEqualByComparingTo("0");
                });
    }

    // ── strict-mode predicates (wildcard SKUs, env-tag nonprod, thresholds) ──

    private NormalizedResource vm(String id, String rg, String sku,
                                  BigDecimal hours, Map<String, String> tags) {
        return new NormalizedResource(id, "vm", ResourceKind.VM, rg, "eu-west-1",
                sku, sku, hours, BigDecimal.ZERO, new BigDecimal("100.00"),
                tags, id + "-nic", null);
    }

    @Test
    void wildcardSkuEntriesCatchAwsAndGcpPrevGenMachines() {
        var aws = vm("i-old", "111122223333", "t2.large",
                new BigDecimal("100"), Map.of("owner", "sam", "env", "prod"));
        var gcp = vm("vm-old", "proj-core", "N1 Predefined Instance Core running in EMEA",
                new BigDecimal("100"), Map.of("owner", "sam", "env", "prod"));

        var result = engine.evaluate(List.of(aws, gcp));

        assertThat(result.matches())
                .extracting(RuleMatch::ruleId)
                .containsExactly("prev_gen_vm", "prev_gen_vm");
        // 100.00 × prev_gen_delta (0.20) each
        assertThat(result.totalMonthlyWaste()).isEqualByComparingTo("40.00");
    }

    @Test
    void envTagClassifiesNonprodWhenTheGroupSlotIsAnAccountId() {
        // AWS CUR: resourceGroup is the numeric account id — only the env tag
        // can mark the machine nonprod. 730h dev VM → nonprod_247, not RI.
        var sustained = vm("i-nightly", "111122223333", "m5.xlarge",
                new BigDecimal("730"), Map.of("owner", "ana", "env", "dev"));

        var result = engine.evaluate(List.of(sustained));

        assertThat(result.matches())
                .extracting(RuleMatch::ruleId)
                .containsExactly("nonprod_247");
        // 100.00 × offhours_factor (0.65)
        assertThat(result.totalMonthlyWaste()).isEqualByComparingTo("65.00");
    }

    @Test
    void unknownUsageCountsAsSustainedForCostByResourceExports() {
        // "Cost by resource" downloads have no usage column (quantity null):
        // a dev VM billing a full month is assumed always-on → nonprod_247.
        var devVm = vm("/rg/vm-dev", "rg-redacta-development", "",
                null, Map.of("owner", "ana", "environment", "development"));

        var result = engine.evaluate(List.of(devVm));

        assertThat(result.matches())
                .extracting(RuleMatch::ruleId)
                .containsExactly("nonprod_247");
        assertThat(result.totalMonthlyWaste()).isEqualByComparingTo("65.00");
    }

    @Test
    void sustainedThresholdCatchesPartialMonthAlwaysOnCompute() {
        // 520h ≥ sustained_hours (500) → prod VM flagged for reservation;
        // the clean sample's 400h VM stays under the bar on purpose.
        var prod = vm("vm-prod", "rg-prod-api", "Standard_D4s_v5",
                new BigDecimal("520"), Map.of("owner", "sam", "env", "prod"));

        var result = engine.evaluate(List.of(prod));

        assertThat(result.matches())
                .extracting(RuleMatch::ruleId)
                .containsExactly("ondemand_vs_reserved");
    }

    @Test
    void engineRefusesToStartWhenYamlAndCodeDiverge() {
        // The 1:1 check between rules-config.yaml and the implementations is what
        // lets the YAML be audited as the authoritative rule list.
        assertThat(config.rules().keySet()).containsExactlyInAnyOrder(
                "unattached_disk", "orphaned_public_ip", "old_snapshot", "prev_gen_vm",
                "nonprod_247", "ondemand_vs_reserved", "premium_storage_nonprod", "untagged");
    }
}
