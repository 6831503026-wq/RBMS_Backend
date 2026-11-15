package com.mfu.rbms.controller;

import com.mfu.rbms.api.ApiException;
import com.mfu.rbms.api.ApiRows;
import com.mfu.rbms.api.ApiValidation;
import com.mfu.rbms.security.ApiIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class StorageController {
    private static final Logger LOGGER = LoggerFactory.getLogger(StorageController.class);
    private static final Set<String> ACTIONS = Set.of("restock", "usage", "waste");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public StorageController(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @GetMapping("/storage-logs")
    public List<Map<String, Object>> storageLogs() {
        try {
            return ApiRows.normalize(jdbc.queryForList(
                    "SELECT sl.id, sl.item_id, i.name AS item_name, i.unit, sl.action, " +
                            "sl.amount_changed, sl.`date`, sl.staff_name, sl.unit_cost " +
                            "FROM storage_logs sl JOIN items i ON i.id = sl.item_id " +
                            "ORDER BY sl.`date` DESC, sl.id DESC"));
        } catch (RuntimeException ex) {
            LOGGER.error("Failed to load storage history", ex);
            throw new ApiException(500, "Failed to load storage history");
        }
    }

    @PostMapping("/stock/bulk-change")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> bulkChange(
            @RequestBody Map<String, Object> request,
            @AuthenticationPrincipal ApiIdentity actor) {
        Object rawChanges = request.get("changes");
        if (!(rawChanges instanceof List<?> changes) || changes.isEmpty()) {
            throw new ApiException(400, "At least one stock change is required");
        }
        try {
            List<Map<String, Object>> results = transactions.execute(status -> applyChanges(changes, actor));
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("message", "Stock update recorded");
            response.put("items", results);
            return response;
        } catch (ApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ApiException(500, "Failed to record stock changes");
        }
    }

    private List<Map<String, Object>> applyChanges(List<?> changes, ApiIdentity actor) {
        List<ValidatedChange> validated = new ArrayList<>();
        for (Object raw : changes) {
            if (!(raw instanceof Map<?, ?> change)) {
                throw new ApiException(500, "Failed to record stock changes");
            }
            Object itemIdValue = change.get("item_id");
            double itemIdNumber = ApiValidation.number(itemIdValue);
            double newAmount = ApiValidation.number(change.get("new_amount"));
            Object actionValue = change.get("action");
            String action = actionValue instanceof String text ? text : "";
            if (!Double.isFinite(itemIdNumber) || itemIdNumber != Math.floor(itemIdNumber)
                    || !Double.isFinite(newAmount) || newAmount < 0 || !ACTIONS.contains(action)) {
                String label = itemIdValue == null || String.valueOf(itemIdValue).isBlank()
                        ? "unknown" : String.valueOf(itemIdValue);
                throw new ApiException(400, "Invalid stock change for item " + label);
            }
            long itemId = (long) itemIdNumber;
            List<Map<String, Object>> itemRows = jdbc.queryForList(
                    "SELECT id, name, unit, amount, avg_cost, min_stock FROM items WHERE id = ? FOR UPDATE",
                    itemId);
            if (itemRows.isEmpty()) {
                throw new ApiException(400, "Item " + itemId + " not found");
            }
            Map<String, Object> item = ApiRows.normalize(itemRows.get(0));
            double previousAmount = ApiValidation.number(item.get("amount"));
            double difference = Math.abs(newAmount - previousAmount);
            String itemName = String.valueOf(item.get("name"));
            if ("restock".equals(action) && newAmount <= previousAmount) {
                throw new ApiException(400, itemName + ": restock must increase stock");
            }
            if (("usage".equals(action) || "waste".equals(action)) && newAmount >= previousAmount) {
                throw new ApiException(400, itemName + ": " + action + " must decrease stock");
            }
            if (difference <= 0) {
                throw new ApiException(400, itemName + ": stock change must be non-zero");
            }

            Object supplierId = null;
            Object unitCost = item.get("avg_cost");
            if ("restock".equals(action)) {
                Object rawSupplierId = change.get("supplier_id");
                if (!ApiValidation.positiveInteger(rawSupplierId)) {
                    throw new ApiException(400, itemName + ": supplier is required for restock");
                }
                supplierId = rawSupplierId;
                List<Map<String, Object>> supplierRows = jdbc.queryForList(
                        "SELECT price FROM item_suppliers WHERE item_id = ? AND supplier_id = ?",
                        itemId, supplierId);
                if (supplierRows.isEmpty()) {
                    throw new ApiException(400, itemName + ": supplier is not linked to this item");
                }
                unitCost = supplierRows.get(0).get("price");
            } else if (ApiValidation.number(item.get("avg_cost")) <= 0) {
                throw new ApiException(400, itemName + " has no cost basis");
            }
            validated.add(new ValidatedChange(itemId, item, action, difference, supplierId, unitCost));
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (ValidatedChange change : validated) {
            BigDecimal difference = BigDecimal.valueOf(change.difference());
            jdbc.update("INSERT INTO storage_logs " +
                            "(item_id, action, amount_changed, reason, staff_name, user_id, unit_cost, supplier_id) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    change.itemId(), change.action(), difference, null, actor.displayName(), actor.id(),
                    change.unitCost(), change.supplierId());
            if ("restock".equals(change.action())) {
                Map<String, Object> current = jdbc.queryForMap(
                        "SELECT amount, avg_cost FROM items WHERE id = ?", change.itemId());
                BigDecimal previousAmount = new BigDecimal(String.valueOf(current.get("amount")));
                BigDecimal previousCost = new BigDecimal(String.valueOf(current.get("avg_cost")));
                BigDecimal purchaseCost = new BigDecimal(String.valueOf(change.unitCost()));
                BigDecimal newAmount = previousAmount.add(difference);
                BigDecimal newAverageCost = previousAmount.multiply(previousCost)
                        .add(difference.multiply(purchaseCost))
                        .divide(newAmount, 4, RoundingMode.HALF_UP);
                jdbc.update("UPDATE items SET avg_cost = ?, amount = amount + ? WHERE id = ?",
                        newAverageCost, difference, change.itemId());
            } else {
                jdbc.update("UPDATE items SET amount = amount - ? WHERE id = ?", difference, change.itemId());
            }
            Map<String, Object> result = new LinkedHashMap<>(change.originalItem());
            double previousAmount = ApiValidation.number(change.originalItem().get("amount"));
            double updatedAmount = "restock".equals(change.action())
                    ? previousAmount + change.difference() : previousAmount - change.difference();
            result.put("amount", jsonNumber(updatedAmount));
            results.add(result);
        }
        return results;
    }

    private static Object jsonNumber(double value) {
        if (value == Math.rint(value) && value >= Long.MIN_VALUE && value <= Long.MAX_VALUE) {
            return (long) value;
        }
        return value;
    }

    private record ValidatedChange(
            long itemId,
            Map<String, Object> originalItem,
            String action,
            double difference,
            Object supplierId,
            Object unitCost) {
    }
}
