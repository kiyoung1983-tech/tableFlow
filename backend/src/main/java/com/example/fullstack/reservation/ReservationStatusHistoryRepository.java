package com.example.fullstack.reservation;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ReservationStatusHistoryRepository
        extends JpaRepository<ReservationStatusHistory, UUID> {
    List<ReservationStatusHistory> findAllByReservationIdInOrderByChangedAtAsc(
            Collection<UUID> reservationIds);

    List<ReservationStatusHistory> findAllByReservationIdOrderByChangedAtAsc(UUID reservationId);
}
