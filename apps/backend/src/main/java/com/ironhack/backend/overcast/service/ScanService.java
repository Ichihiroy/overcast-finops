package com.ironhack.backend.overcast.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironhack.backend.overcast.cache.ScanCache;
import com.ironhack.backend.overcast.csv.AzureUsageCsvParser;
import com.ironhack.backend.overcast.domain.Finding;
import com.ironhack.backend.overcast.domain.Scan;
import com.ironhack.backend.overcast.repo.FindingRepository;
import com.ironhack.backend.overcast.repo.ScanRepository;
import com.ironhack.backend.overcast.rules.RuleMatch;
import com.ironhack.backend.overcast.rules.RulesConfig;
import com.ironhack.backend.overcast.rules.RulesEngine;
import com.ironhack.backend.overcast.web.dto.Dtos.CategoryTotal;
import com.ironhack.backend.overcast.web.dto.Dtos.ChecklistItem;
import com.ironhack.backend.overcast.web.dto.Dtos.FindingDto;
import com.ironhack.backend.overcast.web.dto.Dtos.FindingsPage;
import com.ironhack.backend.overcast.web.dto.Dtos.OptimizedBill;
import com.ironhack.backend.overcast.web.dto.Dtos.ScanSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Reader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Scan lifecycle: parse → rules engine → persist → cached reads. The savings
 * number is fixed the moment the rules engine returns; everything after just
 * stores and serves it.
 */
@Service
public class ScanService {

    private final AzureUsageCsvParser parser = new AzureUsageCsvParser();
    private final RulesEngine engine;
    private final RulesConfig rulesConfig;
    private final ScanRepository scans;
    private final FindingRepository findings;
    private final ScanCache cache;
    private final ObjectMapper mapper;

    public ScanService(RulesEngine engine, RulesConfig rulesConfig, ScanRepository scans,
                       FindingRepository findings, ScanCache cache, ObjectMapper mapper) {
        this.engine = engine;
        this.rulesConfig = rulesConfig;
        this.scans = scans;
        this.findings = findings;
        this.cache = cache;
        this.mapper = mapper;
    }

    @Transactional
    public ScanSummary ingest(Reader csv, String filename, String fixedScanId) {
        var parsed = parser.parse(csv);
        var result = engine.evaluate(parsed.resources());

        String scanId = fixedScanId != null ? fixedScanId : UUID.randomUUID().toString();
        BigDecimal totalCost = parsed.resources().stream()
                .map(r -> r.monthlyCost())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        String dataNotes = dataNotes(parsed.hasAssociationColumn(), parsed.hasAgeColumn());
        Scan scan = new Scan(scanId, "azure", filename, Instant.now(), parsed.currency(),
                totalCost, result.totalMonthlyWaste(), dataNotes);
        List<Finding> rows = new ArrayList<>();
        for (RuleMatch m : result.matches()) {
            rows.add(new Finding(
                    UUID.randomUUID().toString(), scanId,
                    m.resource().resourceId(), m.resource().resourceType(),
                    m.resource().resourceGroup(), m.resource().region(),
                    m.ruleId(), m.category(), m.resource().monthlyCost(),
                    m.monthlySaving(), m.remediation(), null));
        }
        scans.insert(scan);
        findings.insertAll(rows);

        cache.evictScan(scanId);
        ScanSummary summary = buildSummary(scan, rows);
        warmCache(scanId, summary, rows);
        return summary;
    }

    public ScanSummary summary(String scanId) {
        return cache.get(ScanCache.summaryKey(scanId), ScanSummary.class)
                .orElseGet(() -> {
                    Scan scan = requireScan(scanId);
                    List<Finding> rows = findings.findByScan(scanId);
                    ScanSummary summary = buildSummary(scan, rows);
                    cache.put(ScanCache.summaryKey(scanId), summary);
                    return summary;
                });
    }

    public FindingsPage findings(String scanId, int page, int size) {
        List<FindingDto> all = allFindingDtos(scanId);
        int total = all.size();
        int totalPages = size == 0 ? 0 : (int) Math.ceil(total / (double) size);
        int from = Math.min(page * size, total);
        int to = Math.min(from + size, total);
        return new FindingsPage(all.subList(from, to), page, size, total, totalPages);
    }

