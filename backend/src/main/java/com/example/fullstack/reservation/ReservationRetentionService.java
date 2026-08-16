package com.example.fullstack.reservation;

import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationRetentionService {
    private static final Logger log = LoggerFactory.getLogger(ReservationRetentionService.class);

    private final ReservationRepository reservations;
    private final ReservationCryptoService crypto;
    private final Clock clock;
    private final boolean enabled;
    private final int retentionDays;
    private final int batchSize;

    ReservationRetentionService(
            ReservationRepository reservations,
            ReservationCryptoService crypto,
            Clock clock,
            @Value("${app.reservation.retention.enabled:false}") boolean enabled,
            @Value("${app.reservation.retention.days:365}") int retentionDays,
            @Value("${app.reservation.retention.batch-size:100}") int batchSize) {
        if (retentionDays < 1 || batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("예약 보존기간과 파기 batch 크기를 확인해 주세요.");
        }
        this.reservations = reservations;
        this.crypto = crypto;
        this.clock = clock;
        this.enabled = enabled;
        this.retentionDays = retentionDays;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${app.reservation.retention.cron:0 20 3 * * *}", zone = "UTC")
    @Transactional
    public int eraseExpiredPersonalData() {
        if (!enabled) return 0;
        var now = clock.instant();
        var cutoff = now.minus(Duration.ofDays(retentionDays));
        var candidates = reservations.findRetentionCandidates(cutoff, batchSize);
        for (var reservation : candidates) {
            reservation.erasePersonalData(
                    crypto.encrypt("[파기됨]"),
                    crypto.encrypt("[erased]"),
                    crypto.erasureHash("contact", reservation.id()),
                    crypto.erasureHash("manage-token", reservation.id()),
                    crypto.erasureHash("request", reservation.id()),
                    now);
        }
        if (!candidates.isEmpty()) {
            reservations.flush();
            log.info("reservation_personal_data_erased count={}", candidates.size());
        }
        return candidates.size();
    }
}
