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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Parses an Azure Cost Management "usage details" CSV export into the
 * normalized resource model. Column names, aliases, and the multi-row
 * aggregation strategy are documented in docs/csv-schema.md — keep the two
 * in sync. AWS CUR support is a documented adapter stub, not implemented.
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

    public record ParseResult(List<NormalizedResource> resources, String currency,
                              boolean hasAssociationColumn, boolean hasAgeColumn) {}

    public ParseResult parse(Reader input) {
        List<List<String>> rows;
        try {
            rows = CsvReader.parse(input);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (rows.size() < 2) {
            throw new CsvFormatException("CSV has no data rows — expected an Azure usage details export.");
        }

        Map<String, Integer> idx = headerIndex(rows.get(0));
        for (String required : REQUIRED) {
            if (!idx.containsKey(required)) {
                throw new CsvFormatException("Missing required column '" + required
                        + "' (accepted headers: " + COLUMNS.get(required) + "). See docs/csv-schema.md.");
            }
        }
        boolean hasAssociationColumn = idx.containsKey("associatedResource");
        boolean hasAgeColumn = idx.containsKey("ageDays");

        // Group rows by resource id: costs sum; descriptive fields come from
        // the row with the highest cost ("primary meter") — see docs/csv-schema.md.
        Map<String, List<Map<String, String>>> byResource = new LinkedHashMap<>();
        String currency = "USD";
        for (int i = 1; i < rows.size(); i++) {
            Map<String, String> row = namedRow(rows.get(i), idx);
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
            resources.add(normalize(entry.getKey(), entry.getValue(), hasAssociationColumn));
        }
        return new ParseResult(resources, currency, hasAssociationColumn, hasAgeColumn);
    }

    private NormalizedResource normalize(String resourceId, List<Map<String, String>> rows,
                                         boolean hasAssociationColumn) {
        Map<String, String> primary = rows.get(0);
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal primaryCost = new BigDecimal(-1);
        BigDecimal quantity = BigDecimal.ZERO;
        for (Map<String, String> row : rows) {
            BigDecimal cost = decimal(row.get("cost"));
            totalCost = totalCost.add(cost);
            quantity = quantity.add(decimal(row.get("quantity")));
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

        String type = orEmpty(primary.get("resourceType"));
        return new NormalizedResource(
                resourceId,
                type,
                ResourceKind.fromAzureType(type),
                orEmpty(primary.get("resourceGroup")),
                orEmpty(primary.get("region")),
                orEmpty(primary.get("meter")),
                orEmpty(primary.get("sku")),
                quantity,
                decimal(primary.get("unitPrice")),
                totalCost.setScale(2, java.math.RoundingMode.HALF_UP),
                parseTags(primary.get("tags")),
                assoc,
                ageDays);
    }

    private Map<String, String> parseTags(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        String json = raw.trim();
        if (!json.startsWith("{")) json = "{" + json + "}"; // some exports omit the braces
        try {
            Map<String, String> tags = mapper.readValue(json, new TypeReference<HashMap<String, String>>() {});
            Map<String, String> lower = new TreeMap<>();
            tags.forEach((k, v) -> lower.put(k.toLowerCase(Locale.ROOT), v == null ? "" : v));
            return lower;
        } catch (IOException e) {
            return Map.of(); // unparseable tags = untagged, the conservative reading
        }
    }

    private static Map<String, Integer> headerIndex(List<String> header) {
        Map<String, Integer> raw = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            raw.putIfAbsent(header.get(i).trim().toLowerCase(Locale.ROOT), i);
        }
        Map<String, Integer> idx = new HashMap<>();
        COLUMNS.forEach((field, aliases) -> {
            for (String alias : aliases) {
                Integer i = raw.get(alias);
                if (i != null) {
                    idx.put(field, i);
                    return;
                }
            }
        });
        return idx;
    }

    private static Map<String, String> namedRow(List<String> cells, Map<String, Integer> idx) {
        Map<String, String> row = new HashMap<>();
        idx.forEach((field, i) -> row.put(field, i < cells.size() ? cells.get(i) : null));
        return row;
    }

    private static BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static String orEmpty(String v) {
        return v == null ? "" : v.trim();
    }
}
