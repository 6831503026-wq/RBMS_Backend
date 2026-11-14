package com.mfu.rbms.controller;

import com.mfu.rbms.api.ApiException;
import com.mfu.rbms.api.ApiRows;
import com.mfu.rbms.api.ApiValidation;
import com.mfu.rbms.security.ApiIdentity;
import com.mfu.rbms.service.LowStockEmailService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/items")
public class ItemsController {
    private static final List<String> ITEM_FIELDS =
            List.of("name", "unit", "amount", "min_stock", "last_alert_date");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final LowStockEmailService emailService;

    public ItemsController(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            LowStockEmailService emailService) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.emailService = emailService;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        try {
            List<Map<String, Object>> items = ApiRows.normalize(jdbc.queryForList("SELECT * FROM items"));
            List<Map<String, Object>> suppliers = ApiRows.normalize(jdbc.queryForList(
                    "SELECT isup.item_id, isup.supplier_id, s.name AS supplier_name, isup.price " +
                            "FROM item_suppliers isup JOIN suppliers s ON s.id = isup.supplier_id " +
                            "ORDER BY isup.item_id, isup.price ASC, s.name ASC"));
            for (Map<String, Object> item : items) {
                List<Map<String, Object>> itemSuppliers = new ArrayList<>();
                for (Map<String, Object> supplier : suppliers) {
                    if (String.valueOf(supplier.get("item_id")).equals(String.valueOf(item.get("id")))) {
                        Map<String, Object> link = new LinkedHashMap<>();
                        link.put("supplier_id", supplier.get("supplier_id"));
                        link.put("supplier_name", supplier.get("supplier_name"));
                        link.put("price", supplier.get("price"));
                        itemSuppliers.add(link);
                    }
                }
                item.put("suppliers", itemSuppliers);
            }
            return items;
        } catch (RuntimeException ex) {
            throw new ApiException(500, "Failed to fetch items");
        }
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM items WHERE id = ?", id);
            if (rows.isEmpty()) {
                throw new ApiException(404, "Item not found");
            }
            return ApiRows.normalize(rows.get(0));
        } catch (ApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ApiException(500, "Failed to fetch item");
        }
    }

    @GetMapping("/{id}/price-history")
    public List<Map<String, Object>> priceHistory(@PathVariable String id) {
        try {
            return ApiRows.normalize(jdbc.queryForList(
                    "SELECT ph.changed_at AS date, s.name AS supplier_name, ph.old_price, ph.new_price, " +
                            "COALESCE(u.username, 'Deleted user') AS user_name " +
                            "FROM price_history ph " +
                            "JOIN item_suppliers isup ON isup.id = ph.item_supplier_id " +
                            "JOIN suppliers s ON s.id = isup.supplier_id " +
                            "LEFT JOIN users u ON u.id = ph.changed_by " +
                            "WHERE isup.item_id = ? ORDER BY ph.changed_at DESC, ph.id DESC LIMIT 50",
                    id));
        } catch (RuntimeException ex) {
            throw new ApiException(500, "Failed to fetch price history");
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal ApiIdentity actor) {
        Object supplierId = body.get("supplier_id");
        Object price = body.get("price");
        if (!ApiValidation.positiveInteger(supplierId)
                || !Double.isFinite(ApiValidation.number(price)) || ApiValidation.number(price) <= 0) {
            throw new ApiException(400, "supplier_id and a price greater than zero are required");
        }
        String name = requiredText(body.get("name"), "name", 150);
        String unit = requiredText(body.get("unit"), "unit", 32);
        for (String field : List.of("amount", "min_stock")) {
            if (body.containsKey(field) && (!Double.isFinite(ApiValidation.number(body.get(field)))
                    || ApiValidation.number(body.get(field)) < 0)) {
                throw new ApiException(400, field + " must be a non-negative number");
            }
        }
        try {
            if (jdbc.queryForList("SELECT id FROM suppliers WHERE id = ?", supplierId).isEmpty()) {
                throw new ApiException(400, "supplier_id must reference an existing supplier");
            }

            Map<String, Object> data = new LinkedHashMap<>();
            for (String field : ITEM_FIELDS) {
                if (body.containsKey(field)) {
                    data.put(field, body.get(field));
                }
            }
            data.put("name", name);
            data.put("unit", unit);
            data.put("avg_cost", price);

            long itemId = transactions.execute(status -> insertItem(data, supplierId, price, actor));
            Map<String, Object> created = ApiRows.normalize(
                    jdbc.queryForMap("SELECT * FROM items WHERE id = ?", itemId));
            if (ApiValidation.number(created.get("amount")) <= ApiValidation.number(created.get("min_stock"))) {
                emailService.sendAfterCreate(created);
            }
            return created;
        } catch (ApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ApiException(500, "Failed to create item");
        }
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        if (body.containsKey("amount") || body.containsKey("avg_cost")) {
            throw new ApiException(400, "Stock changes go through stock updates");
        }
        if (!ApiValidation.positiveInteger(id)) {
            throw new ApiException(400, "id must be a positive integer");
        }
        if (body.containsKey("name")) {
            validateOptionalText(body.get("name"), "name", 150);
        }
        if (body.containsKey("unit")) {
            validateOptionalText(body.get("unit"), "unit", 32);
        }
        if (body.containsKey("min_stock") && (!Double.isFinite(ApiValidation.number(body.get("min_stock")))
                || ApiValidation.number(body.get("min_stock")) < 0)) {
            throw new ApiException(400, "min_stock must be a non-negative number");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (String field : ITEM_FIELDS) {
            if (body.containsKey(field)) {
                values.put(field, body.get(field));
            }
        }
        if (values.isEmpty()) {
            throw new ApiException(400, "No valid item fields provided");
        }
        try {
            List<Object> args = new ArrayList<>(values.values());
            String assignments = String.join(", ", values.keySet().stream().map(field -> field + " = ?").toList());
            args.add(id);
            int changed = jdbc.update("UPDATE items SET " + assignments + " WHERE id = ?", args.toArray());
            if (changed == 0) {
                throw new ApiException(404, "Item not found");
            }
            return ApiRows.normalize(jdbc.queryForMap("SELECT * FROM items WHERE id = ?", id));
        } catch (ApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ApiException(500, "Failed to update item");
        }
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        try {
            int changed = jdbc.update("DELETE FROM items WHERE id = ?", id);
            if (changed == 0) {
                throw new ApiException(404, "Item not found");
            }
        } catch (ApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ApiException(500, "Failed to delete item");
        }
    }

    private long insertItem(
            Map<String, Object> data,
            Object supplierId,
            Object price,
            ApiIdentity actor) {
        List<String> fields = new ArrayList<>(data.keySet());
        String fieldSql = String.join(", ", fields);
        String placeholders = String.join(", ", fields.stream().map(field -> "?").toList());
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO items (" + fieldSql + ") VALUES (" + placeholders + ")",
                    Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < fields.size(); index++) {
                statement.setObject(index + 1, data.get(fields.get(index)));
            }
            return statement;
        }, keyHolder);
        long itemId = keyHolder.getKeys().entrySet().stream()
                .filter(entry -> "id".equalsIgnoreCase(String.valueOf(entry.getKey())))
                .map(Map.Entry::getValue)
                .map(Number.class::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Item insert did not return an id"))
                .longValue();
        jdbc.update("INSERT INTO item_suppliers (item_id, supplier_id, price) VALUES (?, ?, ?)",
                itemId, supplierId, price);
        long itemSupplierId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO price_history (item_supplier_id, old_price, new_price, changed_by) " +
                        "VALUES (?, NULL, ?, ?)",
                itemSupplierId, price, actor.id());
        if (ApiValidation.number(data.get("amount")) > 0) {
            jdbc.update("INSERT INTO storage_logs " +
                            "(item_id, action, amount_changed, staff_name, user_id, unit_cost, supplier_id) " +
                            "VALUES (?, 'restock', ?, ?, ?, ?, ?)",
                    itemId, data.get("amount"), actor.displayName(), actor.id(), price, supplierId);
        }
        return itemId;
    }

    private static String requiredText(Object value, String field, int maxLength) {
        validateText(value, field, maxLength);
        return (String) value;
    }

    private static void validateText(Object value, String field, int maxLength) {
        if (!(value instanceof String text) || text.trim().isEmpty() || text.trim().length() > maxLength) {
            throw new ApiException(400, field + " is required and must be " + maxLength + " characters or fewer");
        }
    }

    private static void validateOptionalText(Object value, String field, int maxLength) {
        if (!(value instanceof String text) || text.trim().isEmpty() || text.trim().length() > maxLength) {
            throw new ApiException(400, field + " must be a non-empty string of "
                    + maxLength + " characters or fewer");
        }
    }
}
