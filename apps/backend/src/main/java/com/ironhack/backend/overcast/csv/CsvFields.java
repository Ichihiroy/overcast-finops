package com.ironhack.backend.overcast.csv;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Header/cell helpers shared by the Azure and AWS parsers. */
final class CsvFields {

    private CsvFields() {}

    /** Maps normalized field names to column positions via alias lists, first alias wins. */
    static Map<String, Integer> headerIndex(List<String> header, Map<String, List<String>> columns) {
        Map<String, Integer> raw = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            raw.putIfAbsent(header.get(i).trim().toLowerCase(Locale.ROOT), i);
        }
        Map<String, Integer> idx = new HashMap<>();
        columns.forEach((field, aliases) -> {
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

    static Map<String, String> namedRow(List<String> cells, Map<String, Integer> idx) {
        Map<String, String> row = new HashMap<>();
        idx.forEach((field, i) -> row.put(field, i < cells.size() ? cells.get(i) : null));
        return row;
    }

    static BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    static String orEmpty(String v) {
        return v == null ? "" : v.trim();
    }
}
