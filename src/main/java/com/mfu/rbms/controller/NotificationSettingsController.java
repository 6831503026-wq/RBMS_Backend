package com.mfu.rbms.controller;

import com.mfu.rbms.security.ApiIdentity;
import com.mfu.rbms.service.NotificationSettingsService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/settings/email")
public class NotificationSettingsController {
    private final NotificationSettingsService settingsService;

    public NotificationSettingsController(NotificationSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public Map<String, Object> getSettings() {
        return settingsService.getSettings();
    }

    @PutMapping
    public Map<String, Object> updateSettings(
            @RequestBody Map<String, Object> request,
            @AuthenticationPrincipal ApiIdentity actor) {
        return settingsService.updateSettings(request, actor.id());
    }
}