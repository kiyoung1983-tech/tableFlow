package com.example.fullstack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.fullstack.reservation.ReservationRetentionService;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@Import({
    TestcontainersConfiguration.class,
    ReservationRetentionIntegrationTests.FixedClockConfiguration.class
})
@SpringBootTest(properties = {
    "app.reservation.retention.enabled=true",
    "app.reservation.retention.days=30",
    "app.reservation.retention.batch-size=10"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReservationRetentionIntegrationTests {
    private static final Instant FIXED_NOW = Instant.parse("2026-08-17T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReservationRetentionService retentionService;

    @Test
    void expiredTerminalReservationErasesPersonalDataAndInvalidatesManageToken()
            throws Exception {
        var branchId = createBookableBranch();
        var creation = mockMvc.perform(post("/api/public/reservations")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "branchId":"%s",
                                  "startsAt":"2026-08-18T02:00:00Z",
                                  "partySize":2,
                                  "guestName":"보존 고객",
                                  "guestPhone":"010-9876-5432",
                                  "privacyAgreement":{"agreed":true,"policyVersion":"2026-08-16"}
                                }
                                """.formatted(branchId)))
                .andExpect(status().isCreated())
                .andReturn();
        var body = creation.getResponse().getContentAsString();
        var code = (String) JsonPath.read(body, "$.reservation.reservationCode");
        var token = (String) JsonPath.read(body, "$.manageToken");
        var id = jdbc.queryForObject(
                "select id from reservations where reservation_code = ?", UUID.class, code);
        var originalNameCiphertext = jdbc.queryForObject(
                "select guest_name_ciphertext from reservations where id = ?", String.class, id);

        mockMvc.perform(post("/api/public/reservations/{code}/cancellation", code)
                        .header("X-Reservation-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isOk());
        jdbc.update(
                "update reservations set starts_at = ?, ends_at = ?, occupied_until = ? where id = ?",
                OffsetDateTime.parse("2026-06-01T02:00:00Z"),
                OffsetDateTime.parse("2026-06-01T03:30:00Z"),
                OffsetDateTime.parse("2026-06-01T03:45:00Z"),
                id);

        assertEquals(1, retentionService.eraseExpiredPersonalData());

        var erased = jdbc.queryForMap(
                "select guest_name_ciphertext, phone_last_four, contact_phone_hash, "
                        + "manage_token_hash, request_fingerprint, personal_data_erased_at "
                        + "from reservations where id = ?",
                id);
        assertNotEquals(originalNameCiphertext, erased.get("guest_name_ciphertext"));
        assertEquals("0000", erased.get("phone_last_four"));
        assertEquals(64, erased.get("contact_phone_hash").toString().length());
        assertEquals(64, erased.get("manage_token_hash").toString().length());
        assertEquals(64, erased.get("request_fingerprint").toString().length());
        assertNotNull(erased.get("personal_data_erased_at"));

        mockMvc.perform(get("/api/public/reservations/{code}", code)
                        .header("X-Reservation-Token", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
        mockMvc.perform(get("/api/admin/reservations/{id}", id)
                        .with(httpBasic("developer", "change-me-locally")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.guestName").value("[파기됨]"))
                .andExpect(jsonPath("$.guestPhoneLastFour").value("0000"));
    }

    private UUID createBookableBranch() {
        var restaurantId = UUID.randomUUID();
        var branchId = UUID.randomUUID();
        var now = OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);
        jdbc.update(
                "insert into restaurants (id, name, status, created_at, updated_at) "
                        + "values (?, ?, 'ACTIVE', ?, ?)",
                restaurantId,
                "Retention-" + restaurantId,
                now,
                now);
        jdbc.update(
                "insert into branches (id, restaurant_id, name, address, created_at, updated_at) "
                        + "values (?, ?, ?, ?, ?, ?)",
                branchId,
                restaurantId,
                "보존점",
                "서울시 종로구",
                now,
                now);
        jdbc.update(
                "insert into dining_tables "
                        + "(id, branch_id, name, capacity, enabled, created_at, updated_at) "
                        + "values (?, ?, 'T1', 2, true, ?, ?)",
                UUID.randomUUID(),
                branchId,
                now,
                now);
        jdbc.update(
                "insert into business_hours "
                        + "(id, branch_id, day_of_week, opens_at, closes_at, created_at, updated_at) "
                        + "values (?, ?, 2, '11:00:00', '16:00:00', ?, ?)",
                UUID.randomUUID(),
                branchId,
                now,
                now);
        return branchId;
    }

    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }
}
