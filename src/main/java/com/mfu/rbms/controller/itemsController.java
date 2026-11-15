package com.mfu.rbms.controller;

import com.mfu.rbms.api.ApiException;
import com.mfu.rbms.api.ApiRows;
import com.mfu.rbms.api.ApiValidation;
import com.mfu.rbms.model.Item;
import com.mfu.rbms.model.ItemSupplier;
import com.mfu.rbms.model.Supplier;
import com.mfu.rbms.repository.ItemRepository;
import com.mfu.rbms.repository.ItemSupplierRepository;
import com.mfu.rbms.repository.SupplierRepository;
import com.mfu.rbms.security.ApiIdentity;
import com.mfu.rbms.service.LowStockEmailService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/items")
public class ItemsController {
    private static final List<String> ITEM_FIELDS =
            List.of("name", "unit", "amount", "min_stock", "last_alert_date");

    private final ItemRepository itemRepository;
    private final SupplierRepository supplierRepository;
    private final ItemSupplierRepository itemSupplierRepository;
    private final JdbcTemplate jdbc;
    private final LowStockEmailService emailService;

    public ItemsController(
            ItemRepository itemRepository,
            SupplierRepository supplierRepository,
            ItemSupplierRepository itemSupplierRepository,
            JdbcTemplate jdbc,
            LowStockEmailService emailService) {
        this.itemRepository = itemRepository;
        this.supplierRepository = supplierRepository;
        this.itemSupplierRepository = itemSupplierRepository;
        this.jdbc = jdbc;
        this.emailService = emailService;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        try {
            Map<Long, List<Map<String, Object>>> suppliersByItem = new LinkedHashMap<>();
            for (ItemSupplier itemSupplier : itemSupplierRepository.findAllWithItemAndSupplier()) {
                Map<String, Object> link = new LinkedHashMap<>();
                link.put("supplier_id", itemSupplier.getSupplier().getId());
                link.put("supplier_name", itemSupplier.getSupplier().getName());
                link.put("price", decimalText(itemSupplier.getPrice(), 4));
                suppliersByItem.computeIfAbsent(itemSupplier.getItem().getId(), key -> new ArrayList<>())
                        .add(link);
            }

            List<Map<String, Object>> items = new ArrayList<>();
            for (Item item : itemRepository.findAll()) {
                Map<String, Object> row = itemRow(item);
                row.put("suppliers", suppliersByItem.getOrDefault(item.getId(), List.of()));
                items.add(row);
            }
            return items;
        } catch (RuntimeException ex) {
            throw new ApiException(500, "Failed to fetch items");
        }
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        try {
            Item item = itemRepository.findById(Long.parseLong(id))
                    .orElseThrow(() -> new ApiException(404, "Item not found"));
            return itemRow(item);
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
    @Transactional
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
            long supplierKey = Long.parseLong(supplierId.toString());
            Supplier supplier = supplierRepository.findById(supplierKey)
                    .orElseThrow(() -> new ApiException(
                            400, "supplier_id must reference an existing supplier"));

            Item item = new Item();
            item.setName(name);
            item.setUnit(unit);
            if (body.containsKey("amount")) {
                item.setAmount(decimal(body.get("amount")));
            }
            if (body.containsKey("min_stock")) {
                item.setMinStock(decimal(body.get("min_stock")));
            }
            item.setAvgCost(decimal(price));
            if (body.containsKey("last_alert_date")) {
                item.setLastAlertDate(localDate(body.get("last_alert_date")));
            }
            item = itemRepository.saveAndFlush(item);

            ItemSupplier itemSupplier = new ItemSupplier();
            itemSupplier.setItem(item);
            itemSupplier.setSupplier(supplier);
            itemSupplier.setPrice(decimal(price));
            itemSupplier = itemSupplierRepository.saveAndFlush(itemSupplier);

            insertPriceHistoryAndStorageLog(item, itemSupplier, price, body, actor);

            Map<String, Object> created = itemRow(item);
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
        if (ITEM_FIELDS.stream().noneMatch(body::containsKey)) {
            throw new ApiException(400, "No valid item fields provided");
        }
        try {
            Item item = itemRepository.findById(Long.parseLong(id))
                    .orElseThrow(() -> new ApiException(404, "Item not found"));
            if (body.containsKey("name")) {
                item.setName((String) body.get("name"));
            }
            if (body.containsKey("unit")) {
                item.setUnit((String) body.get("unit"));
            }
            if (body.containsKey("min_stock")) {
                item.setMinStock(decimal(body.get("min_stock")));
            }
            if (body.containsKey("last_alert_date")) {
                item.setLastAlertDate(localDate(body.get("last_alert_date")));
            }
            return itemRow(itemRepository.saveAndFlush(item));
        } catch (ApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ApiException(500, "Failed to update item");
        }
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable String id) {
        try {
            Item item = itemRepository.findById(Long.parseLong(id))
                    .orElseThrow(() -> new ApiException(404, "Item not found"));

            jdbc.update("DELETE ph FROM price_history ph " +
                    "JOIN item_suppliers isup ON isup.id = ph.item_supplier_id " +
                    "WHERE isup.item_id = ?", item.getId());
            jdbc.update("DELETE FROM storage_logs WHERE item_id = ?", item.getId());

            List<ItemSupplier> itemSuppliers = itemSupplierRepository.findAllWithItemAndSupplier().stream()
                    .filter(itemSupplier -> itemSupplier.getItem().getId().equals(item.getId()))
                    .toList();
            itemSupplierRepository.deleteAll(itemSuppliers);
            itemRepository.delete(item);
        } catch (ApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ApiException(500, "Failed to delete item");
        }
    }

    private void insertPriceHistoryAndStorageLog(
            Item item,
            ItemSupplier itemSupplier,
            Object price,
            Map<String, Object> body,
            ApiIdentity actor) {
        jdbc.update("INSERT INTO price_history (item_supplier_id, old_price, new_price, changed_by) " +
                        "VALUES (?, NULL, ?, ?)",
                itemSupplier.getId(), price, actor.id());
        if (ApiValidation.number(body.get("amount")) > 0) {
            jdbc.update("INSERT INTO storage_logs " +
                            "(item_id, action, amount_changed, staff_name, user_id, unit_cost, supplier_id) " +
                            "VALUES (?, 'restock', ?, ?, ?, ?, ?)",
                    item.getId(), body.get("amount"), actor.displayName(), actor.id(), price,
                    itemSupplier.getSupplier().getId());
        }
    }

    private static Map<String, Object> itemRow(Item item) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", item.getId());
        row.put("name", item.getName());
        row.put("unit", item.getUnit());
        row.put("amount", decimalText(item.getAmount(), 3));
        row.put("min_stock", decimalText(item.getMinStock(), 3));
        row.put("avg_cost", decimalText(item.getAvgCost(), 4));
        row.put("last_alert_date", item.getLastAlertDate() == null ? null : item.getLastAlertDate().toString());
        return ApiRows.normalize(row);
    }

    private static String decimalText(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? null : new BigDecimal(value.toString());
    }

    private static LocalDate localDate(Object value) {
        return value == null ? null : LocalDate.parse(value.toString());
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
