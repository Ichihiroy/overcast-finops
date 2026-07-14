package com.ironhack.backend.overcast.csv;

import com.ironhack.backend.overcast.domain.NormalizedResource;
import com.ironhack.backend.overcast.domain.ResourceKind;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Parses an AWS Cost and Usage Report (CUR, resource-id granularity) into the
 * same normalized model as the Azure parser: line items grouped by resource
 * id, cost and usage summed, descriptive fields from the highest-cost line.
 * Column mapping is documented in docs/csv-schema.md — keep the two in sync.
 */
public final class AwsCurCsvParser {

    private static final Map<String, List<String>> COLUMNS = Map.ofEntries(
            Map.entry("resourceId", List.of("lineitem/resourceid")),
            Map.entry("productCode", List.of("lineitem/productcode", "product/servicecode")),
            Map.entry("usageType", List.of("lineitem/usagetype")),
            // AWS has no resource groups; the account id fills the slot so the
            // non-prod name pattern simply never matches (numeric ids).
            Map.entry("resourceGroup", List.of("lineitem/usageaccountid")),
            Map.entry("region", List.of("product/region")),
            Map.entry("sku", List.of("product/instancetype")),
            Map.entry("quantity", List.of("lineitem/usageamount")),
            Map.entry("unitPrice", List.of("lineitem/unblendedrate")),
            Map.entry("cost", List.of("lineitem/unblendedcost")),
            Map.entry("currency", List.of("lineitem/currencycode")),
            // Enrichment columns the Overcast enricher can append, same as Azure
            Map.entry("associatedResource", List.of("associatedresource")),
            Map.entry("ageDays", List.of("agedays")));

    private static final List<String> REQUIRED = List.of("resourceId", "cost");
    private static final String TAG_PREFIX = "resourcetags/user:";

    public ParseResult parse(List<List<String>> rows) {
        if (rows.size() < 2) {
            throw new CsvFormatException("CSV has no data rows — expected an AWS Cost and Usage Report.");
        }

        List<String> header = rows.get(0);
        Map<String, Integer> idx = CsvFields.headerIndex(header, COLUMNS);
        for (String required : REQUIRED) {
            if (!idx.containsKey(required)) {
                throw new CsvFormatException("Missing required CUR column '" + required
                        + "' (accepted headers: " + COLUMNS.get(required) + "). See docs/csv-schema.md.");
            }
        }
        Map<String, Integer> tagColumns = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String h = header.get(i).trim().toLowerCase(Locale.ROOT);
            if (h.startsWith(TAG_PREFIX)) tagColumns.put(h.substring(TAG_PREFIX.length()), i);
        }
        boolean hasAssociationColumn = idx.containsKey("associatedResource");
        boolean hasAgeColumn = idx.containsKey("ageDays");

        Map<String, List<Map<String, String>>> byResource = new LinkedHashMap<>();
        Map<String, Map<String, String>> tagsByResource = new HashMap<>();
        String currency = "USD";
        for (int i = 1; i < rows.size(); i++) {
            List<String> cells = rows.get(i);
            Map<String, String> row = CsvFields.namedRow(cells, idx);
            String id = row.get("resourceId");
            if (id == null || id.isBlank()) continue; // RI fees, taxes, support — not resources
            String key = id.trim();
            byResource.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            String cur = row.get("currency");
            if (cur != null && !cur.isBlank()) currency = cur.trim();
            Map<String, String> tags = tagsByResource.computeIfAbsent(key, k -> new TreeMap<>());
            tagColumns.forEach((tag, col) -> {
                String v = col < cells.size() ? cells.get(col) : null;
                if (v != null && !v.isBlank()) tags.put(tag, v.trim());
            });
        }
        if (byResource.isEmpty()) {
            throw new CsvFormatException("No line items with a lineItem/ResourceId found — "
                    + "is the CUR configured with resource-id granularity?");
        }

        List<NormalizedResource> resources = new ArrayList<>();
        for (var entry : byResource.entrySet()) {
            resources.add(normalize(entry.getKey(), entry.getValue(),
                    Map.copyOf(tagsByResource.get(entry.getKey())), hasAssociationColumn));
        }
        return new ParseResult(resources, currency, "aws", hasAssociationColumn, hasAgeColumn);
    }

    private NormalizedResource normalize(String resourceId, List<Map<String, String>> rows,
                                         Map<String, String> tags, boolean hasAssociationColumn) {
        Map<String, String> primary = rows.get(0);
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal primaryCost = new BigDecimal(-1);
        BigDecimal quantity = BigDecimal.ZERO;
        for (Map<String, String> row : rows) {
            BigDecimal cost = CsvFields.decimal(row.get("cost"));
            totalCost = totalCost.add(cost);
            quantity = quantity.add(CsvFields.decimal(row.get("quantity")));
            if (cost.compareTo(primaryCost) > 0) {
                primaryCost = cost;
                primary = row;
            }
        }

        String assoc = null;
        if (hasAssociationColumn) {
            String v = primary.get("associatedResource");
            assoc = v == null ? "" : v.trim();
        }
        Integer ageDays = null;
        String age = primary.get("ageDays");
        if (age != null && !age.isBlank()) {
            try {
                ageDays = Integer.valueOf(age.trim());
            } catch (NumberFormatException ignored) {
                // unknown age stays null; age-based rules simply stay silent
            }
        }

        String product = CsvFields.orEmpty(primary.get("productCode"));
        String usageType = CsvFields.orEmpty(primary.get("usageType"));
        return new NormalizedResource(
                resourceId,
                product + (usageType.isEmpty() ? "" : "/" + usageType),
                ResourceKind.fromAwsUsage(product, usageType),
                CsvFields.orEmpty(primary.get("resourceGroup")),
                CsvFields.orEmpty(primary.get("region")),
                usageType,
                CsvFields.orEmpty(primary.get("sku")),
                quantity,
                CsvFields.decimal(primary.get("unitPrice")),
                totalCost.setScale(2, RoundingMode.HALF_UP),
                tags,
                assoc,
                ageDays);
    }
}
