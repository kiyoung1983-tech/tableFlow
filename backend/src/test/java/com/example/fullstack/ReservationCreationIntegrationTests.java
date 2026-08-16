package com.example.fullstack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
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
    ReservationCreationIntegrationTests.FixedClockConfiguration.class
})
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReservationCreationIntegrationTests {
    private static final Instant FIXED_NOW = Instant.parse("2026-08-17T00:00:00Z");
    private static final String STARTS_AT = "2026-08-18T02:00:00Z";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void publicCreationEncryptsPersonalDataAndWritesInitialHistory() throws Exception {
        var branchId = createBookableBranch(2);
        var idempotencyKey = UUID.randomUUID();

        var result = createReservation(
                branchId, idempotencyKey, request(branchId, STARTS_AT, 2, "홍길동", "010-1234-5678"));

        assertEquals(201, result.getResponse().getStatus());
        var body = result.getResponse().getContentAsString();
        var reservationCode = (String) JsonPath.read(body, "$.reservation.reservationCode");
        var manageToken = (String) JsonPath.read(body, "$.manageToken");
        assertEquals(43, manageToken.length());
        assertTrue(reservationCode.matches("^[A-HJ-NP-Z2-9]{10}$"));

        var stored = jdbc.queryForMap(
                "select guest_name_ciphertext, guest_phone_ciphertext, contact_phone_hash, "
                        + "manage_token_hash from reservations where reservation_code = ?",
                reservationCode);
        assertTrue(stored.get("guest_name_ciphertext").toString().startsWith("v2."));
        assertFalse(stored.get("guest_name_ciphertext").toString().contains("홍길동"));
        assertFalse(stored.get("guest_phone_ciphertext").toString().contains("01012345678"));
        assertEquals(64, stored.get("contact_phone_hash").toString().length());
        assertEquals(64, stored.get("manage_token_hash").toString().length());
        assertNotEquals(manageToken, stored.get("manage_token_hash"));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from reservation_status_history h "
                        + "join reservations r on r.id = h.reservation_id "
                        + "where r.reservation_code = ? and h.to_status = 'PENDING'",
                Long.class,
                reservationCode));
    }

    @Test
    void sameIdempotencyKeyAndBodyReplaysSameReservationAndToken() throws Exception {
        var branchId = createBookableBranch(1);
        var key = UUID.randomUUID();
        var request = request(branchId, STARTS_AT, 2, "재시도 고객", "010-2222-3333");

        var first = createReservation(branchId, key, request);
        var second = createReservation(branchId, key, request);

        assertEquals(201, first.getResponse().getStatus());
        assertEquals(201, second.getResponse().getStatus());
        assertEquals(
                (String) JsonPath.read(first.getResponse().getContentAsString(), "$.reservation.reservationCode"),
                (String) JsonPath.read(second.getResponse().getContentAsString(), "$.reservation.reservationCode"));
        assertEquals(
                (String) JsonPath.read(first.getResponse().getContentAsString(), "$.manageToken"),
                (String) JsonPath.read(second.getResponse().getContentAsString(), "$.manageToken"));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from reservations where idempotency_key = ?",
                Long.class,
                key));
    }

    @Test
    void sameIdempotencyKeyWithDifferentBodyIsRejected() throws Exception {
        var branchId = createBookableBranch(2);
        var key = UUID.randomUUID();
        createReservation(branchId, key, request(branchId, STARTS_AT, 2, "고객", "010-3333-4444"));

        mockMvc.perform(post("/api/public/reservations")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(branchId, STARTS_AT, 3, "고객", "010-3333-4444")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void overlappingReservationForSamePhoneIsRejectedEvenWhenAnotherTableExists() throws Exception {
        var branchId = createBookableBranch(2);
        createReservation(
                branchId,
                UUID.randomUUID(),
                request(branchId, STARTS_AT, 2, "중복 고객", "010-7777-8888"));

        mockMvc.perform(post("/api/public/reservations")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(
                                branchId,
                                "2026-08-18T02:30:00Z",
                                2,
                                "중복 고객",
                                "01077778888")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CUSTOMER_RESERVATION"));
    }

    @Test
    void sameCustomerCanBookAtServiceEndWhileCleanupStillOccupiesTheFirstTable()
            throws Exception {
        var branchId = createBookableBranch(2);
        var first = createReservation(
                branchId,
                UUID.randomUUID(),
                request(branchId, STARTS_AT, 2, "경계 고객", "010-7878-8989"));
        var second = createReservation(
                branchId,
                UUID.randomUUID(),
                request(
                        branchId,
                        "2026-08-18T03:30:00Z",
                        2,
                        "경계 고객",
                        "01078788989"));

        assertEquals(201, first.getResponse().getStatus());
        assertEquals(201, second.getResponse().getStatus());
        var firstCode = (String) JsonPath.read(
                first.getResponse().getContentAsString(), "$.reservation.reservationCode");
        var secondCode = (String) JsonPath.read(
                second.getResponse().getContentAsString(), "$.reservation.reservationCode");
        var firstTableId = jdbc.queryForObject(
                "select dining_table_id from reservations where reservation_code = ?",
                UUID.class,
                firstCode);
        var secondTableId = jdbc.queryForObject(
                "select dining_table_id from reservations where reservation_code = ?",
                UUID.class,
                secondCode);

        assertNotEquals(firstCode, secondCode);
        assertNotEquals(firstTableId, secondTableId);
        assertEquals(2L, jdbc.queryForObject(
                "select count(*) from reservations where branch_id = ?",
                Long.class,
                branchId));
    }

    @Test
    void concurrentRequestsForLastTableAllowOnlyOneReservation() throws Exception {
        var branchId = createBookableBranch(1);
        var firstRequest = request(branchId, STARTS_AT, 2, "첫 고객", "010-1111-0001");
        var secondRequest = request(branchId, STARTS_AT, 2, "둘 고객", "010-1111-0002");

        var results = runConcurrently(
                () -> createReservation(branchId, UUID.randomUUID(), firstRequest),
                () -> createReservation(branchId, UUID.randomUUID(), secondRequest));

        assertEquals(List.of(201, 409), results.stream()
                .map(result -> result.getResponse().getStatus())
                .sorted()
                .toList());
        var conflict = results.stream()
                .filter(result -> result.getResponse().getStatus() == 409)
                .findFirst()
                .orElseThrow();
        assertEquals(
                "NO_TABLE_AVAILABLE",
                JsonPath.read(conflict.getResponse().getContentAsString(), "$.code"));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from reservations where branch_id = ?",
                Long.class,
                branchId));
    }

    @Test
    void concurrentRetriesWithSameKeyReturnOneReservationAndSameResponse() throws Exception {
        var branchId = createBookableBranch(1);
        var key = UUID.randomUUID();
        var body = request(branchId, STARTS_AT, 2, "동시 재시도", "010-9999-0000");

        var results = runConcurrently(
                () -> createReservation(branchId, key, body),
                () -> createReservation(branchId, key, body));

        assertTrue(results.stream().allMatch(result -> result.getResponse().getStatus() == 201));
        assertEquals(
                (String) JsonPath.read(results.get(0).getResponse().getContentAsString(), "$.reservation.reservationCode"),
                (String) JsonPath.read(results.get(1).getResponse().getContentAsString(), "$.reservation.reservationCode"));
        assertEquals(
                (String) JsonPath.read(results.get(0).getResponse().getContentAsString(), "$.manageToken"),
                (String) JsonPath.read(results.get(1).getResponse().getContentAsString(), "$.manageToken"));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from reservations where idempotency_key = ?",
                Long.class,
                key));
    }

    @Test
    void tableCapacityCannotBeReducedBelowFutureReservationPartySize() throws Exception {
        var branchId = createBookableBranch(1);
        createReservation(
                branchId,
                UUID.randomUUID(),
                request(branchId, STARTS_AT, 2, "수용 인원", "010-1212-3434"));
        var tableId = jdbc.queryForObject(
                "select id from dining_tables where branch_id = ?",
                UUID.class,
                branchId);

        mockMvc.perform(patch("/api/admin/tables/{tableId}", tableId)
                        .with(httpBasic("developer", "change-me-locally"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"capacity\":1,\"expectedVersion\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TABLE_CAPACITY_CONFLICT"));
    }

    @Test
    void inactiveRestaurantRejectsNewReservationsForKnownBranchId() throws Exception {
        var branchId = createBookableBranch(1);
        jdbc.update(
                "update restaurants set status = 'INACTIVE' "
                        + "where id = (select restaurant_id from branches where id = ?)",
                branchId);

        mockMvc.perform(post("/api/public/reservations")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(
                                branchId,
                                STARTS_AT,
                                2,
                                "중지 식당 고객",
                                "010-4545-6767")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BOOKING_POLICY_VIOLATION"));
    }

    private MvcResult createReservation(UUID branchId, UUID key, String body) throws Exception {
        return mockMvc.perform(post("/api/public/reservations")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
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

    private UUID createBookableBranch(int tableCount) {
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
                        + "values (?, ?, 2, '11:00:00', '16:00:00', ?, ?)",
                UUID.randomUUID(),
                branchId,
                now,
                now);
        return branchId;
    }

    private static String request(
            UUID branchId,
            String startsAt,
            int partySize,
            String guestName,
            String guestPhone) {
        return """
                {
                  "branchId":"%s",
                  "startsAt":"%s",
                  "partySize":%d,
                  "guestName":"%s",
                  "guestPhone":"%s",
                  "privacyAgreement":{"agreed":true,"policyVersion":"2026-08-16"}
                }
                """.formatted(branchId, startsAt, partySize, guestName, guestPhone);
    }

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
