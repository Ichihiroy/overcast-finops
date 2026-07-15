package com.ironhack.backend.overcast.csv;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;

/**
 * Provider-dispatching entry point. The caller can name the provider
 * ("azure" | "aws" | "gcp" — the UI selector); otherwise the header shape
 * decides: AWS CUR headers are namespaced ("lineItem/UnblendedCost"), GCP
 * BigQuery exports use dotted names ("service.description"), Azure headers
 * are bare words. All three parsers emit the same ParseResult, so the rules
 * engine never knows which cloud the bill came from.
 */
public final class UsageCsvParser {

    private final AzureUsageCsvParser azure = new AzureUsageCsvParser();
    private final AwsCurCsvParser aws = new AwsCurCsvParser();
    private final GcpBillingCsvParser gcp = new GcpBillingCsvParser();

    public ParseResult parse(Reader input) {
        return parse(input, null);
    }

    /** @param provider "azure" | "aws" | "gcp", or null/"auto" to detect from headers. */
    public ParseResult parse(Reader input, String provider) {
        List<List<String>> rows;
        try {
            rows = CsvReader.parse(input);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (rows.isEmpty()) {
            throw new CsvFormatException("CSV has no data rows.");
        }

        String forced = provider == null ? "auto" : provider.trim().toLowerCase(Locale.ROOT);
        switch (forced) {
            case "azure" -> { return azure.parse(rows); }
            case "aws" -> { return aws.parse(rows); }
            case "gcp" -> { return gcp.parse(rows); }
            case "auto", "" -> { /* fall through to detection */ }
            default -> throw new CsvFormatException(
                    "Unknown provider '" + provider + "' — use azure, aws, or gcp.");
        }

        if (hasHeaderPrefix(rows, "lineitem/")) {
            return aws.parse(rows);
        }
        // AWS monthly service summary: bare snake_case headers, no resource ids
        if (hasHeader(rows, "linked_account_id")
                || (hasHeader(rows, "usage_type") && hasHeader(rows, "cost_usd"))) {
            return aws.parse(rows);
        }
        if (hasHeader(rows, "service.description") || hasHeader(rows, "resource.name")
                || hasHeader(rows, "resource.global_name")) {
            return gcp.parse(rows);
        }
        try {
            return azure.parse(rows);
        } catch (CsvFormatException e) {
            // Frequent trap: an AWS/GCP export that is NOT the supported shape
            // (Cost Explorer downloads, console cost tables, hand-made spend
            // sheets) has bare headers, lands here, and gets an Azure-worded
            // error. Point users at the formats that carry resource ids.
            if (e.getMessage().startsWith("Missing required column")) {
                throw new CsvFormatException(e.getMessage()
                        + " AWS bill? Use the Cost and Usage Report (CUR) — headers are namespaced,"
                        + " e.g. lineItem/ResourceId — or a monthly service summary"
                        + " (linked_account_id, service, usage_type, cost_usd columns)."
                        + " GCP bill? Use the BigQuery detailed usage cost export"
                        + " (service.description, resource.name, cost columns).");
            }
            throw e;
        }
    }

    private static boolean hasHeaderPrefix(List<List<String>> rows, String prefix) {
        return rows.get(0).stream()
                .anyMatch(h -> h.trim().toLowerCase(Locale.ROOT).startsWith(prefix));
    }

    private static boolean hasHeader(List<List<String>> rows, String name) {
        return rows.get(0).stream()
                .anyMatch(h -> h.trim().toLowerCase(Locale.ROOT).equals(name));
    }
}
