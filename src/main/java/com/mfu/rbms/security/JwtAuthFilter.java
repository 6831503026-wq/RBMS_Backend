package com.mfu.rbms.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.jsonwebtoken.Claims;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String AUTH_ERROR_ATTRIBUTE = "api.auth.error";

    private final JwtService jwtService;
    private final JdbcTemplate jdbcTemplate;

    public JwtAuthFilter(JwtService jwtService, JdbcTemplate jdbcTemplate) {
        this.jwtService = jwtService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = jwtService.extractAllClaims(header.substring(7));
                Object claimId = claims.get("id");
                if (!(claimId instanceof Number id)) {
                    request.setAttribute(AUTH_ERROR_ATTRIBUTE, "invalid");
                } else {
                    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                            "SELECT id, username, display_name, role, is_active FROM users WHERE id = ? LIMIT 1",
                            id.longValue());
                    if (rows.isEmpty() || !isActive(rows.get(0).get("is_active"))) {
                        request.setAttribute(AUTH_ERROR_ATTRIBUTE, "invalid");
                    } else {
                        Map<String, Object> row = rows.get(0);
                        ApiIdentity identity = new ApiIdentity(
                                ((Number) row.get("id")).longValue(),
                                String.valueOf(row.get("username")),
                                String.valueOf(row.get("display_name")),
                                String.valueOf(row.get("role")));
                        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + identity.role().toUpperCase()));
                        var authentication = new UsernamePasswordAuthenticationToken(identity, null, authorities);
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    }
                }
            } catch (JwtService.JwtConfigurationException ex) {
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, "configuration");
            } catch (Exception ex) {
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, "invalid");
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isActive(Object value) {
        return value instanceof Boolean bool ? bool
                : value instanceof Number number && number.intValue() != 0;
    }
}
