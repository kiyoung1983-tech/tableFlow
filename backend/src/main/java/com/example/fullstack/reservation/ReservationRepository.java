package com.example.fullstack.reservation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

interface ReservationRepository extends JpaRepository<Reservation, UUID> {
    Optional<Reservation> findByIdempotencyKey(UUID idempotencyKey);

    Optional<Reservation> findByReservationCode(String reservationCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.id = :id")
    Optional<Reservation> findByIdForUpdate(@Param("id") UUID id);

    Page<Reservation>
            findAllByBranchIdAndStartsAtGreaterThanEqualAndStartsAtLessThanOrderByStartsAtAscCreatedAtAsc(
                    UUID branchId, Instant startsAt, Instant endsAt, Pageable pageable);

    Page<Reservation>
            findAllByBranchIdAndStatusAndStartsAtGreaterThanEqualAndStartsAtLessThanOrderByStartsAtAscCreatedAtAsc(
                    UUID branchId,
                    ReservationStatus status,
                    Instant startsAt,
                    Instant endsAt,
                    Pageable pageable);

    List<Reservation>
            findAllByBranchIdAndStartsAtGreaterThanEqualAndStartsAtLessThanOrderByStartsAtAsc(
                    UUID branchId, Instant startsAt, Instant endsAt);

    @Query(
            value = """
                    select * from reservations
                    where personal_data_erased_at is null
                      and status in ('COMPLETED', 'CANCELLED', 'NO_SHOW')
                      and ends_at < :cutoff
                    order by ends_at, id
                    limit :batchSize
                    for update skip locked
                    """,
            nativeQuery = true)
    List<Reservation> findRetentionCandidates(
            @Param("cutoff") Instant cutoff,
            @Param("batchSize") int batchSize);

    @Query(
            value = """
                    select * from reservations
                    where left(guest_name_ciphertext, char_length(:activePrefix)) <> :activePrefix
                       or left(guest_phone_ciphertext, char_length(:activePrefix)) <> :activePrefix
                    order by updated_at, id
                    limit :batchSize
                    for update skip locked
                    """,
            nativeQuery = true)
    List<Reservation> findEncryptionRotationCandidates(
            @Param("activePrefix") String activePrefix,
            @Param("batchSize") int batchSize);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.reservationCode = :reservationCode")
    Optional<Reservation> findByReservationCodeForUpdate(
            @Param("reservationCode") String reservationCode);

    @Query("""
            select count(r) from Reservation r
            where r.branchId = :branchId
              and r.contactPhoneHash = :contactPhoneHash
              and r.status in :statuses
              and r.startsAt < :endsAt
              and r.endsAt > :startsAt
            """)
    long countCustomerOverlaps(
            @Param("branchId") UUID branchId,
            @Param("contactPhoneHash") String contactPhoneHash,
            @Param("startsAt") Instant startsAt,
            @Param("endsAt") Instant endsAt,
            @Param("statuses") Set<ReservationStatus> statuses);

    @Query("""
            select count(r) from Reservation r
            where r.id <> :reservationId
              and r.branchId = :branchId
              and r.contactPhoneHash = :contactPhoneHash
              and r.status in :statuses
              and r.startsAt < :endsAt
              and r.endsAt > :startsAt
            """)
    long countCustomerOverlapsExcluding(
            @Param("reservationId") UUID reservationId,
            @Param("branchId") UUID branchId,
            @Param("contactPhoneHash") String contactPhoneHash,
            @Param("startsAt") Instant startsAt,
            @Param("endsAt") Instant endsAt,
            @Param("statuses") Set<ReservationStatus> statuses);
}
