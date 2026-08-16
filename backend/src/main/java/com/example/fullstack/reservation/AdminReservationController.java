package com.example.fullstack.reservation;

import com.example.fullstack.common.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/admin")
class AdminReservationController {
    private final AdminReservationService reservationService;
    private final AdminReservationDashboardService dashboardService;

    AdminReservationController(
            AdminReservationService reservationService,
            AdminReservationDashboardService dashboardService) {
        this.reservationService = reservationService;
        this.dashboardService = dashboardService;
    }

    @GetMapping("/branches/{branchId}/reservations")
    PageResponse<AdminReservationView> list(
            @PathVariable UUID branchId,
            @RequestParam LocalDate date,
            @RequestParam(required = false) ReservationStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return reservationService.list(branchId, date, status, page, size);
    }

    @GetMapping("/branches/{branchId}/reservation-dashboard")
    AdminReservationDashboardService.DashboardResult dashboard(
            @PathVariable UUID branchId, @RequestParam LocalDate date) {
        return dashboardService.get(branchId, date);
    }

    @GetMapping("/reservations/{reservationId}")
    AdminReservationView get(@PathVariable UUID reservationId) {
        return reservationService.get(reservationId);
    }

    @PostMapping("/reservations/{reservationId}/transitions")
    AdminReservationView transition(
            @PathVariable UUID reservationId,
            @Valid @RequestBody TransitionReservationRequest request,
            Authentication authentication) {
        return reservationService.transition(
                reservationId,
                request.targetStatus(),
                request.expectedVersion(),
                request.reason(),
                authentication.getName());
    }

    record TransitionReservationRequest(
            @NotNull ReservationStatus targetStatus,
            @NotNull @Min(0) Long expectedVersion,
            @Size(max = 500) String reason) {}
}
