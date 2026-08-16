package com.example.fullstack.reservation;

import com.example.fullstack.restaurant.Branch;
import com.example.fullstack.restaurant.DiningTable;
import java.time.Instant;
import java.util.UUID;

record PublicReservationView(
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
        Instant updatedAt) {
    static PublicReservationView from(
            Reservation reservation, Branch branch, DiningTable table) {
        return new PublicReservationView(
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
                reservation.updatedAtValue());
    }
}
