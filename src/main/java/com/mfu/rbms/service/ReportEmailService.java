package com.mfu.rbms.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class ReportEmailService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReportEmailService.class);
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    private static final Pattern PASSWORD_PATTERN = Pattern.compile(
            "(?i)(password|passwd|pwd)(\\s*[=:]\\s*|\\s+)(\"[^\"]*\"|'[^']*'|\\S+)");
    private static final int MAX_ERROR_MESSAGE_LENGTH = 250;

    private final JdbcTemplate jdbc;
    private final JavaMailSender mailSender;
    private final String fromAddress;

    public ReportEmailService(
            JdbcTemplate jdbc,
            JavaMailSender mailSender,
            @Value("${app.mail.from:}") String fromAddress) {
        this.jdbc = jdbc;
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    public void sendScheduledSummary(String recipient, LocalDateTime attemptTime) {
        List<String> lines = lowStockLines();
        if (!lines.isEmpty()) {
            deliver(recipient, "Low-stock summary", String.join("\n", lines), attemptTime);
        }
    }

    public SendResult sendTestSummary(String recipient) {
        List<String> lines = lowStockLines();
        String message = lines.isEmpty()
                ? "No low-stock items are currently at or below minimum stock."
                : String.join("\n", lines);
        return deliver(recipient, "Low-stock summary (test)", message, LocalDateTime.now(BANGKOK));
    }

    private List<String> lowStockLines() {
        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT name, amount, unit, min_stock FROM items " +
                        "WHERE amount <= min_stock ORDER BY name, id");
        return items.stream().map(item -> {
            String name = String.valueOf(item.get("name"));
            String amount = decimal(item.get("amount"));
            String unit = String.valueOf(item.get("unit"));
            String minimum = decimal(item.get("min_stock"));
            return name + ": " + amount + " " + unit + " remaining; minimum: "
                    + minimum + " " + unit + ".";
        }).toList();
    }

    private SendResult deliver(
            String recipient,
            String subject,
            String message,
            LocalDateTime createdAt) {
        String status = "failed";
        String errorMessage = null;
        try {
            SimpleMailMessage mail = new SimpleMailMessage();
            if (fromAddress != null && !fromAddress.isBlank()) {
                mail.setFrom(fromAddress);
            }
            mail.setTo(recipient);
            mail.setSubject(subject);
            mail.setText(message);
            mailSender.send(mail);
            status = "sent";
        } catch (Exception exception) {
            errorMessage = errorMessage(exception);
            LOGGER.error("Failed to send notification email: {}", errorMessage);
        }

        try {
            jdbc.update(
                    "INSERT INTO email_notifications " +
                            "(recipient, subject, item_name, message, status, error_message, created_at) " +
                            "VALUES (?, ?, NULL, ?, ?, ?, ?)",
                    recipient, subject, message, status, errorMessage, createdAt);
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to record notification email attempt", exception);
        }
        return new SendResult("sent".equals(status), "sent".equals(status) ? "" : "Email delivery failed");
    }

    private static String errorMessage(Throwable exception) {
        Throwable rootCause = exception;
        while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            rootCause = rootCause.getCause();
        }

        String message = rootCause.getMessage();
        if (message == null || message.isBlank()) {
            message = rootCause.getClass().getSimpleName();
        }
        String sanitized = PASSWORD_PATTERN.matcher(message).replaceAll("$1$2[REDACTED]");
        return sanitized.substring(0, Math.min(sanitized.length(), MAX_ERROR_MESSAGE_LENGTH));
    }

    private static String decimal(Object value) {
        return new BigDecimal(String.valueOf(value)).stripTrailingZeros().toPlainString();
    }

    public record SendResult(boolean sent, String error) {
    }
}