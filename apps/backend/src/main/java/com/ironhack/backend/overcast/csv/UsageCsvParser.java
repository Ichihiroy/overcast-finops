package com.ironhack.backend.overcast.csv;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;

/**
 * Provider-detecting entry point. AWS CUR headers are namespaced
 * ("lineItem/UnblendedCost"); Azure export headers are bare words — that
 * prefix is the dispatch signal. Both parsers emit the same ParseResult, so
 * the rules engine never knows which cloud the bill came from.
 */
public final class UsageCsvParser {

    private final AzureUsageCsvParser azure = new AzureUsageCsvParser();
    private final AwsCurCsvParser aws = new AwsCurCsvParser();

    public ParseResult parse(Reader input) {
        List<List<String>> rows;
        try {
            rows = CsvReader.parse(input);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (rows.isEmpty()) {
            throw new CsvFormatException("CSV has no data rows.");
        }
        boolean isCur = rows.get(0).stream()
                .anyMatch(h -> h.trim().toLowerCase(Locale.ROOT).startsWith("lineitem/"));
        if (isCur) {
            return aws.parse(rows);
        }
        try {
            return azure.parse(rows);
        } catch (CsvFormatException e) {
            // Frequent trap: an AWS export that is NOT the CUR (Cost Explorer
            // downloads, hand-made spend sheets) has bare headers, lands here,
            // and gets an Azure-worded error. Point AWS users at the one
            // format that carries resource ids.
            if (e.getMessage().startsWith("Missing required column")) {
                throw new CsvFormatException(e.getMessage()
                        + " AWS bill? Only the Cost and Usage Report (CUR) with resource IDs is"
                        + " supported — its headers are namespaced, e.g. lineItem/ResourceId.");
            }
            throw e;
        }
    }
}
