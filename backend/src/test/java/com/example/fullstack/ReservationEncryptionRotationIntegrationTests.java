package com.example.fullstack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.fullstack.reservation.ReservationCryptoService;
import com.example.fullstack.reservation.ReservationEncryptionRotationService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
    "app.reservation.encryption-key=dGFibGVmbG93LWxvY2FsLWhtYWMta2V5LTEyMzQ1Njc=",
    "app.reservation.encryption-key-id=new",
    "app.reservation.previous-encryption-key=dGFibGVmbG93LWxvY2FsLWVuY3J5cHRpb24ta2V5ISE=",
    "app.reservation.previous-encryption-key-id=old",
    "app.reservation.encryption-rotation.enabled=true",
    "app.reservation.encryption-rotation.batch-size=10"
})
@ActiveProfiles("test")
class ReservationEncryptionRotationIntegrationTests {
    private static final String OLD_KEY =
            "dGFibGVmbG93LWxvY2FsLWVuY3J5cHRpb24ta2V5ISE=";
    private static final String HMAC_KEY =
            "dGFibGVmbG93LWxvY2FsLWhtYWMta2V5LTEyMzQ1Njc=";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReservationEncryptionRotationService rotationService;

    @Autowired
    private ReservationCryptoService activeCrypto;

    @Test
    void previousKeyCiphertextIsReencryptedWithActiveKey() {
        var oldCrypto = new ReservationCryptoService(OLD_KEY, "old", "", "unused", HMAC_KEY);
        var ids = insertReservation(
                oldCrypto.encrypt("교체 대상 고객"), oldCrypto.encrypt("+821012345678"));

        assertEquals(1, rotationService.rotateBatch());

        var ciphertexts = jdbc.queryForMap(
                "select guest_name_ciphertext, guest_phone_ciphertext from reservations where id = ?",
                ids.reservationId());
        var nameCiphertext = ciphertexts.get("guest_name_ciphertext").toString();
        var phoneCiphertext = ciphertexts.get("guest_phone_ciphertext").toString();
        assertTrue(nameCiphertext.startsWith("v2.new."));
        assertTrue(phoneCiphertext.startsWith("v2.new."));
        assertEquals("교체 대상 고객", activeCrypto.decrypt(nameCiphertext));
        assertEquals("+821012345678", activeCrypto.decrypt(phoneCiphertext));
        assertEquals(0, rotationService.rotateBatch());
    }

    private InsertedIds insertReservation(String nameCiphertext, String phoneCiphertext) {
        var restaurantId = UUID.randomUUID();
        var branchId = UUID.randomUUID();
        var tableId = UUID.randomUUID();
        var reservationId = UUID.randomUUID();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                "insert into restaurants (id, name, created_at, updated_at) values (?, ?, ?, ?)",
                restaurantId,
                "Rotation-" + restaurantId,
                now,
                now);
        jdbc.update(
                "insert into branches (id, restaurant_id, name, address, created_at, updated_at) "
                        + "values (?, ?, ?, ?, ?, ?)",
                branchId,
                restaurantId,
                "키교체점",
                "서울시 중구",
                now,
                now);
        jdbc.update(
                "insert into dining_tables (id, branch_id, name, capacity, created_at, updated_at) "
                        + "values (?, ?, 'T1', 2, ?, ?)",
                tableId,
                branchId,
                now,
                now);
        jdbc.update(
                """
                insert into reservations (
                  id, branch_id, dining_table_id, reservation_code, manage_token_hash,
                  idempotency_key, request_fingerprint, guest_name_ciphertext,
                  guest_phone_ciphertext, contact_phone_hash, phone_last_four, party_size,
                  starts_at, ends_at, occupied_until, status, privacy_policy_version,
                  privacy_agreed_at, created_at, updated_at
                ) values (
                  ?, ?, ?, 'ABCDEFGHJK', repeat('a', 64), ?, repeat('b', 64), ?, ?,
                  repeat('c', 64), '5678', 2, ?, ?, ?, 'PENDING', '2026-08-16', ?, ?, ?
                )
                """,
                reservationId,
                branchId,
                tableId,
                UUID.randomUUID(),
                nameCiphertext,
                phoneCiphertext,
                now.plusDays(1),
                now.plusDays(1).plusMinutes(90),
                now.plusDays(1).plusMinutes(105),
                now,
                now,
                now);
        return new InsertedIds(reservationId);
    }

    private record InsertedIds(UUID reservationId) {}
}
