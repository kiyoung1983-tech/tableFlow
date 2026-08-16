package com.example.fullstack.reservation;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ReservationTransitionPolicy {
    private static final Map<ReservationStatus, Set<ReservationStatus>> ALLOWED_TRANSITIONS =
            new EnumMap<>(ReservationStatus.class);

    static {
        ALLOWED_TRANSITIONS.put(
                ReservationStatus.PENDING,
                EnumSet.of(ReservationStatus.CONFIRMED, ReservationStatus.CANCELLED));
        ALLOWED_TRANSITIONS.put(
                ReservationStatus.CONFIRMED,
                EnumSet.of(
                        ReservationStatus.SEATED,
                        ReservationStatus.CANCELLED,
                        ReservationStatus.NO_SHOW));
        ALLOWED_TRANSITIONS.put(
                ReservationStatus.SEATED,
                EnumSet.of(ReservationStatus.COMPLETED));
        ALLOWED_TRANSITIONS.put(ReservationStatus.COMPLETED, EnumSet.noneOf(ReservationStatus.class));
        ALLOWED_TRANSITIONS.put(ReservationStatus.CANCELLED, EnumSet.noneOf(ReservationStatus.class));
        ALLOWED_TRANSITIONS.put(ReservationStatus.NO_SHOW, EnumSet.noneOf(ReservationStatus.class));
    }

    public void validate(
            ReservationStatus current,
            ReservationStatus target,
            ReservationActorType actor,
            Instant startsAt,
            Instant endsAt,
            Instant now,
            ZoneId branchTimezone,
            Duration customerCancellationCutoff,
            Duration noShowGrace,
            String reason) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(startsAt, "startsAt");
        Objects.requireNonNull(endsAt, "endsAt");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(branchTimezone, "branchTimezone");
        Objects.requireNonNull(customerCancellationCutoff, "customerCancellationCutoff");
        Objects.requireNonNull(noShowGrace, "noShowGrace");

        if (current == target) {
            if (actor == ReservationActorType.ADMIN) {
                return;
            }
            reject("동일 상태 재요청은 관리자에게만 허용됩니다.");
        }
        if (!ALLOWED_TRANSITIONS.get(current).contains(target)) {
            reject("허용되지 않은 예약 상태 전이입니다: " + current + " -> " + target);
        }

        if (target == ReservationStatus.CANCELLED) {
            validateCancellation(actor, startsAt, now, customerCancellationCutoff, reason);
            return;
        }
        if (current == ReservationStatus.PENDING && target == ReservationStatus.CONFIRMED) {
            requireActor(actor, ReservationActorType.ADMIN);
            if (!now.isBefore(startsAt)) {
                reject("시작된 예약은 확정할 수 없습니다.");
            }
            return;
        }
        if (current == ReservationStatus.CONFIRMED && target == ReservationStatus.SEATED) {
            requireActor(actor, ReservationActorType.ADMIN);
            var reservationDate = startsAt.atZone(branchTimezone).toLocalDate();
            var currentDate = now.atZone(branchTimezone).toLocalDate();
            if (!currentDate.equals(reservationDate)) {
                reject("예약일 당일에만 착석 처리할 수 있습니다.");
            }
            return;
        }
        if (current == ReservationStatus.CONFIRMED && target == ReservationStatus.NO_SHOW) {
            if (actor != ReservationActorType.ADMIN && actor != ReservationActorType.SYSTEM) {
                reject("노쇼는 관리자 또는 시스템만 처리할 수 있습니다.");
            }
            if (now.isBefore(startsAt.plus(noShowGrace))) {
                reject("노쇼 판정 유예 시간이 지나지 않았습니다.");
            }
            return;
        }
        if (current == ReservationStatus.SEATED && target == ReservationStatus.COMPLETED) {
            requireActor(actor, ReservationActorType.ADMIN);
            if (now.isBefore(endsAt)) {
                reject("예약 이용 종료 시각 전에는 완료 처리할 수 없습니다.");
            }
        }
    }

    private static void validateCancellation(
            ReservationActorType actor,
            Instant startsAt,
            Instant now,
            Duration customerCancellationCutoff,
            String reason) {
        if (actor == ReservationActorType.SYSTEM) {
            reject("시스템은 예약을 취소할 수 없습니다.");
        }
        var cutoff = startsAt.minus(customerCancellationCutoff);
        if (actor == ReservationActorType.CUSTOMER && now.isAfter(cutoff)) {
            reject("고객 취소 가능 시간이 지났습니다.");
        }
        if (actor == ReservationActorType.ADMIN && now.isAfter(cutoff)
                && (reason == null || reason.isBlank())) {
            reject("마감 이후 관리자 취소에는 사유가 필요합니다.");
        }
    }

    private static void requireActor(ReservationActorType actual, ReservationActorType required) {
        if (actual != required) {
            reject(required + "만 처리할 수 있는 상태 전이입니다.");
        }
    }

    private static void reject(String message) {
        throw new ReservationTransitionRejectedException(message);
    }
}
