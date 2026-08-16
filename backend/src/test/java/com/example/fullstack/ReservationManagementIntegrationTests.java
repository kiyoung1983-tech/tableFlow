package com.example.fullstack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.springframework.test.web.servlet.MvcResult;

@Import({
    TestcontainersConfiguration.class,
    ReservationManagementIntegrationTests.FixedClockConfiguration.class
})
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReservationManagementIntegrationTests {
    private static final Instant FIXED_NOW = Instant.parse("2026-08-17T00:00:00Z");
    private static final String TUESDAY_START = "2026-08-18T02:00:00Z";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void tokenLookupReturnsPublicReservationAndDoesNotRevealWhichCredentialFailed()
            throws Exception {
        var branchId = createBookableBranch(2, (short) 2);
        var created = createReservation(branchId, TUESDAY_START, "010-1010-2020");

        mockMvc.perform(get("/api/public/reservations/{code}", created.code())
                        .header("X-Reservation-Token", created.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationCode").value(created.code()))
                .andExpect(jsonPath("$.timezone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.version").value(0));

        var wrongToken = mockMvc.perform(get(
                                "/api/public/reservations/{code}", created.code())
                        .header("X-Reservation-Token", "wrong-token"))
                .andReturn();
        var missingCode = mockMvc.perform(get(
                                "/api/public/reservations/{code}", "AAAAAAAAAA")
                        .header("X-Reservation-Token", created.token()))
                .andReturn();

        assertEquals(404, wrongToken.getResponse().getStatus());
        assertEquals(404, missingCode.getResponse().getStatus());
        assertEquals(
                (String) JsonPath.read(wrongToken.getResponse().getContentAsString(), "$.code"),
                (String) JsonPath.read(missingCode.getResponse().getContentAsString(), "$.code"));
        assertEquals(
                (String) JsonPath.read(wrongToken.getResponse().getContentAsString(), "$.detail"),
                (String) JsonPath.read(missingCode.getResponse().getContentAsString(), "$.detail"));
    }

    @Test
    void changeReallocatesAtomicallyAndCancellationReleasesCapacity() throws Exception {
        var branchId = createBookableBranch(2, (short) 2);
        var created = createReservation(branchId, TUESDAY_START, "010-3030-4040");
        var originalTableId = jdbc.queryForObject(
                "select dining_table_id from reservations where reservation_code = ?",
                UUID.class,
                created.code());
        var now = OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);
        jdbc.update(
                "insert into booking_blocks "
                        + "(id, branch_id, dining_table_id, starts_at, ends_at, reason, created_at, updated_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                branchId,
                originalTableId,
                OffsetDateTime.parse("2026-08-18T04:00:00Z"),
                OffsetDateTime.parse("2026-08-18T06:00:00Z"),
                "창가 테이블 점검",
                now,
                now);

        var changed = mockMvc.perform(patch(
                                "/api/public/reservations/{code}", created.code())
                        .header("X-Reservation-Token", created.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startsAt":"2026-08-18T04:00:00Z","partySize":2,"expectedVersion":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startsAt").value("2026-08-18T04:00:00Z"))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn();
        var changedTableId = jdbc.queryForObject(
                "select dining_table_id from reservations where reservation_code = ?",
                UUID.class,
                created.code());
        assertNotEquals(originalTableId, changedTableId);

        mockMvc.perform(patch("/api/public/reservations/{code}", created.code())
                        .header("X-Reservation-Token", created.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partySize\":1,\"expectedVersion\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_VERSION_CONFLICT"));

        mockMvc.perform(post(
                                "/api/public/reservations/{code}/cancellation", created.code())
                        .header("X-Reservation-Token", created.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"일정 변경\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.version").value(2));

        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from reservation_status_history h "
                        + "join reservations r on r.id = h.reservation_id "
                        + "where r.reservation_code = ? and h.from_status = 'PENDING' "
                        + "and h.to_status = 'CANCELLED' and h.actor_type = 'CUSTOMER'",
                Long.class,
                created.code()));

        var replacement = createReservation(
                branchId, "2026-08-18T04:00:00Z", "010-3030-4040");
        assertTrue(replacement.code().matches("^[A-HJ-NP-Z2-9]{10}$"));
        assertNotEquals(created.code(), replacement.code());
        assertTrue(changed.getResponse().getContentAsString().contains("T2"));
    }

    @Test
    void customerCannotChangeOrCancelAfterBranchCutoff() throws Exception {
        var branchId = createBookableBranch(1, (short) 1);
        var created = createReservation(
                branchId, "2026-08-17T02:30:00Z", "010-5050-6060");

        mockMvc.perform(patch("/api/public/reservations/{code}", created.code())
                        .header("X-Reservation-Token", created.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partySize\":1,\"expectedVersion\":0}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RESERVATION_CHANGE_CUTOFF_PASSED"));

        mockMvc.perform(post(
                                "/api/public/reservations/{code}/cancellation", created.code())
                        .header("X-Reservation-Token", created.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code")
                        .value("RESERVATION_CANCELLATION_NOT_ALLOWED"));
    }

    @Test
    void concurrentChangesWithSameVersionAllowOnlyOneWinner() throws Exception {
        var branchId = createBookableBranch(2, (short) 2);
        var created = createReservation(branchId, TUESDAY_START, "010-7070-8080");

        var results = runConcurrently(
                () -> change(created, "2026-08-18T04:00:00Z"),
                () -> change(created, "2026-08-18T05:00:00Z"));

        assertEquals(List.of(200, 409), results.stream()
                .map(result -> result.getResponse().getStatus())
                .sorted()
                .toList());
        var conflict = results.stream()
                .filter(result -> result.getResponse().getStatus() == 409)
                .findFirst()
                .orElseThrow();
        assertEquals(
                "RESERVATION_VERSION_CONFLICT",
                JsonPath.read(conflict.getResponse().getContentAsString(), "$.code"));
    }

    @Test
    void concurrentCancellationAndReplacementNeverLeaveOverlappingActiveReservations()
            throws Exception {
        var branchId = createBookableBranch(2, (short) 2);
        var phone = "010-9090-1010";
        var created = createReservation(branchId, TUESDAY_START, phone);

        var results = runConcurrently(
                () -> cancel(created),
                () -> requestReservation(branchId, TUESDAY_START, phone));

        assertEquals(200, results.get(0).getResponse().getStatus());
        assertEquals(
                "CANCELLED",
                JsonPath.read(results.get(0).getResponse().getContentAsString(), "$.status"));
        var replacementStatus = results.get(1).getResponse().getStatus();
        assertTrue(replacementStatus == 201 || replacementStatus == 409);
        if (replacementStatus == 409) {
            assertEquals(
                    "DUPLICATE_CUSTOMER_RESERVATION",
                    JsonPath.read(results.get(1).getResponse().getContentAsString(), "$.code"));
        }

        var activeCount = jdbc.queryForObject(
                "select count(*) from reservations "
                        + "where branch_id = ? and starts_at = ? "
                        + "and status in ('PENDING', 'CONFIRMED', 'SEATED', 'COMPLETED')",
                Long.class,
                branchId,
                OffsetDateTime.parse(TUESDAY_START));
        assertEquals(replacementStatus == 201 ? 1L : 0L, activeCount);
        assertTrue(activeCount <= 1L);
    }

    private MvcResult change(CreatedReservation created, String startsAt) throws Exception {
        return mockMvc.perform(patch("/api/public/reservations/{code}", created.code())
                        .header("X-Reservation-Token", created.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startsAt":"%s","expectedVersion":0}
                                """.formatted(startsAt)))
                .andReturn();
    }

    private CreatedReservation createReservation(
            UUID branchId, String startsAt, String phone) throws Exception {
        var result = requestReservation(branchId, startsAt, phone);
        assertEquals(201, result.getResponse().getStatus());
        var body = result.getResponse().getContentAsString();
        return new CreatedReservation(
                JsonPath.read(body, "$.reservation.reservationCode"),
                JsonPath.read(body, "$.manageToken"));
    }

    private MvcResult requestReservation(UUID branchId, String startsAt, String phone)
            throws Exception {
        return mockMvc.perform(post("/api/public/reservations")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "branchId":"%s",
                                  "startsAt":"%s",
                                  "partySize":2,
                                  "guestName":"관리 고객",
                                  "guestPhone":"%s",
                                  "privacyAgreement":{"agreed":true,"policyVersion":"2026-08-16"}
                                }
                                """.formatted(branchId, startsAt, phone)))
                .andReturn();
    }

    private MvcResult cancel(CreatedReservation created) throws Exception {
        return mockMvc.perform(post(
                                "/api/public/reservations/{code}/cancellation", created.code())
                        .header("X-Reservation-Token", created.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"동시 취소\",\"expectedVersion\":0}"))
                .andReturn();
    }

    private UUID createBookableBranch(int tableCount, short dayOfWeek) {
        var restaurantId = UUID.randomUUID();
        var branchId = UUID.randomUUID();
        var now = OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);
        jdbc.update(
                "insert into restaurants (id, name, status, created_at, updated_at) "
                        + "values (?, ?, 'ACTIVE', ?, ?)",
                restaurantId,
                "TableFlow-" + restaurantId,
                now,
                now);
        jdbc.update(
                "insert into branches (id, restaurant_id, name, address, created_at, updated_at) "
                        + "values (?, ?, ?, ?, ?, ?)",
                branchId,
                restaurantId,
                "강남점",
                "서울시 강남구",
                now,
                now);
        for (int index = 1; index <= tableCount; index++) {
            jdbc.update(
                    "insert into dining_tables "
                            + "(id, branch_id, name, capacity, enabled, created_at, updated_at) "
                            + "values (?, ?, ?, 2, true, ?, ?)",
                    UUID.randomUUID(),
                    branchId,
                    "T" + index,
                    now,
                    now);
        }
        jdbc.update(
                "insert into business_hours "
                        + "(id, branch_id, day_of_week, opens_at, closes_at, created_at, updated_at) "
                        + "values (?, ?, ?, '11:00:00', '18:00:00', ?, ?)",
                UUID.randomUUID(),
                branchId,
                dayOfWeek,
                now,
                now);
        return branchId;
    }

    @SafeVarargs
    private final List<MvcResult> runConcurrently(ThrowingRequest... requests) throws Exception {
        var ready = new CountDownLatch(requests.length);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(requests.length);
        try {
            var futures = new ArrayList<Future<MvcResult>>();
            for (var request : requests) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return request.perform();
                }));
            }
            ready.await();
            start.countDown();
            var results = new ArrayList<MvcResult>();
            for (var future : futures) results.add(future.get());
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private record CreatedReservation(String code, String token) {}

    @FunctionalInterface
    interface ThrowingRequest {
        MvcResult perform() throws Exception;
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
