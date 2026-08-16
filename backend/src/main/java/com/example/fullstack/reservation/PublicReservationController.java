package com.example.fullstack.reservation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/reservations")
class PublicReservationController {
    private final ReservationCreationService creationService;
    private final ReservationManagementService managementService;

    PublicReservationController(
            ReservationCreationService creationService,
            ReservationManagementService managementService) {
        this.creationService = creationService;
        this.managementService = managementService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ReservationCreationService.ReservationCreatedResult create(
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody CreateReservationRequest request) {
        return creationService.create(
                idempotencyKey,
                new ReservationCreationService.CreateReservationCommand(
                        request.branchId(),
                        request.startsAt(),
                        request.partySize(),
                        request.guestName(),
                        request.guestPhone(),
                        request.privacyAgreement().policyVersion()));
    }

    @GetMapping("/{reservationCode}")
    PublicReservationView get(
            @PathVariable String reservationCode,
            @RequestHeader(value = "X-Reservation-Token", required = false) String manageToken) {
        return managementService.get(reservationCode, manageToken);
    }

    @PatchMapping("/{reservationCode}")
    PublicReservationView change(
            @PathVariable String reservationCode,
            @RequestHeader(value = "X-Reservation-Token", required = false) String manageToken,
            @Valid @RequestBody ChangeReservationRequest request) {
        return managementService.change(
                reservationCode,
                manageToken,
                new ReservationManagementService.ChangeReservationCommand(
                        request.startsAt(), request.partySize(), request.expectedVersion()));
    }

    @PostMapping("/{reservationCode}/cancellation")
    PublicReservationView cancel(
            @PathVariable String reservationCode,
            @RequestHeader(value = "X-Reservation-Token", required = false) String manageToken,
            @Valid @RequestBody CancelReservationRequest request) {
        return managementService.cancel(
                reservationCode,
                manageToken,
                new ReservationManagementService.CancelReservationCommand(
                        request.reason(), request.expectedVersion()));
    }

    record CreateReservationRequest(
            @NotNull UUID branchId,
            @NotNull Instant startsAt,
            @Min(1) @Max(100) int partySize,
            @NotBlank @Size(max = 100) String guestName,
            @NotBlank @Size(max = 20) String guestPhone,
            @NotNull @Valid PrivacyAgreement privacyAgreement) {}

    record PrivacyAgreement(
            @AssertTrue(message = "개인정보 수집에 동의해야 합니다.") boolean agreed,
            @NotBlank @Size(max = 50) String policyVersion) {}

    record ChangeReservationRequest(
            Instant startsAt,
            @Min(1) @Max(100) Integer partySize,
            @NotNull @Min(0) Long expectedVersion) {}

    record CancelReservationRequest(
            @Size(max = 500) String reason,
            @NotNull @Min(0) Long expectedVersion) {}
}
