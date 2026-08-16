package com.example.fullstack;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReservationSchemaIntegrationTests {
    private static final Instant TEN = Instant.parse("2026-08-17T10:00:00Z");
    private static final Instant ELEVEN_THIRTY = Instant.parse("2026-08-17T11:30:00Z");
    private static final Instant ELEVEN_FORTY_FIVE = Instant.parse("2026-08-17T11:45:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    private UUID branchId;
    private UUID firstTableId;
    private UUID secondTableId;

    @BeforeEach
    void setUp() {
        var restaurantId = UUID.randomUUID();
        branchId = UUID.randomUUID();
        firstTableId = UUID.randomUUID();
        secondTableId = UUID.randomUUID();
        var now = Instant.parse("2026-08-16T00:00:00Z");

        jdbc.update(
                "insert into restaurants (id, name, status, created_at, updated_at) values (?, ?, 'ACTIVE', ?, ?)",
                restaurantId,
                "TableFlow",
                dbTime(now),
                dbTime(now));
        jdbc.update(
                "insert into branches (id, restaurant_id, name, address, created_at, updated_at) "
                        + "values (?, ?, ?, ?, ?, ?)",
                branchId,
                restaurantId,
                "강남점",
                "서울시 강남구",
                dbTime(now),
                dbTime(now));
        insertDiningTable(firstTableId, "T1", now);
        insertDiningTable(secondTableId, "T2", now);
    }

    @Test
    void adjacentReservationAtOccupiedUntilDoesNotOverlap() {
        insertReservation(firstTableId, "ABCDEFGH23", "c".repeat(64), TEN, ELEVEN_THIRTY, ELEVEN_FORTY_FIVE);

        assertDoesNotThrow(() -> insertReservation(
                firstTableId,
                "ABCDEFGH24",
                "d".repeat(64),
                ELEVEN_FORTY_FIVE,
                Instant.parse("2026-08-17T13:15:00Z"),
                Instant.parse("2026-08-17T13:30:00Z")));
    }

    @Test
    void tableOccupancyConstraintRejectsBufferOverlap() {
        insertReservation(firstTableId, "ABCDEFGH25", "c".repeat(64), TEN, ELEVEN_THIRTY, ELEVEN_FORTY_FIVE);

        assertThrows(DataIntegrityViolationException.class, () -> insertReservation(
                firstTableId,
                "ABCDEFGH26",
                "d".repeat(64),
                ELEVEN_THIRTY,
                Instant.parse("2026-08-17T13:00:00Z"),
                Instant.parse("2026-08-17T13:15:00Z")));
    }

    @Test
    void customerOverlapConstraintRejectsDifferentTableAtSameBranch() {
        insertReservation(firstTableId, "ABCDEFGH27", "c".repeat(64), TEN, ELEVEN_THIRTY, ELEVEN_FORTY_FIVE);

        assertThrows(DataIntegrityViolationException.class, () -> insertReservation(
                secondTableId,
                "ABCDEFGH28",
                "c".repeat(64),
                Instant.parse("2026-08-17T11:00:00Z"),
                Instant.parse("2026-08-17T12:30:00Z"),
                Instant.parse("2026-08-17T12:45:00Z")));
    }

    private void insertDiningTable(UUID tableId, String name, Instant now) {
        jdbc.update(
                "insert into dining_tables "
                        + "(id, branch_id, name, capacity, enabled, created_at, updated_at) "
                        + "values (?, ?, ?, 4, true, ?, ?)",
                tableId,
                branchId,
                name,
                dbTime(now),
                dbTime(now));
    }

    private void insertReservation(
            UUID tableId,
            String reservationCode,
            String phoneHash,
            Instant startsAt,
            Instant endsAt,
            Instant occupiedUntil) {
        var now = Instant.parse("2026-08-16T00:00:00Z");
        jdbc.update(
                "insert into reservations ("
                        + "id, branch_id, dining_table_id, reservation_code, manage_token_hash, "
                        + "idempotency_key, request_fingerprint, guest_name_ciphertext, "
                        + "guest_phone_ciphertext, contact_phone_hash, phone_last_four, party_size, "
                        + "starts_at, ends_at, occupied_until, status, privacy_policy_version, "
                        + "privacy_agreed_at, created_at, updated_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                branchId,
                tableId,
                reservationCode,
                "a".repeat(64),
                UUID.randomUUID(),
                "b".repeat(64),
                "encrypted-name",
                "encrypted-phone",
                phoneHash,
                "1234",
                4,
                dbTime(startsAt),
                dbTime(endsAt),
                dbTime(occupiedUntil),
                "PENDING",
                "2026-08-16",
                dbTime(now),
                dbTime(now),
                dbTime(now));
    }

    private static OffsetDateTime dbTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
