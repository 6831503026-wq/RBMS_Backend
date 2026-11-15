package com.mfu.rbms.controller;

import com.mfu.rbms.service.NotificationSettingsService;
import com.mfu.rbms.service.ReportEmailService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications/test")
public class NotificationTestController {
    private final NotificationSettingsService settingsService;
    private final ReportEmailService reportEmailService;

    public NotificationTestController(
            NotificationSettingsService settingsService,
            ReportEmailService reportEmailService) {
        this.settingsService = settingsService;
        this.reportEmailService = reportEmailService;
    }

    @PostMapping
    public Map<String, Object> sendTestEmail() {
        ReportEmailService.SendResult result =
                reportEmailService.sendTestSummary(settingsService.currentSettings().recipientEmail());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sent", result.sent());
        response.put("error", result.error());
        return response;
    }
}