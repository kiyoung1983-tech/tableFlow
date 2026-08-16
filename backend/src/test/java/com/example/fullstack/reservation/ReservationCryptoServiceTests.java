package com.example.fullstack.reservation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.fullstack.common.error.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReservationCryptoServiceTests {
    private static final String ENCRYPTION_KEY =
            "dGFibGVmbG93LWxvY2FsLWVuY3J5cHRpb24ta2V5ISE=";
    private static final String HMAC_KEY =
            "dGFibGVmbG93LWxvY2FsLWhtYWMta2V5LTEyMzQ1Njc=";

    private final ReservationCryptoService crypto =
            new ReservationCryptoService(ENCRYPTION_KEY, HMAC_KEY);

    @Test
    void encryptionUsesRandomNonceAndCanBeDecrypted() {
        var first = crypto.encrypt("홍길동");
        var second = crypto.encrypt("홍길동");

        assertNotEquals(first, second);
        assertEquals("홍길동", crypto.decrypt(first));
        assertEquals("홍길동", crypto.decrypt(second));
    }

    @Test
    void phoneNormalizationUsesKoreanCountryCodeAndStableHash() {
        var normalized = crypto.normalizePhone("010-1234-5678");

        assertEquals("+821012345678", normalized);
        assertEquals(64, crypto.phoneHash(normalized).length());
        assertEquals(crypto.phoneHash(normalized), crypto.phoneHash("+821012345678"));
        assertThrows(ApiException.class, () -> crypto.normalizePhone("1234"));
    }

    @Test
    void manageTokenIsDeterministicPerIdempotencyKeyAndVerifiable() {
        var key = UUID.randomUUID();
        var token = crypto.manageToken(key);

        assertEquals(token, crypto.manageToken(key));
        assertNotEquals(token, crypto.manageToken(UUID.randomUUID()));
        assertTrue(crypto.tokenMatches(token, crypto.manageTokenHash(token)));
    }

    @Test
    void newEncryptionKeyCanReadCiphertextFromConfiguredPreviousKey() {
        var oldCrypto = new ReservationCryptoService(
                ENCRYPTION_KEY, "old", "", "previous", HMAC_KEY);
        var rotatedCrypto = new ReservationCryptoService(
                HMAC_KEY, "new", ENCRYPTION_KEY, "old", HMAC_KEY);
        var oldCiphertext = oldCrypto.encrypt("교체 전 고객");

        assertTrue(oldCiphertext.startsWith("v2.old."));
        assertEquals("교체 전 고객", rotatedCrypto.decrypt(oldCiphertext));
        assertTrue(rotatedCrypto.encrypt("교체 후 고객").startsWith("v2.new."));
    }
}
