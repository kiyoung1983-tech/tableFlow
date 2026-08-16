package com.example.fullstack.reservation;

import com.example.fullstack.common.error.ApiException;
import com.example.fullstack.common.error.ResourceNotFoundException;
import com.example.fullstack.common.web.PageResponse;
import com.example.fullstack.restaurant.Branch;
import com.example.fullstack.restaurant.BranchRepository;
import com.example.fullstack.restaurant.DiningTable;
import com.example.fullstack.restaurant.DiningTableRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AdminReservationService {
    private final ReservationRepository reservations;
    private final ReservationStatusHistoryRepository history;
    private final BranchRepository branches;
    private final DiningTableRepository tables;
    private final ReservationCryptoService crypto;
    private final ReservationTransitionPolicy transitionPolicy = new ReservationTransitionPolicy();
    private final Clock clock;

    AdminReservationService(
            ReservationRepository reservations,
            ReservationStatusHistoryRepository history,
            BranchRepository branches,
            DiningTableRepository tables,
            ReservationCryptoService crypto,
            Clock clock) {
        this.reservations = reservations;
        this.history = history;
        this.branches = branches;
        this.tables = tables;
        this.crypto = crypto;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    PageResponse<AdminReservationView> list(
            UUID branchId, LocalDate date, ReservationStatus status, int page, int size) {
        var branch = findBranch(branchId);
        var startsAt = date.atStartOfDay(branch.zoneId()).toInstant();
        var endsAt = date.plusDays(1).atStartOfDay(branch.zoneId()).toInstant();
        var pageable = PageRequest.of(page, size);
        var reservationPage = status == null
                ? reservations
                        .findAllByBranchIdAndStartsAtGreaterThanEqualAndStartsAtLessThanOrderByStartsAtAscCreatedAtAsc(
                                branchId, startsAt, endsAt, pageable)
                : reservations
                        .findAllByBranchIdAndStatusAndStartsAtGreaterThanEqualAndStartsAtLessThanOrderByStartsAtAscCreatedAtAsc(
                                branchId, status, startsAt, endsAt, pageable);
        var content = reservationPage.getContent();
        var tableById = loadTables(content.stream()
                .map(Reservation::diningTableId)
                .collect(Collectors.toSet()));
        var historyByReservation = loadHistory(content.stream()
                .map(Reservation::id)
                .toList());
        return PageResponse.from(reservationPage, reservation -> view(
                reservation,
                branch,
                tableById.get(reservation.diningTableId()),
                historyByReservation.getOrDefault(reservation.id(), List.of())));
    }

    @Transactional(readOnly = true)
    AdminReservationView get(UUID reservationId) {
        var reservation = findReservation(reservationId);
        var branch = findBranch(reservation.branchId());
        var table = findTable(reservation.diningTableId());
        return view(
                reservation,
                branch,
                table,
                history.findAllByReservationIdOrderByChangedAtAsc(reservation.id()));
    }

    @Transactional
    AdminReservationView transition(
            UUID reservationId,
            ReservationStatus targetStatus,
            long expectedVersion,
            String reason,
            String actorId) {
        var reservation = reservations.findByIdForUpdate(reservationId)
                .orElseThrow(() -> reservationNotFound(reservationId));
        var branch = findBranch(reservation.branchId());

        if (reservation.status() == targetStatus) {
            return currentView(reservation, branch);
        }
        if (reservation.version() != expectedVersion) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "RESERVATION_VERSION_CONFLICT",
                    "예약 정보가 변경되었습니다. 최신 정보를 다시 조회해 주세요.");
        }

        var now = clock.instant();
        try {
            transitionPolicy.validate(
                    reservation.status(),
                    targetStatus,
                    ReservationActorType.ADMIN,
                    reservation.startsAt(),
                    reservation.endsAt(),
                    now,
                    branch.zoneId(),
                    Duration.ofMinutes(branch.changeCutoffMinutes()),
                    Duration.ofMinutes(branch.noShowGraceMinutes()),
                    reason);
        } catch (ReservationTransitionRejectedException exception) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "INVALID_RESERVATION_TRANSITION",
                    exception.getMessage());
        }

        var fromStatus = reservation.status();
        reservation.transitionTo(targetStatus, reason, now);
        reservations.saveAndFlush(reservation);
        history.saveAndFlush(ReservationStatusHistory.transition(
                reservation.id(),
                fromStatus,
                targetStatus,
                ReservationActorType.ADMIN,
                actorId,
                reason,
                now));
        return currentView(reservation, branch);
    }

    private AdminReservationView currentView(Reservation reservation, Branch branch) {
        return view(
                reservation,
                branch,
                findTable(reservation.diningTableId()),
                history.findAllByReservationIdOrderByChangedAtAsc(reservation.id()));
    }

    private AdminReservationView view(
            Reservation reservation,
            Branch branch,
            DiningTable table,
            List<ReservationStatusHistory> statusHistory) {
        if (table == null) {
            throw new IllegalStateException("예약의 테이블을 찾을 수 없습니다.");
        }
        return AdminReservationView.from(
                reservation,
                branch,
                table,
                crypto.decrypt(reservation.guestNameCiphertext()),
                statusHistory);
    }

    private Map<UUID, DiningTable> loadTables(Collection<UUID> tableIds) {
        return tables.findAllById(tableIds).stream()
                .collect(Collectors.toMap(DiningTable::id, Function.identity()));
    }

    private Map<UUID, List<ReservationStatusHistory>> loadHistory(
            Collection<UUID> reservationIds) {
        if (reservationIds.isEmpty()) return Map.of();
        return history.findAllByReservationIdInOrderByChangedAtAsc(reservationIds).stream()
                .collect(Collectors.groupingBy(
                        ReservationStatusHistory::reservationId,
                        Collectors.toList()));
    }

    private Reservation findReservation(UUID id) {
        return reservations.findById(id).orElseThrow(() -> reservationNotFound(id));
    }

    private Branch findBranch(UUID id) {
        return branches.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "지점", "BRANCH_NOT_FOUND", id));
    }

    private DiningTable findTable(UUID id) {
        return tables.findById(id)
                .orElseThrow(() -> new IllegalStateException("예약의 테이블을 찾을 수 없습니다."));
    }

    private static ResourceNotFoundException reservationNotFound(UUID id) {
        return new ResourceNotFoundException("예약", "RESERVATION_NOT_FOUND", id);
    }
}
