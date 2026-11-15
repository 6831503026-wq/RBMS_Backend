package com.mfu.rbms.controller;

import com.mfu.rbms.security.ApiIdentity;
import com.mfu.rbms.service.AuthService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    public Map<String, Object> getCurrentUser(@AuthenticationPrincipal ApiIdentity identity) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", identity.id());
        result.put("display_name", identity.displayName());
        result.put("role", identity.role());
        return result;
    }
}