    public OptimizedBill optimized(String scanId) {
        Scan scan = requireScan(scanId);
        List<Finding> rows = findings.findByScan(scanId);
        Map<String, CategoryTotal> byCategory = categoryTotals(rows);
        List<ChecklistItem> checklist = rows.stream()
                .filter(f -> f.monthlySaving().signum() > 0)
                .map(f -> {
                    var spec = rulesConfig.rules().get(f.ruleId());
                    var dto = FindingDto.from(f);
                    return new ChecklistItem(f.resourceId(), dto.resourceName(), f.resourceGroup(),
                            f.ruleId(), spec != null ? spec.name() : f.ruleId(),
                            f.remediation(), f.monthlySaving());
                })
                .toList();
        BigDecimal optimizedMonthly = scan.totalMonthlyCost().subtract(scan.totalMonthlyWaste());
        return new OptimizedBill(scanId, scan.currency(), scan.totalMonthlyCost(), optimizedMonthly,
                scan.totalMonthlyWaste(), annual(scan.totalMonthlyWaste()), byCategory, checklist);
    }

    /** Top findings + summary as compact JSON — the ONLY context the AI Q&A sees. */
    public String contextJson(String scanId, int topN) {
        ScanSummary summary = summary(scanId);
        List<FindingDto> top = allFindingDtos(scanId).stream().limit(topN).toList();
        try {
            return mapper.writeValueAsString(Map.of("summary", summary, "topFindings", top));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Finding> topFindings(String scanId, int limit) {
        requireScan(scanId);
        return findings.findByScan(scanId).stream().limit(limit).toList();
    }

    // ── internals ────────────────────────────────────────────────────────

    private List<FindingDto> allFindingDtos(String scanId) {
        return cache.get(ScanCache.findingsKey(scanId), FindingDto[].class)
                .map(List::of)
                .orElseGet(() -> {
                    requireScan(scanId);
                    List<FindingDto> all = findings.findByScan(scanId).stream()
                            .map(FindingDto::from).toList();
                    cache.put(ScanCache.findingsKey(scanId), all);
                    return all;
                });
    }

    private void warmCache(String scanId, ScanSummary summary, List<Finding> rows) {
        cache.put(ScanCache.summaryKey(scanId), summary);
        // Sort identically to FindingRepository.findByScan so the cache-warm
        // path (right after a scan, and the load-test read) and the DB path
        // return findings in the same biggest-saving-first order.
        List<FindingDto> sorted = rows.stream()
                .sorted(FINDINGS_ORDER)
                .map(FindingDto::from)
                .toList();
        cache.put(ScanCache.findingsKey(scanId), sorted);
    }

    /** Biggest saving first — mirrors the SQL ORDER BY in FindingRepository. */
    private static final java.util.Comparator<Finding> FINDINGS_ORDER =
            java.util.Comparator.comparing(Finding::monthlySaving).reversed()
                    .thenComparing(java.util.Comparator.comparing(Finding::monthlyCost).reversed())
                    .thenComparing(Finding::resourceId);

    private Scan requireScan(String scanId) {
        return scans.findById(scanId)
                .orElseThrow(() -> new NotFoundException("No scan with id '" + scanId + "'"));
    }

    private ScanSummary buildSummary(Scan scan, List<Finding> rows) {
        List<String> warnings = scan.dataNotes() == null || scan.dataNotes().isBlank()
                ? List.of()
                : List.of(scan.dataNotes().split("\n"));
        return new ScanSummary(scan.id(), scan.filename(), scan.currency(),
                scan.totalMonthlyCost(), scan.totalMonthlyWaste(),
                annual(scan.totalMonthlyWaste()), rows.size(), categoryTotals(rows), warnings);
    }

    /**
     * Data-quality notes for the uploaded file. A raw Azure Cost Management
     * export lacks the enrichment columns Overcast's high-value rules depend on
     * (attachment state, resource age) — those rules stay silent rather than
     * guess, so without this notice a raw upload looks deceptively clean and
     * every finding collapses to the tag-governance flag. Null = fully enriched.
     */
    private static String dataNotes(boolean hasAssociationColumn, boolean hasAgeColumn) {
        List<String> notes = new ArrayList<>();
        if (!hasAssociationColumn) {
            notes.add("No attachment data (AssociatedResource column) — skipped: "
                    + "Unattached managed disk, Orphaned public IP, Premium storage in non-prod.");
        }
        if (!hasAgeColumn) {
            notes.add("No resource-age data (AgeDays column) — skipped: Stale snapshot.");
        }
        return notes.isEmpty() ? null : String.join("\n", notes);
    }

    private Map<String, CategoryTotal> categoryTotals(List<Finding> rows) {
        Map<String, CategoryTotal> totals = new LinkedHashMap<>();
        for (var category : com.ironhack.backend.overcast.domain.Category.values()) {
            List<Finding> of = rows.stream().filter(f -> f.category() == category).toList();
            BigDecimal sum = of.stream().map(Finding::monthlySaving)
                    .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
            totals.put(category.json(), new CategoryTotal(of.size(), sum));
        }
        return totals;
    }

    private static BigDecimal annual(BigDecimal monthly) {
        return monthly.multiply(BigDecimal.valueOf(12)).setScale(2, RoundingMode.HALF_UP);
    }
}
