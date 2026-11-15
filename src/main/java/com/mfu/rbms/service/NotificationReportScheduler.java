package com.mfu.rbms.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@Component
public class NotificationReportScheduler {
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

    private final JdbcTemplate jdbc;
    private final NotificationSettingsService settingsService;
    private final ReportEmailService reportEmailService;

    public NotificationReportScheduler(
            JdbcTemplate jdbc,
            NotificationSettingsService settingsService,
            ReportEmailService reportEmailService) {
        this.jdbc = jdbc;
        this.settingsService = settingsService;
        this.reportEmailService = reportEmailService;
    }

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Bangkok")
    public void sendDueSummary() {
        runAt(ZonedDateTime.now(BANGKOK));
    }

    public void runAt(ZonedDateTime currentTime) {
        ZonedDateTime bangkokTime = currentTime.withZoneSameInstant(BANGKOK);
        LocalDateTime localNow = bangkokTime.toLocalDateTime();
        NotificationSettingsService.Settings settings = settingsService.currentSettings();
        if (!isDue(settings, localNow)) {
            return;
        }

        LocalDate today = bangkokTime.toLocalDate();
        if (hasAttemptToday(today)) {
            return;
        }
        reportEmailService.sendScheduledSummary(settings.recipientEmail(), localNow);
    }

    private boolean hasAttemptToday(LocalDate today) {
        LocalDateTime start = today.atStartOfDay();
        LocalDateTime end = today.plusDays(1).atStartOfDay();
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM email_notifications " +
                        "WHERE subject = 'Low-stock summary' AND created_at >= ? AND created_at < ?",
                Integer.class, start, end);
        return count != null && count > 0;
    }

    private static boolean isDue(
            NotificationSettingsService.Settings settings,
            LocalDateTime localNow) {
        if ("weekly".equals(settings.frequency())
                && (settings.dayOfWeek() == null
                || settings.dayOfWeek() != localNow.getDayOfWeek().getValue())) {
            return false;
        }
        LocalDateTime scheduledTime = localNow.toLocalDate().atTime(settings.sendTime());
        return !localNow.isBefore(scheduledTime)
                && !localNow.isAfter(scheduledTime.plusMinutes(30));
    }
}