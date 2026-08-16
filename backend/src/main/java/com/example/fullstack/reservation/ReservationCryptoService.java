package com.example.fullstack.reservation;

import com.example.fullstack.common.error.ApiException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ReservationCryptoService {
    private static final Pattern PHONE_SEPARATORS = Pattern.compile("[\\s().-]");
    private static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{7,14}$");
    private static final byte[] LEGACY_ENCRYPTION_AAD =
            "tableflow-reservation-v1".getBytes(StandardCharsets.UTF_8);
    private static final Pattern KEY_ID = Pattern.compile("^[A-Za-z0-9_-]{1,32}$");
    private static final String RESERVATION_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final String activeEncryptionKeyId;
    private final Map<String, SecretKeySpec> encryptionKeys;
    private final SecretKeySpec hmacKey;
    private final SecureRandom secureRandom = new SecureRandom();

    ReservationCryptoService(String encryptionKey, String hmacKey) {
        this(encryptionKey, "primary", "", "previous", hmacKey);
    }

    @Autowired
    public ReservationCryptoService(
            @Value("${app.reservation.encryption-key}") String encryptionKey,
            @Value("${app.reservation.encryption-key-id:primary}") String encryptionKeyId,
            @Value("${app.reservation.previous-encryption-key:}") String previousEncryptionKey,
            @Value("${app.reservation.previous-encryption-key-id:previous}")
                    String previousEncryptionKeyId,
            @Value("${app.reservation.hmac-key}") String hmacKey) {
        validateKeyId(encryptionKeyId, "encryption-key-id");
        var keys = new LinkedHashMap<String, SecretKeySpec>();
        keys.put(
                encryptionKeyId,
                new SecretKeySpec(decodeKey(encryptionKey, "encryption-key"), "AES"));
        if (!previousEncryptionKey.isBlank()) {
            validateKeyId(previousEncryptionKeyId, "previous-encryption-key-id");
            if (keys.containsKey(previousEncryptionKeyId)) {
                throw new IllegalStateException("현재 키와 이전 키의 ID는 달라야 합니다.");
            }
            keys.put(
                    previousEncryptionKeyId,
                    new SecretKeySpec(
                            decodeKey(previousEncryptionKey, "previous-encryption-key"), "AES"));
        }
        this.activeEncryptionKeyId = encryptionKeyId;
        this.encryptionKeys = Map.copyOf(keys);
        this.hmacKey = new SecretKeySpec(decodeKey(hmacKey, "hmac-key"), "HmacSHA256");
    }

    public String normalizePhone(String phone) {
        var normalized = PHONE_SEPARATORS.matcher(phone.strip()).replaceAll("");
        if (normalized.startsWith("00")) normalized = "+" + normalized.substring(2);
        if (normalized.startsWith("0")) normalized = "+82" + normalized.substring(1);
        if (!E164.matcher(normalized).matches()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_PHONE_NUMBER",
                    "올바른 휴대전화 번호를 입력해 주세요.");
        }
        return normalized;
    }

    public String encrypt(String plaintext) {
        var key = encryptionKeys.get(activeEncryptionKeyId);
        var aad = encryptionAad(activeEncryptionKeyId);
        try {
            var nonce = new byte[12];
            secureRandom.nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad);
            var ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            var payload = ByteBuffer.allocate(nonce.length + ciphertext.length)
                    .put(nonce)
                    .put(ciphertext)
                    .array();
            return "v2." + activeEncryptionKeyId + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("예약 개인정보를 암호화할 수 없습니다.", exception);
        }
    }

    public String decrypt(String encrypted) {
        if (encrypted.startsWith("v2.")) {
            var parts = encrypted.split("\\.", 3);
            if (parts.length != 3) {
                throw new IllegalArgumentException("올바르지 않은 예약 암호문입니다.");
            }
            var key = encryptionKeys.get(parts[1]);
            if (key == null) {
                throw new IllegalArgumentException("예약 암호문 키를 찾을 수 없습니다.");
            }
            return decryptPayload(parts[2], key, encryptionAad(parts[1]));
        }
        if (encrypted.startsWith("v1.")) return decryptLegacy(encrypted.substring(3));
        throw new IllegalArgumentException("지원하지 않는 예약 암호문 버전입니다.");
    }

    String activeCiphertextPrefix() {
        return "v2." + activeEncryptionKeyId + ".";
    }

    private String decryptLegacy(String encodedPayload) {
        for (var key : encryptionKeys.values()) {
            try {
                return decryptPayload(encodedPayload, key, LEGACY_ENCRYPTION_AAD);
            } catch (IllegalArgumentException ignored) {
                // 키 교체 중 이전 키까지 순서대로 시도한다.
            }
        }
        throw new IllegalArgumentException("예약 개인정보를 복호화할 수 없습니다.");
    }

    private static String decryptPayload(
            String encodedPayload, SecretKeySpec key, byte[] aad) {
        try {
            var payload = Base64.getUrlDecoder().decode(encodedPayload);
            if (payload.length < 29) {
                throw new IllegalArgumentException("올바르지 않은 예약 암호문입니다.");
            }
            var nonce = new byte[12];
            var ciphertext = new byte[payload.length - nonce.length];
            System.arraycopy(payload, 0, nonce, 0, nonce.length);
            System.arraycopy(payload, nonce.length, ciphertext, 0, ciphertext.length);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad);
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("예약 개인정보를 복호화할 수 없습니다.", exception);
        }
    }

    public String phoneHash(String normalizedPhone) {
        return hmacHex("contact-phone", normalizedPhone);
    }

    public String requestFingerprint(String canonicalRequest) {
        return hmacHex("idempotency-request", canonicalRequest);
    }

    public String manageToken(UUID idempotencyKey) {
        var bytes = hmac("manage-token-derive", idempotencyKey.toString());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String manageTokenHash(String manageToken) {
        return hmacHex("manage-token-verify", manageToken);
    }

    public String reservationCode() {
        var code = new StringBuilder(10);
        for (int index = 0; index < 10; index++) {
            code.append(RESERVATION_CODE_ALPHABET.charAt(
                    secureRandom.nextInt(RESERVATION_CODE_ALPHABET.length())));
        }
        return code.toString();
    }

    public boolean tokenMatches(String rawToken, String expectedHash) {
        var actual = HexFormat.of().parseHex(manageTokenHash(rawToken));
        var expected = HexFormat.of().parseHex(expectedHash);
        return MessageDigest.isEqual(actual, expected);
    }

    public String erasureHash(String domain, UUID reservationId) {
        return hmacHex("personal-data-erasure-" + domain, reservationId.toString());
    }

    private String hmacHex(String domain, String value) {
        return HexFormat.of().formatHex(hmac(domain, value));
    }

    private byte[] hmac(String domain, String value) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            mac.update(domain.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("예약 무결성 값을 계산할 수 없습니다.", exception);
        }
    }

    private static byte[] decodeKey(String encoded, String propertyName) {
        try {
            var decoded = Base64.getDecoder().decode(encoded);
            if (decoded.length != 32) {
                throw new IllegalArgumentException(propertyName + " must decode to 32 bytes");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "app.reservation." + propertyName + " must be a Base64 encoded 32-byte key",
                    exception);
        }
    }

    private static byte[] encryptionAad(String keyId) {
        return ("tableflow-reservation-v2:" + keyId).getBytes(StandardCharsets.UTF_8);
    }

    private static void validateKeyId(String keyId, String propertyName) {
        if (!KEY_ID.matcher(keyId).matches()) {
            throw new IllegalStateException(
                    "app.reservation." + propertyName
                            + " must contain 1-32 letters, digits, underscores, or hyphens");
        }
    }
}
