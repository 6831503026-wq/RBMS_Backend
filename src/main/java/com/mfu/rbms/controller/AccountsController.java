package com.mfu.rbms.controller;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mfu.rbms.api.ApiException;
import com.mfu.rbms.api.ApiRows;

@RestController
@RequestMapping("/api/accounts")
public class AccountsController {
    private static final String PUBLIC_FIELDS = "id, username, display_name, role, is_active, created_at";
    private static final Set<String> ROLES = Set.of("manager", "kitchen_staff");

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactions;

    public AccountsController(
            JdbcTemplate jdbc,
            PasswordEncoder passwordEncoder,
            TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.transactions = transactions;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        try {
            return ApiRows.normalize(jdbc.queryForList(
                    "SELECT " + PUBLIC_FIELDS + " FROM users ORDER BY display_name, username"));
        } catch (RuntimeException ex) {
            throw new ApiException(500, "Failed to load accounts");
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        String name = requiredTrimmedName(body.get("name"));
        String username = requiredTrimmedUsername(body.get("username"));
        String role = requiredRole(body.get("role"));
        String password = requiredPassword(body.get("password"), "Password must be at least 8 characters");
        try {
            var result = jdbc.update(
                    "INSERT INTO users (username, password_hash, display_name, role) VALUES (?, ?, ?, ?)",
                    username, passwordEncoder.encode(password), name, role);
            if (result != 1) {
                throw new ApiException(500, "Failed to create account");
            }
            Long id = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
            return findPublicUser(id);
        } catch (DuplicateKeyException ex) {
            throw new ApiException(409, "That username is already in use");
        } catch (ApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ApiException(500, "Failed to create account");
        }
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(
            @PathVariable String id,
            @RequestBody Map<String, Object> body,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            com.mfu.rbms.security.ApiIdentity actor) {
        long userId = parseAccountId(id);
        String name = requiredTrimmedName(body.get("name"));
        String newRole = requiredRole(body.get("role"));
        transactions.executeWithoutResult(status -> {
            List<Map<String, Object>> users = jdbc.queryForList(
                    "SELECT id, role, is_active FROM users WHERE id = ? FOR UPDATE", userId);
            if (users.isEmpty()) {
                throw new ApiException(404, "Account not found");
            }
            Map<String, Object> user = users.get(0);
            String currentRole = String.valueOf(user.get("role"));
            boolean active = booleanValue(user.get("is_active"));
            if ("manager".equals(currentRole) && "kitchen_staff".equals(newRole) && userId == actor.id()) {
                throw new ApiException(409, "You cannot change your own role to Staff");
            }
            if ("manager".equals(currentRole) && "kitchen_staff".equals(newRole) && active
                    && activeManagerCount() <= 1) {
                throw new ApiException(409, "At least one active manager is required");
            }
            jdbc.update("UPDATE users SET display_name = ?, role = ? WHERE id = ?", name, newRole, userId);
        });
        return findPublicUser(userId);
    }

    @PutMapping("/{id}/password")
    public Map<String, Object> updatePassword(
            @PathVariable String id,
            @RequestBody Map<String, Object> body,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            com.mfu.rbms.security.ApiIdentity actor) {
        long userId = parseAccountId(id);
        String currentPassword = requiredPassword(body.get("current_password"), "Current password is required");
        String newPassword = requiredPassword(body.get("password"), "Password must be at least 8 characters");
        try {
            List<Map<String, Object>> users = jdbc.queryForList(
                    "SELECT id, role, password_hash FROM users WHERE id = ? LIMIT 1", userId);
            if (users.isEmpty()) {
                throw new ApiException(404, "Account not found");
            }
            Map<String, Object> user = users.get(0);
            String role = String.valueOf(user.get("role"));
            if ("manager".equals(role) && ((Number) user.get("id")).longValue() != actor.id()) {
                throw new ApiException(403, "You can only change your own manager password");
            }
            if (!"manager".equals(role) && !"kitchen_staff".equals(role)) {
                throw new ApiException(403, "This account cannot change its password here");
            }
            if (!passwordEncoder.matches(currentPassword, String.valueOf(user.get("password_hash")))) {
                throw new ApiException(401, "Current password is incorrect");
            }
            int changed = jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?",
                    passwordEncoder.encode(newPassword), userId);
            if (changed == 0) {
                throw new ApiException(404, "Account not found");
            }
            return Map.of("message", "Password reset successfully");
        } catch (ApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ApiException(500, "Failed to reset password");
        }
    }

    @PutMapping("/{id}/active")
    public Map<String, Object> updateActive(
            @PathVariable String id,
            @RequestBody Map<String, Object> body,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            com.mfu.rbms.security.ApiIdentity actor) {
        long userId = parseAccountId(id);
        Object rawActive = body.get("is_active");
        if (!(rawActive instanceof Boolean active)) {
            throw new ApiException(400, "is_active must be a boolean");
        }
        if (userId == actor.id() && !active) {
            throw new ApiException(409, "You cannot deactivate yourself");
        }
        transactions.executeWithoutResult(status -> {
            List<Map<String, Object>> users = jdbc.queryForList(
                    "SELECT id, role, is_active FROM users WHERE id = ? FOR UPDATE", userId);
            if (users.isEmpty()) {
                throw new ApiException(404, "Account not found");
            }
            Map<String, Object> user = users.get(0);
            if (!active && booleanValue(user.get("is_active"))
                    && "manager".equals(String.valueOf(user.get("role"))) && activeManagerCount() <= 1) {
                throw new ApiException(409, "At least one active manager is required");
            }
            jdbc.update("UPDATE users SET is_active = ? WHERE id = ?", active, userId);
        });
        return findPublicUser(userId);
    }

    private Map<String, Object> findPublicUser(long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT " + PUBLIC_FIELDS + " FROM users WHERE id = ?", userId);
        if (rows.isEmpty()) {
            throw new ApiException(404, "Account not found");
        }
        return ApiRows.normalize(rows.get(0));
    }

    private int activeManagerCount() {
        List<Long> managers = jdbc.queryForList(
                "SELECT id FROM users WHERE role = ? AND is_active = TRUE FOR UPDATE", Long.class, "manager");
        return managers.size();
    }

    private static long parseAccountId(String value) {
        if (com.mfu.rbms.api.ApiValidation.positiveInteger(value)) {
            return (long) com.mfu.rbms.api.ApiValidation.number(value);
        }
        throw new ApiException(400, "Invalid account id");
    }

    private static String requiredTrimmedName(Object value) {
        if (!(value instanceof String text) || text.trim().isEmpty() || text.trim().length() > 150) {
            throw new ApiException(400, "Name is required and must be 150 characters or fewer");
        }
        return text.trim();
    }

    private static String requiredTrimmedUsername(Object value) {
        if (!(value instanceof String text) || text.trim().isEmpty() || text.trim().length() > 150) {
            throw new ApiException(400, "Username is required and must be 150 characters or fewer");
        }
        return text.trim();
    }

    private static String requiredRole(Object value) {
        if (!(value instanceof String role) || !ROLES.contains(role)) {
            throw new ApiException(400, "Role must be manager or kitchen_staff");
        }
        return role;
    }

    private static String requiredPassword(Object value, String error) {
        if (!(value instanceof String password) || password.length() < 8) {
            throw new ApiException(400, error);
        }
        return password;
    }

    private static boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool
                : value instanceof Number number && number.intValue() != 0;
    }
}
