package com.example.fullstack.reservation;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationAvailabilityReader extends Repository<Reservation, UUID> {
    @Query("""
            select new com.example.fullstack.reservation.ReservationOccupancy(
                r.id, r.diningTableId, r.startsAt, r.occupiedUntil)
            from Reservation r
            where r.branchId = :branchId
              and r.status in :statuses
              and r.startsAt < :endsAt
              and r.occupiedUntil > :startsAt
            """)
    List<ReservationOccupancy> findOccupancies(
            @Param("branchId") UUID branchId,
            @Param("startsAt") Instant startsAt,
            @Param("endsAt") Instant endsAt,
            @Param("statuses") Set<ReservationStatus> statuses);

    @Query("""
            select count(r) from Reservation r
            where r.branchId = :branchId
              and r.status in :statuses
              and r.startsAt < :endsAt
              and r.occupiedUntil > :startsAt
            """)
    long countBranchOccupancyConflicts(
            @Param("branchId") UUID branchId,
            @Param("startsAt") Instant startsAt,
            @Param("endsAt") Instant endsAt,
            @Param("statuses") Set<ReservationStatus> statuses);

    @Query("""
            select count(r) from Reservation r
            where r.branchId = :branchId
              and r.diningTableId = :tableId
              and r.status in :statuses
              and r.startsAt < :endsAt
              and r.occupiedUntil > :startsAt
            """)
    long countTableOccupancyConflicts(
            @Param("branchId") UUID branchId,
            @Param("tableId") UUID tableId,
            @Param("startsAt") Instant startsAt,
            @Param("endsAt") Instant endsAt,
            @Param("statuses") Set<ReservationStatus> statuses);

    @Query("""
            select count(r) from Reservation r
            where r.diningTableId = :tableId
              and r.partySize > :capacity
              and r.status in :statuses
              and r.occupiedUntil > :now
            """)
    long countFutureReservationsExceedingCapacity(
            @Param("tableId") UUID tableId,
            @Param("capacity") int capacity,
            @Param("now") Instant now,
            @Param("statuses") Set<ReservationStatus> statuses);
}
