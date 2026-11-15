package com.mfu.rbms;

import com.mfu.rbms.service.NotificationReportScheduler;
import com.mfu.rbms.service.ReportEmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NotificationEmailTests {
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    private static final String PASSWORD = "test-password-123";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NotificationReportScheduler scheduler;

    @Autowired
    private ReportEmailService reportEmailService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private JavaMailSender mailSender;

    @BeforeEach
    void resetNotificationData() {
        jdbc.update("DELETE FROM email_notifications");
        jdbc.update("DELETE FROM storage_logs");
        jdbc.update("DELETE FROM price_history");
        jdbc.update("DELETE FROM item_suppliers");
        jdbc.update("DELETE FROM items");
        jdbc.update("DELETE FROM suppliers");
        jdbc.update("UPDATE email_settings SET recipient_email = ?, frequency = 'daily', " +
                        "send_time = ?, day_of_week = NULL, updated_by = NULL WHERE id = 1",
                "po@example.com", java.sql.Time.valueOf(LocalTime.of(18, 0)));
    }

    @Test
    void sendsDueDailySummaryWithBangkokTimestampAndOneLinePerLowItem() {
        insertItem("Rice", "bags", "2", "3");
        insertItem("Oil", "bottles", "1.5", "2");
        setSettings("daily", LocalTime.of(18, 0), null);

        scheduler.runAt(at(2026, 11, 15, 18, 10));

        var mail = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(mail.capture());
        assertThat(mail.getValue().getSubject()).isEqualTo("Low-stock summary");
        assertThat(mail.getValue().getTo()).containsExactly("po@example.com");
        assertThat(mail.getValue().getText())
                .contains("Rice: 2 bags remaining; minimum: 3 bags.")
                .contains("Oil: 1.5 bottles remaining; minimum: 2 bottles.");

        assertThat(jdbc.queryForObject(
                "SELECT created_at FROM email_notifications WHERE subject = 'Low-stock summary'",
                LocalDateTime.class)).isEqualTo(LocalDateTime.of(2026, 11, 15, 18, 10));
        assertThat(jdbc.queryForObject(
                "SELECT item_name FROM email_notifications WHERE subject = 'Low-stock summary'",
                String.class)).isNull();
    }

    @Test
    void doesNotSendOutsideTheThirtyMinuteDailyWindow() {
        insertItem("Rice", "bags", "2", "3");
        setSettings("daily", LocalTime.of(18, 0), null);

        scheduler.runAt(at(2026, 11, 15, 17, 59));
        scheduler.runAt(at(2026, 11, 15, 18, 31));

        verifyNoInteractions(mailSender);
        assertThat(notificationCount("Low-stock summary")).isZero();
    }

    @Test
    void weeklySummaryRequiresTheConfiguredBangkokWeekday() {
        insertItem("Rice", "bags", "2", "3");
        setSettings("weekly", LocalTime.of(18, 0), 1);

        scheduler.runAt(at(2026, 11, 15, 18, 10));
        scheduler.runAt(at(2026, 11, 16, 18, 10));

        verify(mailSender).send(any(SimpleMailMessage.class));
        assertThat(notificationCount("Low-stock summary")).isEqualTo(1);
    }

    @Test
    void sendsAtMostOneSummaryPerBangkokDay() {
        insertItem("Rice", "bags", "2", "3");
        setSettings("daily", LocalTime.of(18, 0), null);

        scheduler.runAt(at(2026, 11, 15, 18, 5));
        scheduler.runAt(at(2026, 11, 15, 18, 25));

        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
        assertThat(notificationCount("Low-stock summary")).isEqualTo(1);
    }

    @Test
    void failedEmailIsLoggedAndStillPreventsAnotherAttemptThatDay() {
        insertItem("Rice", "bags", "2", "3");
        setSettings("daily", LocalTime.of(18, 0), null);
        doThrow(new MailSendException("test failure")).when(mailSender).send(any(SimpleMailMessage.class));

        scheduler.runAt(at(2026, 11, 15, 18, 5));
        scheduler.runAt(at(2026, 11, 15, 18, 25));

        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
        assertThat(jdbc.queryForObject(
                "SELECT status FROM email_notifications WHERE subject = 'Low-stock summary'",
                String.class)).isEqualTo("failed");
    }

    @Test
    void storesSanitizedTruncatedRootCauseMessageWhenEmailFails() {
        insertItem("Rice", "bags", "2", "3");
        String causeMessage = "SMTP rejected password=secret " + "x".repeat(300);
        doThrow(new MailSendException("mail send failed", new IllegalStateException(causeMessage)))
                .when(mailSender).send(any(SimpleMailMessage.class));

        reportEmailService.sendTestSummary("po@example.com");

        String errorMessage = jdbc.queryForObject(
                "SELECT error_message FROM email_notifications WHERE subject = 'Low-stock summary (test)'",
                String.class);
        assertThat(errorMessage)
                .startsWith("SMTP rejected password=[REDACTED]")
                .doesNotContain("secret")
                .hasSize(250);
    }

    @Test
    void sendsNothingForScheduledSummaryWhenNoItemIsLow() {
        insertItem("Rice", "bags", "5", "3");
        setSettings("daily", LocalTime.of(18, 0), null);

        scheduler.runAt(at(2026, 11, 15, 18, 10));

        verifyNoInteractions(mailSender);
        assertThat(notificationCount("Low-stock summary")).isZero();
    }

    @Test
    void validatesSettingsAndStoresNullDayForDailyFrequency() throws Exception {
        String managerToken = managerToken();
        String[] invalidBodies = {
                "{\"recipient_email\":\"not-an-email\",\"frequency\":\"daily\",\"send_time\":\"18:00\"}",
                "{\"recipient_email\":\"po@example.com\",\"frequency\":\"monthly\",\"send_time\":\"18:00\"}",
                "{\"recipient_email\":\"po@example.com\",\"frequency\":\"daily\",\"send_time\":\"25:00\"}",
                "{\"recipient_email\":\"po@example.com\",\"frequency\":\"weekly\",\"send_time\":\"18:00\"}",
                "{\"recipient_email\":\"po@example.com\",\"frequency\":\"weekly\",\"send_time\":\"18:00\","
                        + "\"day_of_week\":8}"
        };
        for (String body : invalidBodies) {
            mockMvc.perform(put("/api/settings/email")
                            .header("Authorization", bearer(managerToken))
                            .contentType("application/json")
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").isNotEmpty());
        }
        mockMvc.perform(put("/api/settings/email")
                        .header("Authorization", bearer(managerToken))
                        .contentType("application/json")
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid request body"));

        mockMvc.perform(put("/api/settings/email")
                        .header("Authorization", bearer(managerToken))
                        .contentType("application/json")
                        .content("{\"recipient_email\":\"buyer@example.com\",\"frequency\":\"daily\","
                                + "\"send_time\":\"07:45\",\"day_of_week\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipient_email").value("buyer@example.com"))
                .andExpect(jsonPath("$.frequency").value("daily"))
                .andExpect(jsonPath("$.send_time").value("07:45"))
                .andExpect(jsonPath("$.day_of_week").value(nullValue()))
                .andExpect(jsonPath("$.updated_by").isNumber());

        assertThat(jdbc.queryForObject(
                "SELECT day_of_week FROM email_settings WHERE id = 1", Integer.class)).isNull();
    }

    @Test
    void allThreeNotificationRoutesRequireManagerRoleAndTestRouteUsesMockSender() throws Exception {
        String managerName = "manager-" + UUID.randomUUID();
        String staffName = "staff-" + UUID.randomUUID();
        long managerId = createUser(managerName, "manager");
        createUser(staffName, "kitchen_staff");
        String managerToken = login(managerName);
        String staffToken = login(staffName);
        String updateBody = "{\"recipient_email\":\"buyer@example.com\",\"frequency\":\"daily\","
                + "\"send_time\":\"18:00\"}";

        mockMvc.perform(get("/api/settings/email"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/settings/email").header("Authorization", bearer(staffToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/settings/email").header("Authorization", bearer(managerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipient_email").exists())
                .andExpect(jsonPath("$.send_time").exists());

        mockMvc.perform(put("/api/settings/email").contentType("application/json").content(updateBody))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/settings/email")
                        .header("Authorization", bearer(staffToken))
                        .contentType("application/json").content(updateBody))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/settings/email")
                        .header("Authorization", bearer(managerToken))
                        .contentType("application/json").content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated_by").value(managerId));

        mockMvc.perform(post("/api/notifications/test"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/notifications/test").header("Authorization", bearer(staffToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/notifications/test").header("Authorization", bearer(managerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(true))
                .andExpect(jsonPath("$.error").value(""));

        var mail = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(mail.capture());
        assertThat(mail.getValue().getSubject()).isEqualTo("Low-stock summary (test)");
        assertThat(mail.getValue().getText()).contains("No low-stock items");
    }

    private void insertItem(String name, String unit, String amount, String minimum) {
        jdbc.update("INSERT INTO items (name, unit, amount, min_stock) VALUES (?, ?, ?, ?)",
                name, unit, new java.math.BigDecimal(amount), new java.math.BigDecimal(minimum));
    }

    private void setSettings(String frequency, LocalTime sendTime, Integer dayOfWeek) {
        jdbc.update("UPDATE email_settings SET frequency = ?, send_time = ?, day_of_week = ? WHERE id = 1",
                frequency, java.sql.Time.valueOf(sendTime), dayOfWeek);
    }

    private int notificationCount(String subject) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM email_notifications WHERE subject = ?", Integer.class, subject);
    }

    private static ZonedDateTime at(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, BANGKOK);
    }

    private String managerToken() throws Exception {
        String username = "manager-" + UUID.randomUUID();
        createUser(username, "manager");
        return login(username);
    }

    private long createUser(String username, String role) {
        jdbc.update("INSERT INTO users (username, password_hash, display_name, role) VALUES (?, ?, ?, ?)",
                username, passwordEncoder.encode(PASSWORD), username, role);
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
    }

    private String login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        Matcher matcher = Pattern.compile("\"token\":\"([^\"]+)\"")
                .matcher(result.getResponse().getContentAsString());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
