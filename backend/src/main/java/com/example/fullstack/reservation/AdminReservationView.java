package com.example.fullstack.reservation;

import com.example.fullstack.restaurant.Branch;
import com.example.fullstack.restaurant.DiningTable;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

record AdminReservationView(
        UUID id,
        String reservationCode,
        UUID branchId,
        String branchName,
        String timezone,
        String tableName,
        int partySize,
        Instant startsAt,
        Instant endsAt,
        ReservationStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt,
        String guestName,
        String guestPhoneLastFour,
        List<StatusHistoryView> history) {
    static AdminReservationView from(
            Reservation reservation,
            Branch branch,
            DiningTable table,
            String guestName,
            List<ReservationStatusHistory> history) {
        return new AdminReservationView(
                reservation.id(),
                reservation.reservationCode(),
                branch.id(),
                branch.name(),
                branch.timezone(),
                table.name(),
                reservation.partySize(),
                reservation.startsAt(),
                reservation.endsAt(),
                reservation.status(),
                reservation.version(),
                reservation.createdAtValue(),
                reservation.updatedAtValue(),
                guestName,
                reservation.phoneLastFour(),
                history.stream().map(StatusHistoryView::from).toList());
    }

    record StatusHistoryView(
            ReservationStatus fromStatus,
            ReservationStatus toStatus,
            ReservationActorType actorType,
            String actorId,
            String reason,
            Instant changedAt) {
        static StatusHistoryView from(ReservationStatusHistory history) {
            return new StatusHistoryView(
                    history.fromStatus(),
                    history.toStatus(),
                    history.actorType(),
                    history.actorId(),
                    history.reason(),
                    history.changedAt());
        }
    }
}
