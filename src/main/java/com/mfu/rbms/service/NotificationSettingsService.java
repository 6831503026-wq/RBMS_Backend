package com.mfu.rbms.service;

import com.mfu.rbms.api.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.sql.Time;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class NotificationSettingsService implements ApplicationRunner {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final JdbcTemplate jdbc;
    private final String defaultRecipient;

    public NotificationSettingsService(
            JdbcTemplate jdbc,
            @Value("${PO_EMAIL:po@example.com}") String defaultRecipient) {
        this.jdbc = jdbc;
        this.defaultRecipient = defaultRecipient == null || defaultRecipient.isBlank()
                ? "po@example.com" : defaultRecipient.trim();
    }

    @Override
    public void run(ApplicationArguments arguments) {
        List<Long> rows = jdbc.queryForList(
                "SELECT id FROM email_settings WHERE id = 1", Long.class);
        if (rows.isEmpty()) {
            jdbc.update("INSERT INTO email_settings " +
                            "(id, recipient_email, frequency, send_time, day_of_week, updated_by, updated_at) " +
                            "VALUES (1, ?, 'daily', ?, NULL, NULL, CURRENT_TIMESTAMP)",
                    defaultRecipient, Time.valueOf(LocalTime.of(18, 0)));
        }
    }

    public Map<String, Object> getSettings() {
        Settings settings = currentSettings();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", 1);
        response.put("recipient_email", settings.recipientEmail());
        response.put("frequency", settings.frequency());
        response.put("send_time", settings.sendTime().format(TIME_FORMAT));
        response.put("day_of_week", settings.dayOfWeek());
        response.put("updated_by", settings.updatedBy());
        response.put("updated_at", settings.updatedAt());
        return response;
    }

    public Settings currentSettings() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT recipient_email, frequency, send_time, day_of_week, updated_by, updated_at " +
                        "FROM email_settings WHERE id = 1");
        if (rows.isEmpty()) {
            throw new ApiException(500, "Email settings are not initialized");
        }
        Map<String, Object> row = rows.get(0);
        Object rawDayOfWeek = row.get("day_of_week");
        Integer dayOfWeek = rawDayOfWeek instanceof Number number ? number.intValue() : null;
        return new Settings(
                String.valueOf(row.get("recipient_email")),
                String.valueOf(row.get("frequency")),
                toLocalTime(row.get("send_time")),
                dayOfWeek,
                row.get("updated_by"),
                row.get("updated_at"));
    }

    public Map<String, Object> updateSettings(Map<String, Object> request, long actorId) {
        String recipient = requiredEmail(request.get("recipient_email"));
        String frequency = requiredFrequency(request.get("frequency"));
        LocalTime sendTime = requiredTime(request.get("send_time"));
        Integer dayOfWeek = "weekly".equals(frequency) ? requiredDayOfWeek(request.get("day_of_week")) : null;

        int changed = jdbc.update(
                "UPDATE email_settings SET recipient_email = ?, frequency = ?, send_time = ?, " +
                        "day_of_week = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP WHERE id = 1",
                recipient, frequency, Time.valueOf(sendTime), dayOfWeek, actorId);
        if (changed == 0) {
            throw new ApiException(500, "Email settings are not initialized");
        }
        return getSettings();
    }

    private static String requiredEmail(Object value) {
        if (!(value instanceof String text)) {
            throw new ApiException(400, "recipient_email must be a valid email address");
        }
        String email = text.trim();
        if (email.isEmpty() || email.length() > 254) {
            throw new ApiException(400, "recipient_email must be a valid email address");
        }
        try {
            InternetAddress[] addresses = InternetAddress.parse(email, true);
            if (addresses.length != 1 || !email.equals(addresses[0].getAddress())) {
                throw new ApiException(400, "recipient_email must be a valid email address");
            }
            addresses[0].validate();
        } catch (AddressException exception) {
            throw new ApiException(400, "recipient_email must be a valid email address");
        }
        return email;
    }

    private static String requiredFrequency(Object value) {
        if (!(value instanceof String frequency)
                || !("daily".equals(frequency) || "weekly".equals(frequency))) {
            throw new ApiException(400, "frequency must be daily or weekly");
        }
        return frequency;
    }

    private static LocalTime requiredTime(Object value) {
        if (!(value instanceof String text) || !text.matches("(?:[01]\\d|2[0-3]):[0-5]\\d")) {
            throw new ApiException(400, "send_time must use HH:mm format");
        }
        return LocalTime.parse(text, TIME_FORMAT);
    }

    private static Integer requiredDayOfWeek(Object value) {
        if (!(value instanceof Number number)) {
            throw new ApiException(400, "day_of_week must be an integer from 1 to 7 for weekly frequency");
        }
        double numericValue = number.doubleValue();
        if (!Double.isFinite(numericValue) || numericValue != Math.rint(numericValue)
                || numericValue < 1 || numericValue > 7) {
            throw new ApiException(400, "day_of_week must be an integer from 1 to 7 for weekly frequency");
        }
        return (int) numericValue;
    }

    private static LocalTime toLocalTime(Object value) {
        if (value instanceof Time time) {
            return time.toLocalTime();
        }
        if (value instanceof LocalTime time) {
            return time;
        }
        return LocalTime.parse(String.valueOf(value));
    }

    public record Settings(
            String recipientEmail,
            String frequency,
            LocalTime sendTime,
            Integer dayOfWeek,
            Object updatedBy,
            Object updatedAt) {
    }
}