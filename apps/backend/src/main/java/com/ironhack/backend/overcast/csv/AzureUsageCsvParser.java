package com.ironhack.backend.overcast.csv;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironhack.backend.overcast.domain.NormalizedResource;
import com.ironhack.backend.overcast.domain.ResourceKind;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Parses an Azure Cost Management "usage details" CSV export into the
 * normalized resource model. Column names, aliases, and the multi-row
 * aggregation strategy are documented in docs/csv-schema.md — keep the two
 * in sync. AWS CUR exports are handled by {@link AwsCurCsvParser}; use
 * {@link UsageCsvParser} to auto-detect the provider.
 */
public final class AzureUsageCsvParser {

    /** Header aliases, checked in order (Azure export names vary by API version). */
    private static final Map<String, List<String>> COLUMNS = Map.ofEntries(
            Map.entry("resourceId", List.of("resourceid", "instanceid")),
            Map.entry("resourceType", List.of("resourcetype", "consumedservice")),
            Map.entry("resourceGroup", List.of("resourcegroup", "resourcegroupname")),
            Map.entry("region", List.of("resourcelocation", "location")),
            Map.entry("meter", List.of("metername")),
            Map.entry("meterCategory", List.of("metercategory")),
            Map.entry("sku", List.of("sku", "metersubcategory", "servicetier")),
            Map.entry("quantity", List.of("quantity", "usagequantity")),
            Map.entry("unitPrice", List.of("unitprice", "effectiveprice")),
            Map.entry("cost", List.of("cost", "costinbillingcurrency", "pretaxcost")),
            Map.entry("currency", List.of("currency", "billingcurrency", "billingcurrencycode")),
            Map.entry("tags", List.of("tags")),
            // Enrichment columns (present in Overcast samples/exporter, optional in raw exports)
            Map.entry("associatedResource", List.of("associatedresource")),
            Map.entry("ageDays", List.of("agedays")));

    private static final List<String> REQUIRED =
            List.of("resourceId", "resourceType", "resourceGroup", "cost");

    private final ObjectMapper mapper = new ObjectMapper();

    public ParseResult parse(Reader input) {
        try {
            return parse(CsvReader.parse(input));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public ParseResult parse(List<List<String>> rows) {
        if (rows.size() < 2) {
            throw new CsvFormatException("CSV has no data rows — expected an Azure usage details export.");
        }

        Map<String, Integer> idx = CsvFields.headerIndex(rows.get(0), COLUMNS);
        // The portal's "Cost analysis" download (UsageDate,Cost,...) is daily
        // totals from the same blade — wrong artifact: no per-resource rows.
        if (!idx.containsKey("resourceId") && hasHeader(rows.get(0), "usagedate")) {
            throw new CsvFormatException("This is a Cost analysis daily-totals export "
                    + "(UsageDate/Cost) — it has no per-resource data, so nothing can be scanned. "
                    + "Download the per-resource usage details export instead: Cost analysis → "
                    + "group by Resource → Download, or Cost Management → Usage + charges → "
                    + "Download usage. See docs/csv-schema.md.");
        }
        for (String required : REQUIRED) {
            if (!idx.containsKey(required)) {
                throw new CsvFormatException("Missing required column '" + required
                        + "' (accepted headers: " + COLUMNS.get(required) + "). See docs/csv-schema.md.");
            }
        }
        boolean hasAssociationColumn = idx.containsKey("associatedResource");
        boolean hasAgeColumn = idx.containsKey("ageDays");
        boolean hasQuantityColumn = idx.containsKey("quantity");

        // Group rows by resource id: costs sum; descriptive fields come from
        // the row with the highest cost ("primary meter") — see docs/csv-schema.md.
        Map<String, List<Map<String, String>>> byResource = new LinkedHashMap<>();
        String currency = "USD";
        for (int i = 1; i < rows.size(); i++) {
            Map<String, String> row = CsvFields.namedRow(rows.get(i), idx);
            String id = row.get("resourceId");
            if (id == null || id.isBlank()) continue;
            byResource.computeIfAbsent(id.trim(), k -> new ArrayList<>()).add(row);
            String cur = row.get("currency");
            if (cur != null && !cur.isBlank()) currency = cur.trim();
        }
        if (byResource.isEmpty()) {
            throw new CsvFormatException("No rows with a ResourceId found — is this really a usage details export?");
        }

        List<NormalizedResource> resources = new ArrayList<>();
        for (var entry : byResource.entrySet()) {
            resources.add(normalize(entry.getKey(), entry.getValue(), hasAssociationColumn, hasQuantityColumn));
        }
        return new ParseResult(resources, currency, "azure", hasAssociationColumn, hasAgeColumn);
    }

    private NormalizedResource normalize(String resourceId, List<Map<String, String>> rows,
                                         boolean hasAssociationColumn, boolean hasQuantityColumn) {
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

        String type = CsvFields.orEmpty(primary.get("resourceType"));
        ResourceKind kind = ResourceKind.fromAzureType(type);
        // "Cost by resource" downloads carry display names ("Virtual machine"),
        // not ARM types — but the ARM id still embeds the real type segment.
        if (kind == ResourceKind.OTHER) kind = ResourceKind.fromAzureResourceId(resourceId);

        return new NormalizedResource(
                resourceId,
                type,
                kind,
                CsvFields.orEmpty(primary.get("resourceGroup")),
                CsvFields.orEmpty(primary.get("region")),
                CsvFields.orEmpty(primary.get("meter")),
                CsvFields.orEmpty(primary.get("sku")),
                // "Cost by resource" downloads carry no usage column — null =
                // usage unknown, and the sustained-hours rules assume always-on
                hasQuantityColumn ? quantity : null,
                CsvFields.decimal(primary.get("unitPrice")),
                totalCost.setScale(2, java.math.RoundingMode.HALF_UP),
                parseTags(primary.get("tags")),
                assoc,
                ageDays);
    }

    private Map<String, String> parseTags(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        String json = raw.trim();
        try {
            if (json.startsWith("[")) {
                // "Cost by resource" exports write tags as a JSON array of
                // "\"key\":\"value\"" strings — rebuild the object from it.
                List<String> pairs = mapper.readValue(json, new TypeReference<ArrayList<String>>() {});
                json = "{" + String.join(",", pairs) + "}";
            } else if (!json.startsWith("{")) {
                json = "{" + json + "}"; // some exports omit the braces
            }
            Map<String, String> tags = mapper.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {});
            Map<String, String> lower = new TreeMap<>();
            tags.forEach((k, v) -> lower.put(k.toLowerCase(Locale.ROOT), v == null ? "" : v));
            return lower;
        } catch (IOException e) {
            return Map.of(); // unparseable tags = untagged, the conservative reading
        }
    }

    private static boolean hasHeader(List<String> header, String name) {
        return header.stream().anyMatch(h -> h.trim().toLowerCase(Locale.ROOT).equals(name));
    }
}
