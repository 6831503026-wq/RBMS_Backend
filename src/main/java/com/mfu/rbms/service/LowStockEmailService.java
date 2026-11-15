package com.mfu.rbms.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class LowStockEmailService {
    private static final Logger LOGGER = LoggerFactory.getLogger(LowStockEmailService.class);
    private static final String UNCONFIGURED_RECIPIENT = "__NOT_CONFIGURED__";

    private final JavaMailSender mailSender;
    private final String recipient;
    private final String sender;

    public LowStockEmailService(
            JavaMailSender mailSender,
            @Value("${app.po-email:__NOT_CONFIGURED__}") String recipient,
            @Value("${app.mail.from:}") String sender) {
        this.mailSender = mailSender;
        this.recipient = recipient;
        this.sender = sender;
    }

    public void sendAfterCreate(Map<String, Object> item) {
        if (recipient == null || recipient.isBlank() || UNCONFIGURED_RECIPIENT.equals(recipient)) {
            LOGGER.warn("Low-stock email was not sent because app.po-email is not configured");
            return;
        }

        String itemName = String.valueOf(item.get("name"));
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(recipient);
        if (sender != null && !sender.isBlank()) {
            message.setFrom(sender);
        }
        message.setSubject("Low stock alert: " + itemName);
        message.setText("Item " + itemName + " is at or below its minimum stock level.\n"
                + "Current stock: " + item.get("amount") + " " + item.get("unit") + "\n"
                + "Minimum stock: " + item.get("min_stock") + " " + item.get("unit"));

        try {
            mailSender.send(message);
        } catch (MailException ex) {
            LOGGER.error("Failed to send low-stock email for item {}", itemName, ex);
        }
    }
}
