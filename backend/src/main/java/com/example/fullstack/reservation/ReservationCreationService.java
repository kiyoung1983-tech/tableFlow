package com.example.fullstack.reservation;

import com.example.fullstack.common.error.ApiException;
import com.example.fullstack.common.error.ResourceNotFoundException;
import com.example.fullstack.restaurant.Branch;
import com.example.fullstack.restaurant.BranchRepository;
import com.example.fullstack.restaurant.DiningTable;
import com.example.fullstack.restaurant.DiningTableRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReservationCreationService {
    private final ReservationRepository reservations;
    private final IdempotencyLock idempotencyLock;
    private final ReservationStatusHistoryRepository history;
    private final ReservationAllocationService allocationService;
    private final BranchRepository branches;
    private final DiningTableRepository tables;
    private final ReservationCryptoService crypto;
    private final Clock clock;
    private final String currentPrivacyPolicyVersion;

    ReservationCreationService(
            ReservationRepository reservations,
            IdempotencyLock idempotencyLock,
            ReservationStatusHistoryRepository history,
            ReservationAllocationService allocationService,
            BranchRepository branches,
            DiningTableRepository tables,
            ReservationCryptoService crypto,
            Clock clock,
            @Value("${app.reservation.privacy-policy-version}") String currentPrivacyPolicyVersion) {
        this.reservations = reservations;
        this.idempotencyLock = idempotencyLock;
        this.history = history;
        this.allocationService = allocationService;
        this.branches = branches;
        this.tables = tables;
        this.crypto = crypto;
        this.clock = clock;
        this.currentPrivacyPolicyVersion = currentPrivacyPolicyVersion;
    }

    @Transactional
    ReservationCreatedResult create(UUID idempotencyKey, CreateReservationCommand command) {
        idempotencyLock.acquire(idempotencyKey);
        var guestName = command.guestName().strip();
        var normalizedPhone = crypto.normalizePhone(command.guestPhone());
        var privacyPolicyVersion = command.privacyPolicyVersion().strip();
        if (!privacyPolicyVersion.equals(currentPrivacyPolicyVersion)) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "PRIVACY_POLICY_VERSION_MISMATCH",
                    "최신 개인정보 수집 동의 내용을 확인해 주세요.");
        }
        var fingerprint = crypto.requestFingerprint(canonicalRequest(
                command, guestName, normalizedPhone, privacyPolicyVersion));
        var manageToken = crypto.manageToken(idempotencyKey);

        var existing = reservations.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.orElseThrow(), fingerprint, manageToken);
        }

        var branch = branches.findByIdForUpdate(command.branchId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "지점", "BRANCH_NOT_FOUND", command.branchId()));

        existing = reservations.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.orElseThrow(), fingerprint, manageToken);
        }

        var now = clock.instant();
        var allocation = allocationService.allocate(
                branch, command.startsAt(), command.partySize(), null);

        var phoneHash = crypto.phoneHash(normalizedPhone);
        if (reservations.countCustomerOverlaps(
                        branch.id(),
                        phoneHash,
                        allocation.startsAt(),
                        allocation.endsAt(),
                        ReservationAllocationService.CAPACITY_HOLDING_STATUSES)
                > 0) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "DUPLICATE_CUSTOMER_RESERVATION",
                    "같은 지점에 시간이 겹치는 활성 예약이 있습니다.");
        }

        var reservation = Reservation.create(
                branch.id(),
                allocation.table().id(),
                crypto.reservationCode(),
                crypto.manageTokenHash(manageToken),
                idempotencyKey,
                fingerprint,
                crypto.encrypt(guestName),
                crypto.encrypt(normalizedPhone),
                phoneHash,
                normalizedPhone.substring(normalizedPhone.length() - 4),
                command.partySize(),
                allocation.startsAt(),
                allocation.endsAt(),
                allocation.occupiedUntil(),
                privacyPolicyVersion,
                now);
        reservations.saveAndFlush(reservation);
        history.save(ReservationStatusHistory.initial(reservation.id(), now));
        return result(reservation, branch, allocation.table(), manageToken);
    }

    private ReservationCreatedResult replay(
            Reservation reservation, String fingerprint, String manageToken) {
        if (!reservation.requestFingerprint().equals(fingerprint)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_KEY_REUSED",
                    "같은 멱등성 키를 다른 예약 요청에 사용할 수 없습니다.");
        }
        var branch = branches.findById(reservation.branchId())
                .orElseThrow(() -> new IllegalStateException("예약의 지점을 찾을 수 없습니다."));
        var table = tables.findById(reservation.diningTableId())
                .orElseThrow(() -> new IllegalStateException("예약의 테이블을 찾을 수 없습니다."));
        return result(reservation, branch, table, manageToken);
    }

    private static String canonicalRequest(
            CreateReservationCommand command,
            String guestName,
            String normalizedPhone,
            String privacyPolicyVersion) {
        return canonicalPart(command.branchId().toString())
                + canonicalPart(command.startsAt().toString())
                + canonicalPart(Integer.toString(command.partySize()))
                + canonicalPart(guestName)
                + canonicalPart(normalizedPhone)
                + canonicalPart(privacyPolicyVersion);
    }

    private static String canonicalPart(String value) {
        var encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
        return encoded.length() + ":" + encoded;
    }

    private static ReservationCreatedResult result(
            Reservation reservation, Branch branch, DiningTable table, String manageToken) {
        return new ReservationCreatedResult(
                PublicReservationView.from(reservation, branch, table),
                manageToken);
    }

    record CreateReservationCommand(
            UUID branchId,
            Instant startsAt,
            int partySize,
            String guestName,
            String guestPhone,
            String privacyPolicyVersion) {}

    record ReservationCreatedResult(PublicReservationView reservation, String manageToken) {}
}
