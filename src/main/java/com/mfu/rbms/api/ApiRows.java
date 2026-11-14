package com.mfu.rbms.api;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ApiRows {
    private ApiRows() {
    }

    public static List<Map<String, Object>> normalize(List<Map<String, Object>> rows) {
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            result.add(normalize(row));
        }
        return result;
    }

    public static Map<String, Object> normalize(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        row.forEach((key, rawValue) -> {
            String normalizedKey = key.toLowerCase(Locale.ROOT);
            Object value = rawValue;
            if (value instanceof BigDecimal decimal) {
                value = decimal.toPlainString();
            } else if (value instanceof Timestamp timestamp) {
                value = timestamp.toInstant().toString();
            } else if (value instanceof Date date) {
                value = date.toLocalDate().toString();
            } else if (value instanceof Instant instant) {
                value = instant.toString();
            }
            result.put(normalizedKey, value);
        });
        return result;
    }
}
