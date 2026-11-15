package com.mfu.rbms.controller;

import com.mfu.rbms.api.ApiException;
import com.mfu.rbms.api.ApiRows;
import com.mfu.rbms.api.ApiValidation;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.sql.Statement;

@RestController
@RequestMapping("/api/suppliers")
public class SuppliersController {
    private static final Logger LOGGER = LoggerFactory.getLogger(SuppliersController.class);
    private static final List<String> FIELDS = List.of("name", "contact_email", "phone");

    private final JdbcTemplate jdbc;

    public SuppliersController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        try {
            return ApiRows.normalize(jdbc.queryForList("SELECT * FROM suppliers ORDER BY name"));
        } catch (RuntimeException ex) {
            throw new ApiException(500, "Failed to fetch suppliers");
        }
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM suppliers WHERE id = ?", id);
            if (rows.isEmpty()) {
                throw new ApiException(404, "Supplier not found");
            }
            return ApiRows.normalize(rows.get(0));
        } catch (ApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ApiException(500, "Failed to fetch supplier");
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        Map<String, Object> values = selected(body);
        validateName(values.get("name"), true);
        validateContactFields(values);
        List<String> fields = new ArrayList<>(values.keySet());
        try {
            String sql = "INSERT INTO suppliers (" + String.join(", ", fields) + ") VALUES (" +
                    String.join(", ", fields.stream().map(field -> "?").toList()) + ")";
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                for (int index = 0; index < fields.size(); index++) {
                    statement.setObject(index + 1, values.get(fields.get(index)));
                }
                return statement;
            }, keyHolder);
            Long id = generatedId(keyHolder);
            return ApiRows.normalize(jdbc.queryForMap("SELECT * FROM suppliers WHERE id = ?", id));
        } catch (RuntimeException ex) {
            LOGGER.error("Failed to create supplier", ex);
            throw new ApiException(500, "Failed to create supplier");
        }
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        if (!isPositiveInteger(id)) {
            throw new ApiException(400, "id must be a positive integer");
        }
        Map<String, Object> values = selected(body);
        if (values.isEmpty()) {
            throw new ApiException(400, "No valid supplier fields provided");
        }
        if (values.containsKey("name")) {
            validateName(values.get("name"), false);
        }
        validateContactFields(values);
        List<String> fields = new ArrayList<>(values.keySet());
        List<Object> args = new ArrayList<>(values.values());
        args.add(id);
        try {
            String assignments = String.join(", ", fields.stream().map(field -> field + " = ?").toList());
            int changed = jdbc.update("UPDATE suppliers SET " + assignments + " WHERE id = ?", args.toArray());
            if (changed == 0) {
                throw new ApiException(404, "Supplier not found");
            }
            return ApiRows.normalize(jdbc.queryForMap("SELECT * FROM suppliers WHERE id = ?", id));
        } catch (ApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ApiException(500, "Failed to update supplier");
        }
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        try {
            int changed = jdbc.update("DELETE FROM suppliers WHERE id = ?", id);
            if (changed == 0) {
                throw new ApiException(404, "Supplier not found");
            }
        } catch (ApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ApiException(500, "Failed to delete supplier");
        }
    }

    private static Map<String, Object> selected(Map<String, Object> body) {
        Map<String, Object> selected = new LinkedHashMap<>();
        for (String field : FIELDS) {
            if (body.containsKey(field)) {
                selected.put(field, body.get(field));
            }
        }
        return selected;
    }

    private static void validateName(Object value, boolean required) {
        String message = required
                ? "name is required and must be 150 characters or fewer"
                : "name must be a non-empty string of 150 characters or fewer";
        if (!(value instanceof String text) || text.trim().isEmpty() || text.trim().length() > 150) {
            throw new ApiException(400, message);
        }
    }

    private static void validateContactFields(Map<String, Object> values) {
        Object email = values.get("contact_email");
        if (values.containsKey("contact_email")
                && (!(email instanceof String text) || text.length() > 254)) {
            throw new ApiException(400, "contact_email must be 254 characters or fewer");
        }
        Object phone = values.get("phone");
        if (values.containsKey("phone")
                && (!(phone instanceof String text) || text.length() > 32)) {
            throw new ApiException(400, "phone must be 32 characters or fewer");
        }
    }

    private static boolean isPositiveInteger(String id) {
        return ApiValidation.positiveInteger(id);
    }

    private static long generatedId(KeyHolder keyHolder) {
        Number id = keyHolder.getKey();
        if (id == null) {
            throw new IllegalStateException("Supplier insert did not return an id");
        }
        return id.longValue();
    }
}

