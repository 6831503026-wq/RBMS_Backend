package com.mfu.rbms.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DataSeeder implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final boolean enabled;
    private final String managerUsername;
    private final String managerPassword;
    private final String staffUsername;
    private final String staffPassword;

    public DataSeeder(
            JdbcTemplate jdbc,
            PasswordEncoder passwordEncoder,
            @Value("${app.seed.users.enabled:false}") boolean enabled,
            @Value("${app.seed.users.manager.username:demo_po}") String managerUsername,
            @Value("${app.seed.users.manager.password:}") String managerPassword,
            @Value("${app.seed.users.staff.username:demo-kitchen}") String staffUsername,
            @Value("${app.seed.users.staff.password:}") String staffPassword) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.enabled = enabled;
        this.managerUsername = managerUsername == null ? "demo_po" : managerUsername.trim();
        this.managerPassword = managerPassword == null ? "" : managerPassword;
        this.staffUsername = staffUsername == null ? "demo-kitchen" : staffUsername.trim();
        this.staffPassword = staffPassword == null ? "" : staffPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }

        upsertUser(managerUsername, managerPassword, "Manager", "manager");
        upsertUser(staffUsername, staffPassword, "Kitchen Staff", "kitchen_staff");
    }

    private void upsertUser(String username, String password, String displayName, String role) {
        if (username == null || username.isBlank()) {
            throw new IllegalStateException("Seed username cannot be blank");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("Seed password for " + username + " cannot be blank");
        }

        String trimmedUsername = username.trim();
        List<Long> rows = jdbc.queryForList(
                "SELECT id FROM users WHERE username = ?",
                Long.class,
                trimmedUsername);

        String passwordHash = passwordEncoder.encode(password);

        if (rows.isEmpty()) {
            jdbc.update(
                    "INSERT INTO users (username, password_hash, display_name, role, is_active) VALUES (?, ?, ?, ?, TRUE)",
                    trimmedUsername,
                    passwordHash,
                    displayName,
                    role);
            return;
        }

        jdbc.update(
                "UPDATE users SET password_hash = ?, display_name = ?, role = ?, is_active = TRUE WHERE username = ?",
                passwordHash,
                displayName,
                role,
                trimmedUsername);
    }
}
