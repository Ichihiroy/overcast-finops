package com.ironhack.backend.overcast.csv;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironhack.backend.overcast.domain.NormalizedResource;
import com.ironhack.backend.overcast.domain.ResourceKind;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Parses a GCP Cloud Billing detailed export (BigQuery "detailed usage cost"
 * table flattened to CSV — dotted column names like {@code service.description})
 * into the same normalized model as the Azure and AWS parsers. Column mapping
 * is documented in docs/csv-schema.md — keep the two in sync.
 */
public final class GcpBillingCsvParser {

    private static final Map<String, List<String>> COLUMNS = Map.ofEntries(
            // The global name is unambiguous across projects; plain name is the fallback.
            Map.entry("resourceId", List.of("resource.global_name", "resource.name")),
            Map.entry("service", List.of("service.description")),
            Map.entry("sku", List.of("sku.description")),
            // GCP has no resource groups; the project fills the slot so the
            // non-prod name pattern ("dev|test|sandbox|qa") still works.
            Map.entry("resourceGroup", List.of("project.id", "project.name")),
            Map.entry("region", List.of("location.region", "location.location")),
            Map.entry("quantity", List.of("usage.amount")),
            Map.entry("cost", List.of("cost")),
            Map.entry("currency", List.of("currency")),
            Map.entry("tags", List.of("labels")),
            // Enrichment columns the Overcast enricher can append, same as Azure/AWS
            Map.entry("associatedResource", List.of("associatedresource")),
            Map.entry("ageDays", List.of("agedays")));

    private static final List<String> REQUIRED = List.of("resourceId", "cost");

    private final ObjectMapper mapper = new ObjectMapper();

    public ParseResult parse(List<List<String>> rows) {
        if (rows.size() < 2) {
            throw new CsvFormatException("CSV has no data rows — expected a GCP Cloud Billing detailed export.");
        }

        Map<String, Integer> idx = CsvFields.headerIndex(rows.get(0), COLUMNS);
        for (String required : REQUIRED) {
            if (!idx.containsKey(required)) {
                throw new CsvFormatException("Missing required column '" + required
                        + "' (accepted headers: " + COLUMNS.get(required) + "). GCP support needs the"
                        + " BigQuery detailed usage cost export, which carries resource names —"
                        + " the console's cost-table download does not. See docs/csv-schema.md.");
            }
        }
        boolean hasAssociationColumn = idx.containsKey("associatedResource");
        boolean hasAgeColumn = idx.containsKey("ageDays");

        Map<String, List<Map<String, String>>> byResource = new LinkedHashMap<>();
        String currency = "USD";
        for (int i = 1; i < rows.size(); i++) {
            Map<String, String> row = CsvFields.namedRow(rows.get(i), idx);
            String id = row.get("resourceId");
            if (id == null || id.isBlank()) continue; // untied charges (support, credits)
            byResource.computeIfAbsent(id.trim(), k -> new ArrayList<>()).add(row);
            String cur = row.get("currency");
            if (cur != null && !cur.isBlank()) currency = cur.trim();
        }
        if (byResource.isEmpty()) {
            throw new CsvFormatException("No rows with a resource.name found — is this the"
                    + " detailed (resource-level) billing export?");
        }

        List<NormalizedResource> resources = new ArrayList<>();
        for (var entry : byResource.entrySet()) {
            resources.add(normalize(entry.getKey(), entry.getValue(), hasAssociationColumn));
        }
        return new ParseResult(resources, currency, "gcp", hasAssociationColumn, hasAgeColumn);
    }

    private NormalizedResource normalize(String resourceId, List<Map<String, String>> rows,
                                         boolean hasAssociationColumn) {
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
            assoc = v == null ? "" : v.trim(); // column present, blank value = known-unattached
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

        String service = CsvFields.orEmpty(primary.get("service"));
        String sku = CsvFields.orEmpty(primary.get("sku"));
        return new NormalizedResource(
                resourceId,
                service + (sku.isEmpty() ? "" : "/" + sku),
                ResourceKind.fromGcpSku(service, sku),
                CsvFields.orEmpty(primary.get("resourceGroup")),
                CsvFields.orEmpty(primary.get("region")),
                sku,
                sku,
                quantity,
                BigDecimal.ZERO, // the detailed export has no per-row unit price
                totalCost.setScale(2, RoundingMode.HALF_UP),
                parseLabels(primary.get("tags")),
                assoc,
                ageDays);
    }

    /**
     * BigQuery labels flatten to either a JSON array of {key,value} structs or
     * a plain JSON object, depending on the export tool — accept both.
     */
    private Map<String, String> parseLabels(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        String json = raw.trim();
        try {
            Map<String, String> lower = new TreeMap<>();
            if (json.startsWith("[")) {
                List<Map<String, String>> pairs =
                        mapper.readValue(json, new TypeReference<ArrayList<Map<String, String>>>() {});
                for (Map<String, String> pair : pairs) {
                    String k = pair.get("key");
                    if (k != null) lower.put(k.toLowerCase(Locale.ROOT), CsvFields.orEmpty(pair.get("value")));
                }
                return lower;
            }
            if (!json.startsWith("{")) json = "{" + json + "}";
            Map<String, String> tags = mapper.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {});
            tags.forEach((k, v) -> lower.put(k.toLowerCase(Locale.ROOT), v == null ? "" : v));
            return lower;
        } catch (IOException e) {
            return Map.of(); // unparseable labels = unlabeled, the conservative reading
        }
    }
}
