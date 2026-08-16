package com.example.fullstack.reservation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class ReservationTransitionPolicyTests {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Duration CANCELLATION_CUTOFF = Duration.ofHours(3);
    private static final Duration NO_SHOW_GRACE = Duration.ofMinutes(15);
    private static final Instant STARTS_AT = Instant.parse("2026-08-17T10:00:00Z");
    private static final Instant ENDS_AT = Instant.parse("2026-08-17T11:30:00Z");

    private final ReservationTransitionPolicy policy = new ReservationTransitionPolicy();

    @Test
    void pendingCanBeConfirmedByAdminBeforeStart() {
        assertDoesNotThrow(() -> validate(
                ReservationStatus.PENDING,
                ReservationStatus.CONFIRMED,
                ReservationActorType.ADMIN,
                Instant.parse("2026-08-17T09:00:00Z"),
                null));

        assertThrows(ReservationTransitionRejectedException.class, () -> validate(
                ReservationStatus.PENDING,
                ReservationStatus.CONFIRMED,
                ReservationActorType.CUSTOMER,
                Instant.parse("2026-08-17T09:00:00Z"),
                null));
    }

    @Test
    void customerCancellationAllowsExactCutoffButRejectsAfterIt() {
        var exactCutoff = STARTS_AT.minus(CANCELLATION_CUTOFF);

        assertDoesNotThrow(() -> validate(
                ReservationStatus.CONFIRMED,
                ReservationStatus.CANCELLED,
                ReservationActorType.CUSTOMER,
                exactCutoff,
                null));
        assertThrows(ReservationTransitionRejectedException.class, () -> validate(
                ReservationStatus.CONFIRMED,
                ReservationStatus.CANCELLED,
                ReservationActorType.CUSTOMER,
                exactCutoff.plusMillis(1),
                null));
    }

    @Test
    void lateAdminCancellationRequiresReason() {
        var afterCutoff = STARTS_AT.minus(CANCELLATION_CUTOFF).plusSeconds(1);

        assertThrows(ReservationTransitionRejectedException.class, () -> validate(
                ReservationStatus.CONFIRMED,
                ReservationStatus.CANCELLED,
                ReservationActorType.ADMIN,
                afterCutoff,
                " "));
        assertDoesNotThrow(() -> validate(
                ReservationStatus.CONFIRMED,
                ReservationStatus.CANCELLED,
                ReservationActorType.ADMIN,
                afterCutoff,
                "전화 요청"));
    }

    @Test
    void noShowRequiresGracePeriodAndAdminOrSystemActor() {
        var boundary = STARTS_AT.plus(NO_SHOW_GRACE);

        assertThrows(ReservationTransitionRejectedException.class, () -> validate(
                ReservationStatus.CONFIRMED,
                ReservationStatus.NO_SHOW,
                ReservationActorType.ADMIN,
                boundary.minusMillis(1),
                null));
        assertDoesNotThrow(() -> validate(
                ReservationStatus.CONFIRMED,
                ReservationStatus.NO_SHOW,
                ReservationActorType.SYSTEM,
                boundary,
                null));
    }

    @Test
    void seatingIsRestrictedToReservationLocalDate() {
        assertDoesNotThrow(() -> validate(
                ReservationStatus.CONFIRMED,
                ReservationStatus.SEATED,
                ReservationActorType.ADMIN,
                Instant.parse("2026-08-17T00:00:00Z"),
                null));
        assertThrows(ReservationTransitionRejectedException.class, () -> validate(
                ReservationStatus.CONFIRMED,
                ReservationStatus.SEATED,
                ReservationActorType.ADMIN,
                Instant.parse("2026-08-16T14:59:59Z"),
                null));
    }

    @Test
    void completionRequiresServiceEndAndTerminalStatesCannotTransition() {
        assertThrows(ReservationTransitionRejectedException.class, () -> validate(
                ReservationStatus.SEATED,
                ReservationStatus.COMPLETED,
                ReservationActorType.ADMIN,
                ENDS_AT.minusMillis(1),
                null));
        assertDoesNotThrow(() -> validate(
                ReservationStatus.SEATED,
                ReservationStatus.COMPLETED,
                ReservationActorType.ADMIN,
                ENDS_AT,
                null));
        assertThrows(ReservationTransitionRejectedException.class, () -> validate(
                ReservationStatus.CANCELLED,
                ReservationStatus.CONFIRMED,
                ReservationActorType.ADMIN,
                ENDS_AT,
                null));
    }

    @Test
    void completedKeepsItsRecordedOccupancyButCancellationAndNoShowReleaseIt() {
        assertTrue(ReservationStatus.PENDING.holdsRecordedCapacity());
        assertTrue(ReservationStatus.CONFIRMED.holdsRecordedCapacity());
        assertTrue(ReservationStatus.SEATED.holdsRecordedCapacity());
        assertTrue(ReservationStatus.COMPLETED.holdsRecordedCapacity());
        assertFalse(ReservationStatus.CANCELLED.holdsRecordedCapacity());
        assertFalse(ReservationStatus.NO_SHOW.holdsRecordedCapacity());
    }

    private void validate(
            ReservationStatus current,
            ReservationStatus target,
            ReservationActorType actor,
            Instant now,
            String reason) {
        policy.validate(
                current,
                target,
                actor,
                STARTS_AT,
                ENDS_AT,
                now,
                SEOUL,
                CANCELLATION_CUTOFF,
                NO_SHOW_GRACE,
                reason);
    }
}
