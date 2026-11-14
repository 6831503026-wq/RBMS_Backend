package com.mfu.rbms.api;

public final class ApiValidation {
    private ApiValidation() {
    }

    public static double number(Object value) {
        if (value == null) {
            return 0d;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof Boolean bool) {
            return bool ? 1d : 0d;
        }
        if (value instanceof String text) {
            if (text.trim().isEmpty()) {
                return 0d;
            }
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    public static boolean positiveInteger(Object value) {
        double parsed = number(value);
        return Double.isFinite(parsed) && parsed > 0 && parsed == Math.floor(parsed);
    }
}
