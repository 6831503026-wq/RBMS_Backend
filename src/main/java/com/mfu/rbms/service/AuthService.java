package com.mfu.rbms.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.mfu.rbms.api.ApiException;
import com.mfu.rbms.security.JwtService;

@Service
public class AuthService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthService.class);

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public Map<String, Object> login(Map<String, Object> request) {
        Object rawUsername = request.get("username");
        Object rawPassword = request.get("password");
        if (!(rawUsername instanceof String username) || !(rawPassword instanceof String password)) {
            throw new ApiException(400, "Username and password are required");
        }

        try {
            var rows = jdbcTemplate.queryForList(
                    "SELECT id, username, password_hash, display_name, role FROM users " +
                            "WHERE username = ? AND is_active = TRUE",
                    username);
            if (rows.isEmpty() || !passwordEncoder.matches(password, String.valueOf(rows.get(0).get("password_hash")))) {
                throw new ApiException(401, "Invalid username or password");
            }

            Map<String, Object> dbUser = rows.get(0);
            long id = ((Number) dbUser.get("id")).longValue();
            String displayName = String.valueOf(dbUser.get("display_name"));
            String role = String.valueOf(dbUser.get("role"));
            Map<String, Object> publicUser = new LinkedHashMap<>();
            publicUser.put("id", id);
            publicUser.put("display_name", displayName);
            publicUser.put("role", role);
            String token = jwtService.generateToken(id, displayName, role);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("token", token);
            result.put("user", publicUser);
            return result;
        } catch (ApiException ex) {
            throw ex;
        } catch (JwtService.JwtConfigurationException ex) {
            throw new ApiException(500, "Authentication is not configured");
        } catch (RuntimeException ex) {
            LOGGER.error("Failed to log in", ex);
            throw new ApiException(500, "Failed to log in");
        }
    }
}
