package com.example.fullstack.reservation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationEncryptionRotationService {
    private static final Logger log =
            LoggerFactory.getLogger(ReservationEncryptionRotationService.class);

    private final ReservationRepository reservations;
    private final ReservationCryptoService crypto;
    private final boolean enabled;
    private final int batchSize;

    ReservationEncryptionRotationService(
            ReservationRepository reservations,
            ReservationCryptoService crypto,
            @Value("${app.reservation.encryption-rotation.enabled:false}") boolean enabled,
            @Value("${app.reservation.encryption-rotation.batch-size:100}") int batchSize) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("예약 암호화 키 교체 batch 크기는 1~1000이어야 합니다.");
        }
        this.reservations = reservations;
        this.crypto = crypto;
        this.enabled = enabled;
        this.batchSize = batchSize;
    }

    @Scheduled(
            cron = "${app.reservation.encryption-rotation.cron:0 40 3 * * *}",
            zone = "UTC")
    @Transactional
    public int rotateBatch() {
        if (!enabled) return 0;
        var candidates = reservations.findEncryptionRotationCandidates(
                crypto.activeCiphertextPrefix(), batchSize);
        for (var reservation : candidates) {
            reservation.rotatePersonalDataEncryption(
                    crypto.encrypt(crypto.decrypt(reservation.guestNameCiphertext())),
                    crypto.encrypt(crypto.decrypt(reservation.guestPhoneCiphertext())));
        }
        if (!candidates.isEmpty()) {
            reservations.flush();
            log.info("reservation_personal_data_reencrypted count={}", candidates.size());
        }
        return candidates.size();
    }
}
