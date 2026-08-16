package com.example.fullstack.restaurant;

import com.example.fullstack.common.error.ApiException;
import com.example.fullstack.common.error.ResourceNotFoundException;
import com.example.fullstack.reservation.ReservationAvailabilityReader;
import com.example.fullstack.reservation.ReservationStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
class AdminTableController {
    private static final EnumSet<ReservationStatus> CAPACITY_HOLDING_STATUSES =
            EnumSet.of(
                    ReservationStatus.PENDING,
                    ReservationStatus.CONFIRMED,
                    ReservationStatus.SEATED,
                    ReservationStatus.COMPLETED);

    private final BranchRepository branches;
    private final DiningTableRepository tables;
    private final ReservationAvailabilityReader reservations;
    private final Clock clock;

    AdminTableController(
            BranchRepository branches,
            DiningTableRepository tables,
            ReservationAvailabilityReader reservations,
            Clock clock) {
        this.branches = branches;
        this.tables = tables;
        this.reservations = reservations;
        this.clock = clock;
    }

    @GetMapping("/branches/{branchId}/tables")
    @Transactional(readOnly = true)
    List<DiningTableResponse> listTables(@PathVariable UUID branchId) {
        requireBranch(branchId);
        return tables.findAllByBranchIdOrderByCapacityAscNameAsc(branchId).stream()
                .map(DiningTableResponse::from)
                .toList();
    }

    @PostMapping("/branches/{branchId}/tables")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    DiningTableResponse createTable(
            @PathVariable UUID branchId,
            @Valid @RequestBody CreateDiningTableRequest request) {
        requireBranch(branchId);
        return DiningTableResponse.from(
                tables.save(DiningTable.create(branchId, request.name(), request.capacity())));
    }

    @PatchMapping("/tables/{tableId}")
    @Transactional
    DiningTableResponse updateTable(
            @PathVariable UUID tableId,
            @Valid @RequestBody UpdateDiningTableRequest request) {
        var table = tables.findByIdForUpdate(tableId).orElseThrow(() -> tableNotFound(tableId));
        requireVersion(request.expectedVersion(), table.version());
        if (request.capacity() != null
                && reservations.countFutureReservationsExceedingCapacity(
                                tableId,
                                request.capacity(),
                                clock.instant(),
                                CAPACITY_HOLDING_STATUSES)
                        > 0) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "TABLE_CAPACITY_CONFLICT",
                    "새 수용 인원보다 인원이 많은 향후 예약이 있습니다.");
        }
        table.update(request.name(), request.capacity(), request.enabled());
        return DiningTableResponse.from(table);
    }

    private void requireBranch(UUID branchId) {
        if (!branches.existsById(branchId)) {
            throw new ResourceNotFoundException("지점", "BRANCH_NOT_FOUND", branchId);
        }
    }

    private static ResourceNotFoundException tableNotFound(UUID id) {
        return new ResourceNotFoundException("테이블", "DINING_TABLE_NOT_FOUND", id);
    }

    private static void requireVersion(long expected, long actual) {
        if (expected != actual) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "VERSION_CONFLICT",
                    "다른 요청이 먼저 테이블을 변경했습니다. 최신 정보를 다시 조회해 주세요.");
        }
    }

    record CreateDiningTableRequest(
            @NotBlank @Size(max = 50) String name,
            @Min(1) @Max(100) int capacity) {}

    record UpdateDiningTableRequest(
            @Pattern(regexp = ".*\\S.*", message = "공백만 입력할 수 없습니다.")
            @Size(max = 50) String name,
            @Min(1) @Max(100) Integer capacity,
            Boolean enabled,
            @NotNull @Min(0) Long expectedVersion) {}

    record DiningTableResponse(
            UUID id,
            UUID branchId,
            String name,
            int capacity,
            boolean enabled,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        static DiningTableResponse from(DiningTable table) {
            return new DiningTableResponse(
                    table.id(), table.branchId(), table.name(), table.capacity(), table.enabled(),
                    table.version(), table.createdAtValue(), table.updatedAtValue());
        }
    }
}
