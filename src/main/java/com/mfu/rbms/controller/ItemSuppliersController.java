package com.mfu.rbms.controller;

import com.mfu.rbms.api.ApiException;
import com.mfu.rbms.api.ApiRows;
import com.mfu.rbms.api.ApiValidation;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/item-suppliers")
public class ItemSuppliersController {
    private static final String SELECT = "SELECT isup.id, isup.item_id, isup.supplier_id, isup.price, " +
            "i.name AS item_name, s.name AS supplier_name " +
            "FROM item_suppliers isup JOIN items i ON i.id = isup.item_id " +
            "JOIN suppliers s ON s.id = isup.supplier_id ";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public ItemSuppliersController(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        try {
            return ApiRows.normalize(jdbc.queryForList(SELECT + "ORDER BY i.name, isup.price, s.name"));
        } catch (RuntimeException ex) {
            throw new ApiException(500, "Failed to fetch item suppliers");
        }
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(SELECT + "WHERE isup.id = ?", id);
            if (rows.isEmpty()) {
                throw new ApiException(404, "Item supplier not found");
            }
            return ApiRows.normalize(rows.get(0));
        } catch (ApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ApiException(500, "Failed to fetch item supplier");
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        Object itemId = body.get("item_id");
        Object supplierId = body.get("supplier_id");
        Object price = body.get("price");
        if (!ApiValidation.positiveInteger(itemId) || !ApiValidation.positiveInteger(supplierId)
                || !Double.isFinite(ApiValidation.number(price)) || ApiValidation.number(price) <= 0) {
            throw new ApiException(400,
                    "item_id, supplier_id, and a price greater than zero are required");
        }
        try {
            long linkId = transactions.execute(status -> {
                jdbc.update("INSERT INTO item_suppliers (item_id, supplier_id, price) VALUES (?, ?, ?)",
                        itemId, supplierId, price);
                Long insertedId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                jdbc.update("INSERT INTO price_history (item_supplier_id, old_price, new_price, changed_by) " +
                                "VALUES (?, NULL, ?, ?)",
                        insertedId, price, currentUserId());
                return insertedId;
            });
            return ApiRows.normalize(jdbc.queryForMap(SELECT + "WHERE isup.id = ?", linkId));
        } catch (RuntimeException ex) {
            throw new ApiException(500, "Failed to create item supplier");
        }
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Object rawPrice = body.get("price");
        if (!body.containsKey("price") || !Double.isFinite(ApiValidation.number(rawPrice))
                || ApiValidation.number(rawPrice) <= 0) {
            throw new ApiException(400, "A price greater than zero is required");
        }
        try {
            transactions.executeWithoutResult(status -> {
                List<Map<String, Object>> current = jdbc.queryForList(
                        "SELECT price FROM item_suppliers WHERE id = ? FOR UPDATE", id);
                if (current.isEmpty()) {
                    throw new ApiException(404, "Item supplier not found");
                }
                Object oldPrice = current.get(0).get("price");
                if (sameNumericValue(oldPrice, rawPrice)) {
                    return;
                }
                jdbc.update("UPDATE item_suppliers SET price = ? WHERE id = ?", rawPrice, id);
                jdbc.update("INSERT INTO price_history (item_supplier_id, old_price, new_price, changed_by) " +
                                "VALUES (?, ?, ?, ?)",
                        id, oldPrice, rawPrice, currentUserId());
            });
            return ApiRows.normalize(jdbc.queryForMap(SELECT + "WHERE isup.id = ?", id));
        } catch (ApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ApiException(500, "Failed to update item supplier");
        }
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        try {
            transactions.executeWithoutResult(status -> {
                List<Map<String, Object>> links = jdbc.queryForList(
                        "SELECT item_id FROM item_suppliers WHERE id = ? FOR UPDATE", id);
                if (links.isEmpty()) {
                    throw new ApiException(404, "Item supplier not found");
                }
                Object itemId = links.get(0).get("item_id");
                Integer count = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM item_suppliers WHERE item_id = ?", Integer.class, itemId);
                if (count != null && count <= 1) {
                    throw new ApiException(400, "An item must have at least one supplier");
                }
                jdbc.update("DELETE FROM item_suppliers WHERE id = ?", id);
            });
        } catch (ApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ApiException(500, "Failed to delete item supplier");
        }
    }

    private static boolean sameNumericValue(Object first, Object second) {
        try {
            return new BigDecimal(String.valueOf(first)).compareTo(new BigDecimal(String.valueOf(second))) == 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private long currentUserId() {
        var authentication = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return ((com.mfu.rbms.security.ApiIdentity) authentication.getPrincipal()).id();
    }
}
