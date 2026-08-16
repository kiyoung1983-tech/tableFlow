package com.example.fullstack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    AdminReservationOperationsIntegrationTests.FixedClockConfiguration.class
})
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminReservationOperationsIntegrationTests {
    private static final Instant FIXED_NOW = Instant.parse("2026-08-17T00:00:00Z");
    private static final String USERNAME = "developer";
    private static final String PASSWORD = "change-me-locally";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void listAndDashboardExposePagedOperationsDataWithoutFullPhoneNumber() throws Exception {
        var branchId = createBookableBranch(3);
        var first = createReservation(
                branchId, "2026-08-18T02:00:00Z", "첫 고객", "010-1111-1001");
        createReservation(branchId, "2026-08-18T02:00:00Z", "둘 고객", "010-1111-1002");
        createReservation(branchId, "2026-08-18T04:00:00Z", "셋 고객", "010-1111-1003");
        var unusedTableId = jdbc.queryForObject(
                "select id from dining_tables where branch_id = ? and id not in "
                        + "(select dining_table_id from reservations where branch_id = ? "
                        + "and starts_at = '2026-08-18T02:00:00Z')",
                UUID.class,
                branchId,
                branchId);
        var now = OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);
        jdbc.update(
                "insert into booking_blocks "
                        + "(id, branch_id, dining_table_id, starts_at, ends_at, reason, created_at, updated_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                branchId,
                unusedTableId,
                OffsetDateTime.parse("2026-08-18T02:30:00Z"),
                OffsetDateTime.parse("2026-08-18T03:00:00Z"),
                "점검",
                now,
                now);

        mockMvc.perform(get("/api/admin/branches/{branchId}/reservations", branchId)
                        .param("date", "2026-08-18")
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/admin/branches/{branchId}/reservations", branchId)
                        .with(httpBasic(USERNAME, PASSWORD))
                        .param("date", "2026-08-18")
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(false))
                .andExpect(jsonPath("$.items[0].guestName").exists())
                .andExpect(jsonPath("$.items[0].guestPhoneLastFour").value("1001"))
                .andExpect(jsonPath("$.items[0].history[0].toStatus").value("PENDING"))
                .andExpect(jsonPath("$.items[0].guestPhone").doesNotExist());

        mockMvc.perform(get("/api/admin/branches/{branchId}/reservations", branchId)
                        .with(httpBasic(USERNAME, PASSWORD))
                        .param("date", "2026-08-18")
                        .param("status", "CONFIRMED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/api/admin/reservations/{reservationId}", first.id())
                        .with(httpBasic(USERNAME, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationCode").value(first.code()))
                .andExpect(jsonPath("$.guestName").value("첫 고객"));

        mockMvc.perform(get(
                                "/api/admin/branches/{branchId}/reservation-dashboard",
                                branchId)
                        .with(httpBasic(USERNAME, PASSWORD))
                        .param("date", "2026-08-18"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.totalEnabledTables").value(3))
                .andExpect(jsonPath("$.statusCounts.pending").value(3))
                .andExpect(jsonPath("$.capacityTimeline[0].startsAt")
                        .value("2026-08-18T02:00:00Z"))
                .andExpect(jsonPath("$.capacityTimeline[0].availableTableCount").value(1))
                .andExpect(jsonPath("$.capacityTimeline[0].occupiedTableCount").value(2))
                .andExpect(jsonPath("$.capacityTimeline[1].availableTableCount").value(0))
                .andExpect(jsonPath("$.capacityTimeline[1].occupiedTableCount").value(2))
                .andExpect(jsonPath("$.capacityTimeline[1].blockedTableCount").value(1));
    }

    @Test
    void administratorCanCompleteTheOperationalLifecycleAndMarkNoShow() throws Exception {
        var branchId = createBookableBranch(2);
        var visit = createReservation(
                branchId, "2026-08-17T02:30:00Z", "방문 고객", "010-2222-2001");

        transition(visit.id(), "CONFIRMED", 0, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.version").value(1));
        transition(visit.id(), "SEATED", 1, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEATED"))
                .andExpect(jsonPath("$.version").value(2));

        jdbc.update(
                "update reservations set starts_at = ?, ends_at = ?, occupied_until = ? where id = ?",
                OffsetDateTime.parse("2026-08-16T21:00:00Z"),
                OffsetDateTime.parse("2026-08-16T22:30:00Z"),
                OffsetDateTime.parse("2026-08-16T22:45:00Z"),
                visit.id());
        transition(visit.id(), "COMPLETED", 2, "정상 이용 완료")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.history[3].actorType").value("ADMIN"))
                .andExpect(jsonPath("$.history[3].actorId").value(USERNAME));

        var absent = createReservation(
                branchId, "2026-08-17T05:00:00Z", "노쇼 고객", "010-2222-2002");
        transition(absent.id(), "CONFIRMED", 0, null).andExpect(status().isOk());
        jdbc.update(
                "update reservations set starts_at = ?, ends_at = ?, occupied_until = ? where id = ?",
                OffsetDateTime.parse("2026-08-16T23:00:00Z"),
                OffsetDateTime.parse("2026-08-17T00:30:00Z"),
                OffsetDateTime.parse("2026-08-17T00:45:00Z"),
                absent.id());
        transition(absent.id(), "NO_SHOW", 1, "연락 불가")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_SHOW"))
                .andExpect(jsonPath("$.history[2].reason").value("연락 불가"));
    }

    @Test
    void transitionEnforcesVersionRulesAndLateCancellationReason() throws Exception {
        var branchId = createBookableBranch(3);
        var late = createReservation(
                branchId, "2026-08-17T02:30:00Z", "취소 고객", "010-3333-3001");

        transition(late.id(), "CANCELLED", 0, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_RESERVATION_TRANSITION"));
        transition(late.id(), "CANCELLED", 0, "고객 전화 요청")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.version").value(1));

        transition(late.id(), "CANCELLED", 0, "중복 재시도")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        assertEquals(2L, jdbc.queryForObject(
                "select count(*) from reservation_status_history where reservation_id = ?",
                Long.class,
                late.id()));

        var pending = createReservation(
                branchId, "2026-08-18T04:00:00Z", "검증 고객", "010-3333-3002");
        transition(pending.id(), "SEATED", 0, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_RESERVATION_TRANSITION"));
        transition(pending.id(), "CONFIRMED", 99, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_VERSION_CONFLICT"));
    }

    @Test
    void concurrentTransitionsWithOneVersionHaveOneWinner() throws Exception {
        var branchId = createBookableBranch(1);
        var reservation = createReservation(
                branchId, "2026-08-18T02:00:00Z", "동시 고객", "010-4444-4001");

        var results = runConcurrently(
                () -> transitionResult(reservation.id(), "CONFIRMED", 0, null),
                () -> transitionResult(reservation.id(), "CANCELLED", 0, null));

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
                (String) JsonPath.read(conflict.getResponse().getContentAsString(), "$.code"));
        assertEquals(2L, jdbc.queryForObject(
                "select count(*) from reservation_status_history where reservation_id = ?",
                Long.class,
                reservation.id()));
    }

    private org.springframework.test.web.servlet.ResultActions transition(
            UUID reservationId, String targetStatus, long expectedVersion, String reason)
            throws Exception {
        return mockMvc.perform(post(
                                "/api/admin/reservations/{reservationId}/transitions",
                                reservationId)
                        .with(httpBasic(USERNAME, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transitionBody(targetStatus, expectedVersion, reason)));
    }

    private MvcResult transitionResult(
            UUID reservationId, String targetStatus, long expectedVersion, String reason)
            throws Exception {
        return mockMvc.perform(post(
                                "/api/admin/reservations/{reservationId}/transitions",
                                reservationId)
                        .with(httpBasic(USERNAME, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transitionBody(targetStatus, expectedVersion, reason)))
                .andReturn();
    }

    private static String transitionBody(
            String targetStatus, long expectedVersion, String reason) {
        var reasonField = reason == null ? "" : ",\"reason\":\"" + reason + "\"";
        return "{\"targetStatus\":\"" + targetStatus + "\",\"expectedVersion\":"
                + expectedVersion + reasonField + "}";
    }

    private CreatedReservation createReservation(
            UUID branchId, String startsAt, String guestName, String phone) throws Exception {
        var result = mockMvc.perform(post("/api/public/reservations")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "branchId":"%s",
                                  "startsAt":"%s",
                                  "partySize":2,
                                  "guestName":"%s",
                                  "guestPhone":"%s",
                                  "privacyAgreement":{"agreed":true,"policyVersion":"2026-08-16"}
                                }
                                """.formatted(branchId, startsAt, guestName, phone)))
                .andExpect(status().isCreated())
                .andReturn();
        var code = (String) JsonPath.read(
                result.getResponse().getContentAsString(), "$.reservation.reservationCode");
        var id = jdbc.queryForObject(
                "select id from reservations where reservation_code = ?", UUID.class, code);
        return new CreatedReservation(id, code);
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
                "운영점",
                "서울시 중구",
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
        for (short day : new short[] {1, 2}) {
            jdbc.update(
                    "insert into business_hours "
                            + "(id, branch_id, day_of_week, opens_at, closes_at, created_at, updated_at) "
                            + "values (?, ?, ?, '11:00:00', '18:00:00', ?, ?)",
                    UUID.randomUUID(),
                    branchId,
                    day,
                    now,
                    now);
        }
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

    private record CreatedReservation(UUID id, String code) {}

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
