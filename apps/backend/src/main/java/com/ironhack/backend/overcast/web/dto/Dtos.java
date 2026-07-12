package com.ironhack.backend.overcast.web.dto;

import com.ironhack.backend.overcast.domain.Category;
import com.ironhack.backend.overcast.domain.Finding;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** API shapes. All dollar values originate in the rules engine, verbatim. */
public final class Dtos {

    private Dtos() {}

    public record CategoryTotal(long count, BigDecimal monthlySaving) {}

    public record ScanSummary(
            String scanId,
            String filename,
            String currency,
            BigDecimal totalMonthlyCost,
            BigDecimal totalMonthlyWaste,
            BigDecimal totalAnnualWaste,
            long findingCount,
            Map<String, CategoryTotal> byCategory,
            // Data-quality notices (e.g. raw export missing enrichment columns so
            // some rules could not run); empty when the export was fully enriched.
            List<String> warnings) {}

    public record FindingDto(
            String id,
            String resourceId,
            String resourceName,
            String resourceType,
            String resourceGroup,
            String region,
            String ruleId,
            Category category,
            BigDecimal monthlyCost,
            BigDecimal monthlySaving,
            String remediation) {

        public static FindingDto from(Finding f) {
            int idx = f.resourceId().lastIndexOf('/');
            String name = idx >= 0 ? f.resourceId().substring(idx + 1) : f.resourceId();
            return new FindingDto(f.id(), f.resourceId(), name, f.resourceType(), f.resourceGroup(),
                    f.region(), f.ruleId(), f.category(), f.monthlyCost(), f.monthlySaving(),
                    f.remediation());
        }
    }

    public record FindingsPage(List<FindingDto> items, int page, int size, long totalItems, int totalPages) {}

    public record ScanCreated(String scanId, ScanSummary summary) {}

    public record ExplainResponse(String explanation, String remediation, String source) {}

    public record AskRequest(String question) {}

    public record AskResponse(String answer, String source) {}

    public record ChecklistItem(String resourceId, String resourceName, String resourceGroup,
                                String ruleId, String ruleName, String action, BigDecimal monthlySaving) {}

    public record OptimizedBill(
            String scanId,
            String currency,
            BigDecimal currentMonthly,
            BigDecimal optimizedMonthly,
            BigDecimal monthlySavings,
            BigDecimal annualSavings,
            Map<String, CategoryTotal> byCategory,
            List<ChecklistItem> checklist) {}
}
