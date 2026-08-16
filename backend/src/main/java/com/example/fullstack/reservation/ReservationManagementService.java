package com.example.fullstack.reservation;

import com.example.fullstack.common.error.ApiException;
import com.example.fullstack.restaurant.BranchRepository;
import com.example.fullstack.restaurant.DiningTableRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReservationManagementService {
    private static final String DUMMY_TOKEN_HASH = "0".repeat(64);

    private final ReservationRepository reservations;
    private final ReservationStatusHistoryRepository history;
    private final ReservationAllocationService allocationService;
    private final BranchRepository branches;
    private final DiningTableRepository tables;
    private final ReservationCryptoService crypto;
    private final ReservationTransitionPolicy transitionPolicy = new ReservationTransitionPolicy();
    private final Clock clock;

    ReservationManagementService(
            ReservationRepository reservations,
            ReservationStatusHistoryRepository history,
            ReservationAllocationService allocationService,
            BranchRepository branches,
            DiningTableRepository tables,
            ReservationCryptoService crypto,
            Clock clock) {
        this.reservations = reservations;
        this.history = history;
        this.allocationService = allocationService;
        this.branches = branches;
        this.tables = tables;
        this.crypto = crypto;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    PublicReservationView get(String reservationCode, String manageToken) {
        var reservation = reservations.findByReservationCode(normalizeCode(reservationCode))
                .orElse(null);
        verifyTokenOrNotFound(reservation, manageToken);
        return view(reservation);
    }

    @Transactional
    PublicReservationView change(
            String reservationCode, String manageToken, ChangeReservationCommand command) {
        var reservation = reservations
                .findByReservationCodeForUpdate(normalizeCode(reservationCode))
                .orElse(null);
        verifyTokenOrNotFound(reservation, manageToken);
        validateVersion(reservation, command.expectedVersion());
        if (command.startsAt() == null && command.partySize() == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "변경할 예약 시각 또는 인원을 입력해 주세요.");
        }
        if (reservation.status() != ReservationStatus.PENDING
                && reservation.status() != ReservationStatus.CONFIRMED) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "RESERVATION_CHANGE_NOT_ALLOWED",
                    "대기 또는 확정 상태의 예약만 변경할 수 있습니다.");
        }

        var branch = branches.findByIdForUpdate(reservation.branchId())
                .orElseThrow(() -> new IllegalStateException("예약의 지점을 찾을 수 없습니다."));
        var now = clock.instant();
        if (now.isAfter(reservation.startsAt()
                .minusSeconds(branch.changeCutoffMinutes() * 60L))) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "RESERVATION_CHANGE_CUTOFF_PASSED",
                    "고객 예약 변경 가능 시간이 지났습니다.");
        }

        var startsAt = command.startsAt() == null ? reservation.startsAt() : command.startsAt();
        var partySize = command.partySize() == null ? reservation.partySize() : command.partySize();
        if (startsAt.equals(reservation.startsAt()) && partySize == reservation.partySize()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "현재 예약과 다른 시각 또는 인원을 입력해 주세요.");
        }

        var allocation = allocationService.allocate(
                branch, startsAt, partySize, reservation.id());
        if (reservations.countCustomerOverlapsExcluding(
                        reservation.id(),
                        branch.id(),
                        reservation.contactPhoneHash(),
                        allocation.startsAt(),
                        allocation.endsAt(),
                        ReservationAllocationService.CAPACITY_HOLDING_STATUSES)
                > 0) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "DUPLICATE_CUSTOMER_RESERVATION",
                    "같은 지점에 시간이 겹치는 활성 예약이 있습니다.");
        }

        reservation.reschedule(
                allocation.table().id(),
                partySize,
                allocation.startsAt(),
                allocation.endsAt(),
                allocation.occupiedUntil());
        reservations.saveAndFlush(reservation);
        return PublicReservationView.from(reservation, branch, allocation.table());
    }

    @Transactional
    PublicReservationView cancel(
            String reservationCode, String manageToken, CancelReservationCommand command) {
        var reservation = reservations
                .findByReservationCodeForUpdate(normalizeCode(reservationCode))
                .orElse(null);
        verifyTokenOrNotFound(reservation, manageToken);
        validateVersion(reservation, command.expectedVersion());
        var branch = branches.findById(reservation.branchId())
                .orElseThrow(() -> new IllegalStateException("예약의 지점을 찾을 수 없습니다."));
        var now = clock.instant();
        try {
            transitionPolicy.validate(
                    reservation.status(),
                    ReservationStatus.CANCELLED,
                    ReservationActorType.CUSTOMER,
                    reservation.startsAt(),
                    reservation.endsAt(),
                    now,
                    branch.zoneId(),
                    Duration.ofMinutes(branch.changeCutoffMinutes()),
                    Duration.ofMinutes(branch.noShowGraceMinutes()),
                    command.reason());
        } catch (ReservationTransitionRejectedException exception) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "RESERVATION_CANCELLATION_NOT_ALLOWED",
                    exception.getMessage());
        }

        var fromStatus = reservation.status();
        reservation.cancel(command.reason(), now);
        reservations.saveAndFlush(reservation);
        history.save(ReservationStatusHistory.transition(
                reservation.id(),
                fromStatus,
                ReservationStatus.CANCELLED,
                ReservationActorType.CUSTOMER,
                null,
                command.reason(),
                now));
        var table = tables.findById(reservation.diningTableId())
                .orElseThrow(() -> new IllegalStateException("예약의 테이블을 찾을 수 없습니다."));
        return PublicReservationView.from(reservation, branch, table);
    }

    private PublicReservationView view(Reservation reservation) {
        var branch = branches.findById(reservation.branchId())
                .orElseThrow(() -> new IllegalStateException("예약의 지점을 찾을 수 없습니다."));
        var table = tables.findById(reservation.diningTableId())
                .orElseThrow(() -> new IllegalStateException("예약의 테이블을 찾을 수 없습니다."));
        return PublicReservationView.from(reservation, branch, table);
    }

    private void verifyTokenOrNotFound(Reservation reservation, String manageToken) {
        var rawToken = manageToken == null ? "" : manageToken.strip();
        var expectedHash = reservation == null
                ? DUMMY_TOKEN_HASH
                : reservation.manageTokenHash();
        var matches = crypto.tokenMatches(rawToken, expectedHash);
        if (reservation == null || !matches) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "RESERVATION_NOT_FOUND",
                    "예약 번호 또는 관리 토큰을 확인해 주세요.");
        }
    }

    private static void validateVersion(Reservation reservation, long expectedVersion) {
        if (reservation.version() != expectedVersion) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "RESERVATION_VERSION_CONFLICT",
                    "예약 정보가 변경되었습니다. 최신 정보를 다시 조회해 주세요.");
        }
    }

    private static String normalizeCode(String reservationCode) {
        return reservationCode == null
                ? ""
                : reservationCode.strip().toUpperCase(Locale.ROOT);
    }

    record ChangeReservationCommand(Instant startsAt, Integer partySize, long expectedVersion) {}

    record CancelReservationCommand(String reason, long expectedVersion) {}
}
