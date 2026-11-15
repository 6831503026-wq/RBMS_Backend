package com.mfu.rbms.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import jakarta.servlet.http.HttpServletResponse;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public UserDetailsService noFormLoginUsers() {
        return username -> {
            throw new UsernameNotFoundException("Username/password authentication is not configured");
        };
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthFilter jwtAuthFilter) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) -> {
                    Object reason = request.getAttribute(JwtAuthFilter.AUTH_ERROR_ATTRIBUTE);
                    if ("configuration".equals(reason)) {
                        writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                                "Authentication is not configured");
                    } else if ("invalid".equals(reason)) {
                        writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                                "Invalid or expired token");
                    } else {
                        writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                                "Authentication required");
                    }
                })
                .accessDeniedHandler((request, response, exception) ->
                    writeError(response, HttpServletResponse.SC_FORBIDDEN,
                            "Insufficient permissions")))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/login").permitAll()
                .requestMatchers("/api/accounts/**", "/api/reports/**").hasRole("MANAGER")
                .requestMatchers(HttpMethod.GET, "/api/items/*/price-history").hasRole("MANAGER")
                .requestMatchers(HttpMethod.POST, "/api/items/**", "/api/suppliers/**",
                        "/api/item-suppliers/**").hasRole("MANAGER")
                .requestMatchers(HttpMethod.PUT, "/api/items/**", "/api/suppliers/**",
                        "/api/item-suppliers/**").hasRole("MANAGER")
                .requestMatchers(HttpMethod.DELETE, "/api/items/**", "/api/suppliers/**",
                        "/api/item-suppliers/**").hasRole("MANAGER")
                .anyRequest().authenticated())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private static void writeError(
            jakarta.servlet.http.HttpServletResponse response,
            int status,
            String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
