package com.mfu.rbms.controller;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mfu.rbms.api.ApiException;
import com.mfu.rbms.api.ApiRows;

@RestController
@RequestMapping("/api/reports")
public class ReportsController {
    private static final int USAGE_LOOKBACK_DAYS = 14;
    private static final int WHAT_TO_BUY_DAYS_LEFT = 3;

    private final JdbcTemplate jdbc;
    private final ZoneId restaurantZone;

    public ReportsController(
            JdbcTemplate jdbc,
            @Value("${app.restaurant-time-zone:Asia/Bangkok}") String restaurantTimeZone) {
        this.jdbc = jdbc;
        this.restaurantZone = ZoneId.of(restaurantTimeZone);
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(@RequestParam Map<String, String> query) {
        DateRange range = range(query);
        if (range == null) {
            throw new ApiException(400, "Invalid report date range");
        }
        try {
            LocalDate usageStart = range.end().minusDays(USAGE_LOOKBACK_DAYS);
            List<Object> rangeArgs = List.of(range.start().toString(), range.end().toString());

            Map<String, Object> summary = ApiRows.normalize(jdbc.queryForMap(
                    "SELECT SUM(amount <= min_stock) AS low_items, SUM(amount = 0) AS out_of_stock, " +
                            "(SELECT COUNT(*) FROM storage_logs WHERE action = 'waste' AND status = 'active' " +
                            "AND date >= ? AND date < ?) AS waste_entries FROM items",
                    rangeArgs.toArray()));

            List<Map<String, Object>> whatToBuy = ApiRows.normalize(jdbc.queryForList(
                    "WITH usage_totals AS (" +
                            " SELECT item_id, SUM(amount_changed) AS used_amount FROM storage_logs " +
                            " WHERE action = 'usage' AND status = 'active' AND date >= ? AND date < ? GROUP BY item_id" +
                            "), candidates AS (" +
                            " SELECT i.id, i.name, i.unit, i.amount, i.min_stock, " +
                            " COALESCE(u.used_amount, 0) AS usage_14_days, " +
                            " CASE WHEN COALESCE(u.used_amount, 0) > 0 " +
                            " THEN i.amount / (u.used_amount / " + USAGE_LOOKBACK_DAYS + ") ELSE NULL END AS days_left, " +
                            " CASE WHEN LOWER(i.unit) IN ('sack', 'bag', 'pcs', 'box', 'bottle', 'can', 'pack') " +
                            " THEN 1 ELSE 100 END AS order_factor " +
                            " FROM items i LEFT JOIN usage_totals u ON u.item_id = i.id " +
                            " WHERE i.amount <= i.min_stock OR (COALESCE(u.used_amount, 0) > 0 " +
                            " AND i.amount / (u.used_amount / " + USAGE_LOOKBACK_DAYS + ") <= " +
                            WHAT_TO_BUY_DAYS_LEFT + ")" +
                            "), priced AS (" +
                            " SELECT c.*, (SELECT isup.price FROM item_suppliers isup " +
                            " JOIN suppliers s ON s.id = isup.supplier_id WHERE isup.item_id = c.id " +
                            " ORDER BY isup.price ASC, s.name ASC LIMIT 1) AS cheapest_price FROM candidates c" +
                            ") SELECT id, name, unit, amount, min_stock, usage_14_days, days_left, " +
                            "CEIL(GREATEST(0, GREATEST(2 * min_stock, 7 * usage_14_days / " +
                            USAGE_LOOKBACK_DAYS + ") - amount) * order_factor) / order_factor AS suggested_order, " +
                            "CASE WHEN 7 * usage_14_days / " + USAGE_LOOKBACK_DAYS + " > 2 * min_stock " +
                            "THEN 'usage_7d' ELSE 'min_x2' END AS order_basis, " +
                            "ROUND((CEIL(GREATEST(0, GREATEST(2 * min_stock, 7 * usage_14_days / " +
                            USAGE_LOOKBACK_DAYS + ") - amount) * order_factor) / order_factor) * " +
                            "COALESCE(cheapest_price, 0), 2) AS estimated_cost, " +
                            "CASE WHEN amount = 0 THEN 'out' WHEN amount <= min_stock THEN 'below_minimum' " +
                            "ELSE 'days_left' END AS reason FROM priced ORDER BY amount ASC, name ASC",
                    usageStart.toString(), range.end().toString()));

            List<Map<String, Object>> supplierRows = ApiRows.normalize(jdbc.queryForList(
                    "SELECT isup.item_id, s.name AS supplier_name, isup.price " +
                            "FROM item_suppliers isup JOIN suppliers s ON s.id = isup.supplier_id " +
                            "ORDER BY isup.item_id, isup.price ASC, s.name ASC"));
            for (Map<String, Object> item : whatToBuy) {
                List<Map<String, Object>> matches = supplierRows.stream()
                        .filter(supplier -> sameId(supplier.get("item_id"), item.get("id"))).toList();
                item.put("suppliers", matches);
                List<Map<String, Object>> prices = matches.stream().map(supplier -> {
                    Map<String, Object> price = new LinkedHashMap<>();
                    price.put("name", supplier.get("supplier_name"));
                    price.put("price", supplier.get("price"));
                    return price;
                }).toList();
                item.put("supplier_prices", prices);
                item.put("cheapest_supplier", prices.isEmpty() ? null : prices.get(0));
            }

            List<Map<String, Object>> usage = ApiRows.normalize(jdbc.queryForList(
                    "SELECT i.id AS item_id, i.name AS item_name, i.unit, " +
                            "COALESCE(SUM(CASE WHEN sl.action = 'usage' THEN sl.amount_changed ELSE 0 END), 0) AS total_used, " +
                            "COALESCE(SUM(CASE WHEN sl.action = 'waste' THEN sl.amount_changed ELSE 0 END), 0) AS total_waste, " +
                            "COALESCE(SUM(CASE WHEN sl.action = 'restock' THEN sl.amount_changed ELSE 0 END), 0) AS total_restocked " +
                            "FROM items i LEFT JOIN storage_logs sl ON sl.item_id = i.id AND sl.status = 'active' " +
                            "AND sl.date >= ? AND sl.date < ? GROUP BY i.id, i.name, i.unit " +
                            "ORDER BY total_used DESC, i.name ASC",
                    range.start().toString(), range.end().toString()));
            for (Map<String, Object> item : usage) {
                item.put("average_daily_use",
                        number(item.get("total_used")) / range.days());
            }

            List<Map<String, Object>> waste = ApiRows.normalize(jdbc.queryForList(
                    "SELECT DATE_FORMAT(CONVERT_TZ(sl.date, @@session.time_zone, ?), '%Y-%m-%d') AS date, " +
                            "sl.item_id, i.name AS item_name, i.unit, sl.amount_changed AS amount, " +
                            "COALESCE(u.display_name, 'Deleted user') AS recorded_by " +
                            "FROM storage_logs sl JOIN items i ON i.id = sl.item_id " +
                            "LEFT JOIN users u ON u.id = sl.user_id " +
                            "WHERE sl.action = 'waste' AND sl.status = 'active' AND sl.date >= ? AND sl.date < ? " +
                            "ORDER BY sl.date DESC, sl.id DESC",
                    restaurantZone.getId(), range.start().toString(), range.end().toString()));

            List<Map<String, Object>> currentStock = ApiRows.normalize(jdbc.queryForList(
                    "SELECT id AS item_id, name AS item_name, amount, unit, min_stock, " +
                            "CASE WHEN amount = 0 THEN 'Out' WHEN amount <= min_stock THEN 'Low' ELSE 'OK' END AS status " +
                            "FROM items ORDER BY name ASC"));

            List<Map<String, Object>> cost = ApiRows.normalize(jdbc.queryForList(
                    "SELECT i.id AS item_id, i.name AS item_name, i.unit, " +
                            "SUM(CASE WHEN sl.action = 'restock' THEN sl.amount_changed ELSE 0 END) AS restocked_amount, " +
                            "ROUND(SUM(CASE WHEN sl.action = 'restock' THEN sl.amount_changed * sl.unit_cost ELSE 0 END), 2) AS purchase_cost, " +
                            "ROUND(SUM(CASE WHEN sl.action = 'usage' THEN sl.amount_changed * sl.unit_cost ELSE 0 END), 2) AS usage_cost, " +
                            "ROUND(SUM(CASE WHEN sl.action = 'waste' THEN sl.amount_changed * sl.unit_cost ELSE 0 END), 2) AS waste_cost " +
                            "FROM storage_logs sl JOIN items i ON i.id = sl.item_id WHERE sl.status = 'active' " +
                            "AND sl.date >= ? AND sl.date < ? GROUP BY i.id, i.name, i.unit ORDER BY i.name ASC",
                    range.start().toString(), range.end().toString()));

            Map<String, Object> costTotals = ApiRows.normalize(jdbc.queryForMap(
                    "SELECT ROUND(SUM(CASE WHEN action = 'restock' THEN amount_changed * unit_cost ELSE 0 END), 2) AS purchase_cost, " +
                            "ROUND(SUM(CASE WHEN action = 'usage' THEN amount_changed * unit_cost ELSE 0 END), 2) AS usage_cost, " +
                            "ROUND(SUM(CASE WHEN action = 'waste' THEN amount_changed * unit_cost ELSE 0 END), 2) AS waste_cost " +
                            "FROM storage_logs WHERE status = 'active' AND date >= ? AND date < ?",
                    range.start().toString(), range.end().toString()));

            List<Map<String, Object>> dailyCostRows = ApiRows.normalize(jdbc.queryForList(
                    "SELECT COALESCE(DATE_FORMAT(CONVERT_TZ(date, @@session.time_zone, ?), '%Y-%m-%d'), " +
                            "DATE_FORMAT(date, '%Y-%m-%d')) AS date, " +
                            "ROUND(SUM(CASE WHEN action = 'usage' THEN amount_changed * unit_cost ELSE 0 END), 2) AS usage_cost, " +
                            "ROUND(SUM(CASE WHEN action = 'waste' THEN amount_changed * unit_cost ELSE 0 END), 2) AS waste_cost " +
                            "FROM storage_logs WHERE status = 'active' AND action IN ('usage', 'waste') " +
                            "AND date >= ? AND date < ? GROUP BY COALESCE(" +
                            "DATE_FORMAT(CONVERT_TZ(date, @@session.time_zone, ?), '%Y-%m-%d'), " +
                            "DATE_FORMAT(date, '%Y-%m-%d')) ORDER BY date ASC",
                    restaurantZone.getId(), range.start().toString(), range.end().toString(), restaurantZone.getId()));
            Map<String, Map<String, Object>> dailyByDate = new LinkedHashMap<>();
            for (Map<String, Object> row : dailyCostRows) {
                dailyByDate.put(String.valueOf(row.get("date")), row);
            }
            List<Map<String, Object>> dailyCost = new ArrayList<>();
            for (LocalDate date = range.start(); date.isBefore(range.end()); date = date.plusDays(1)) {
                Map<String, Object> row = dailyByDate.get(date.toString());
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("date", date.toString());
                output.put("usage_cost", row == null ? "0.00" : row.get("usage_cost"));
                output.put("waste_cost", row == null ? "0.00" : row.get("waste_cost"));
                dailyCost.add(output);
            }

            List<Map<String, Object>> topUsageCost = ApiRows.normalize(jdbc.queryForList(
                    "SELECT sl.item_id, i.name, i.unit, ROUND(SUM(sl.amount_changed * sl.unit_cost), 2) AS usage_cost " +
                            "FROM storage_logs sl JOIN items i ON i.id = sl.item_id " +
                            "WHERE sl.action = 'usage' AND sl.status = 'active' AND sl.date >= ? AND sl.date < ? " +
                            "GROUP BY sl.item_id, i.name, i.unit ORDER BY usage_cost DESC, i.name ASC LIMIT 5",
                    range.start().toString(), range.end().toString()));

            List<Map<String, Object>> wasteByItem = ApiRows.normalize(jdbc.queryForList(
                    "SELECT sl.item_id, i.name, i.unit, " +
                            "ROUND(SUM(CASE WHEN sl.action = 'waste' THEN sl.amount_changed * sl.unit_cost ELSE 0 END), 2) AS waste_cost, " +
                            "ROUND(SUM(CASE WHEN sl.action = 'waste' THEN sl.amount_changed ELSE 0 END) / " +
                            "NULLIF(SUM(CASE WHEN sl.action IN ('usage', 'waste') THEN sl.amount_changed ELSE 0 END), 0) * 100, 1) AS waste_rate_pct " +
                            "FROM storage_logs sl JOIN items i ON i.id = sl.item_id " +
                            "WHERE sl.action IN ('usage', 'waste') AND sl.status = 'active' AND sl.date >= ? AND sl.date < ? " +
                            "GROUP BY sl.item_id, i.name, i.unit " +
                            "HAVING SUM(CASE WHEN sl.action = 'waste' THEN sl.amount_changed ELSE 0 END) > 0 " +
                            "ORDER BY waste_cost DESC, i.name ASC LIMIT 5",
                    range.start().toString(), range.end().toString()));

            List<Map<String, Object>> runningOut = ApiRows.normalize(jdbc.queryForList(
                    "SELECT i.id AS item_id, i.name, i.unit, i.amount AS current, " +
                            "i.amount / (u.used_amount / " + USAGE_LOOKBACK_DAYS + ") AS days_left " +
                            "FROM items i JOIN (SELECT item_id, SUM(amount_changed) AS used_amount FROM storage_logs " +
                            "WHERE action = 'usage' AND status = 'active' AND date >= ? AND date < ? GROUP BY item_id) u " +
                            "ON u.item_id = i.id WHERE u.used_amount > 0 " +
                            "ORDER BY days_left ASC, i.name ASC LIMIT 5",
                    usageStart.toString(), range.end().toString()));

            List<Map<String, Object>> usageDaily = ApiRows.normalize(jdbc.queryForList(
                    "SELECT sl.item_id, i.name, i.unit, " +
                            "COALESCE(DATE_FORMAT(CONVERT_TZ(sl.date, @@session.time_zone, ?), '%Y-%m-%d'), " +
                            "DATE_FORMAT(sl.date, '%Y-%m-%d')) AS date, SUM(sl.amount_changed) AS used " +
                            "FROM storage_logs sl JOIN items i ON i.id = sl.item_id " +
                            "WHERE sl.action = 'usage' AND sl.status = 'active' AND sl.date >= ? AND sl.date < ? " +
                            "GROUP BY sl.item_id, i.name, i.unit, COALESCE(" +
                            "DATE_FORMAT(CONVERT_TZ(sl.date, @@session.time_zone, ?), '%Y-%m-%d'), " +
                            "DATE_FORMAT(sl.date, '%Y-%m-%d')) ORDER BY date ASC, i.name ASC",
                    restaurantZone.getId(), range.start().toString(), range.end().toString(), restaurantZone.getId()));

            List<Map<String, Object>> restockFrequency = ApiRows.normalize(jdbc.queryForList(
                    "SELECT i.id AS item_id, i.name, i.unit, " +
                            "COUNT(DISTINCT CASE WHEN sl.id IS NOT NULL THEN COALESCE(" +
                            "DATE_FORMAT(CONVERT_TZ(sl.date, @@session.time_zone, ?), '%Y-%m-%d'), " +
                            "DATE_FORMAT(sl.date, '%Y-%m-%d')) END) AS restock_days, " +
                            "(SELECT COALESCE(DATE_FORMAT(CONVERT_TZ(sl2.date, @@session.time_zone, ?), '%Y-%m-%d'), " +
                            "DATE_FORMAT(sl2.date, '%Y-%m-%d')) FROM storage_logs sl2 WHERE sl2.item_id = i.id " +
                            "AND sl2.action = 'restock' AND sl2.status = 'active' " +
                            "ORDER BY sl2.date DESC, sl2.id DESC LIMIT 1) AS last_restock_date " +
                            "FROM items i LEFT JOIN storage_logs sl ON sl.item_id = i.id AND sl.action = 'restock' " +
                            "AND sl.status = 'active' AND sl.date >= ? AND sl.date < ? " +
                            "GROUP BY i.id, i.name, i.unit ORDER BY i.name ASC",
                    restaurantZone.getId(), restaurantZone.getId(),
                    range.start().toString(), range.end().toString()));

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("range", range.response());
            response.put("summary", summary);
            response.put("what_to_buy", whatToBuy);
            response.put("what_to_buy_total", sumMoney(whatToBuy, "estimated_cost"));
            response.put("usage", usage);
            response.put("waste", waste);
            response.put("current_stock", currentStock);
            response.put("cost", cost);
            response.put("cost_totals", costTotals);
            response.put("daily_cost", dailyCost);
            response.put("top_usage_cost", topUsageCost);
            response.put("waste_by_item", wasteByItem);
            response.put("running_out", runningOut);
            response.put("running_out_threshold_days", WHAT_TO_BUY_DAYS_LEFT);
            response.put("usage_daily", usageDaily);
            response.put("restock_frequency", restockFrequency);
            return response;
        } catch (ApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ApiException(500, "Failed to load report");
        }
    }

    @GetMapping({"/usage", "/waste"})
    public List<Map<String, Object>> legacyReport(@RequestParam(required = false) String period,
                                                   jakarta.servlet.http.HttpServletRequest request) {
        if (!"week".equals(period) && !"month".equals(period)) {
            throw new ApiException(400, "period must be week or month");
        }
        int days = "month".equals(period) ? 30 : 7;
        LocalDate today = LocalDate.now(restaurantZone);
        LocalDate start = today.minusDays(days - 1L);
        LocalDate end = today.plusDays(1);
        String action = request.getRequestURI().endsWith("waste") ? "waste" : "usage";
        try {
            return ApiRows.normalize(jdbc.queryForList(
                    "SELECT sl.item_id, i.name AS item_name, " +
                            "DATE_FORMAT(CONVERT_TZ(sl.date, @@session.time_zone, ?), '%Y-%m-%d') AS date, " +
                            "SUM(sl.amount_changed) AS amount FROM storage_logs sl " +
                            "LEFT JOIN items i ON i.id = sl.item_id " +
                            "WHERE sl.action = ? AND sl.status = 'active' AND sl.date >= ? AND sl.date < ? " +
                            "GROUP BY sl.item_id, DATE(sl.date) ORDER BY date ASC, item_name ASC",
                    restaurantZone.getId(), action, start.toString(), end.toString()));
        } catch (RuntimeException ex) {
            throw new ApiException(500, "Failed to load report");
        }
    }

    @GetMapping({"/restock-suggestions", "/what-to-buy"})
    public List<Map<String, Object>> restockSuggestions() {
        try {
            return ApiRows.normalize(jdbc.queryForList(
                    "SELECT * FROM items WHERE amount <= min_stock ORDER BY amount ASC"));
        } catch (RuntimeException ex) {
            throw new ApiException(500, "Failed to load report");
        }
    }

    private DateRange range(Map<String, String> query) {
        LocalDate today = LocalDate.now(restaurantZone);
        if ("custom".equals(query.get("range"))) {
            String startText = query.get("start");
            String endText = query.get("end");
            if (startText == null || endText == null
                    || !startText.matches("\\d{4}-\\d{2}-\\d{2}")
                    || !endText.matches("\\d{4}-\\d{2}-\\d{2}")
                    || startText.compareTo(endText) > 0) {
                return null;
            }
            try {
                LocalDate start = LocalDate.parse(startText);
                LocalDate end = LocalDate.parse(endText).plusDays(1);
                return new DateRange(start, end, Math.max(1, (int) (end.toEpochDay() - start.toEpochDay())));
            } catch (DateTimeParseException | ArithmeticException ex) {
                return null;
            }
        }
        int days = "today".equals(query.get("range")) ? 1
                : "30".equals(query.get("range")) ? 30 : 7;
        return new DateRange(today.minusDays(days - 1L), today.plusDays(1), days);
    }

    private static boolean sameId(Object first, Object second) {
        return first != null && second != null && String.valueOf(first).equals(String.valueOf(second));
    }

    private static double number(Object value) {
        if (value == null) {
            return 0d;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0d;
        }
    }

    private static String sumMoney(List<Map<String, Object>> rows, String field) {
        BigInteger cents = BigInteger.ZERO;
        for (Map<String, Object> row : rows) {
            Object rawValue = row.get(field);
            String value = rawValue == null ? "0.00" : String.valueOf(rawValue);
            String[] parts = value.split("\\.", -1);
            BigInteger whole = new BigInteger(parts[0].isBlank() ? "0" : parts[0]);
            String fractional = parts.length > 1 ? parts[1] : "";
            String padded = (fractional + "00").substring(0, 2);
            cents = cents.add(whole.multiply(BigInteger.valueOf(100))
                    .add(new BigInteger(padded)));
        }
        BigInteger[] parts = cents.divideAndRemainder(BigInteger.valueOf(100));
        String fractional = parts[1].abs().toString();
        return parts[0] + "." + "0".repeat(Math.max(0, 2 - fractional.length())) + fractional;
    }

    private record DateRange(LocalDate start, LocalDate end, int days) {
        Map<String, Object> response() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("start", start.toString());
            result.put("end", end.toString());
            result.put("days", days);
            return result;
        }
    }
}
