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
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/admin")
class AdminScheduleController {
    private static final EnumSet<ReservationStatus> CAPACITY_HOLDING_STATUSES =
            EnumSet.of(
                    ReservationStatus.PENDING,
                    ReservationStatus.CONFIRMED,
                    ReservationStatus.SEATED,
                    ReservationStatus.COMPLETED);

    private final BranchRepository branches;
    private final DiningTableRepository tables;
    private final BusinessHourRepository businessHours;
    private final BookingBlockRepository bookingBlocks;
    private final ReservationAvailabilityReader reservations;

    AdminScheduleController(
            BranchRepository branches,
            DiningTableRepository tables,
            BusinessHourRepository businessHours,
            BookingBlockRepository bookingBlocks,
            ReservationAvailabilityReader reservations) {
        this.branches = branches;
        this.tables = tables;
        this.businessHours = businessHours;
        this.bookingBlocks = bookingBlocks;
        this.reservations = reservations;
    }

    @GetMapping("/branches/{branchId}/business-hours")
    @Transactional(readOnly = true)
    BusinessHoursResponse getBusinessHours(@PathVariable UUID branchId) {
        requireBranch(branchId);
        return new BusinessHoursResponse(businessHours
                .findAllByBranchIdOrderByDayOfWeekAscOpensAtAsc(branchId).stream()
                .map(BusinessHourResponse::from)
                .toList());
    }

    @PutMapping("/branches/{branchId}/business-hours")
    @Transactional
    BusinessHoursResponse replaceBusinessHours(
            @PathVariable UUID branchId,
            @Valid @RequestBody ReplaceBusinessHoursRequest request) {
        branches.findByIdForUpdate(branchId)
                .orElseThrow(() -> branchNotFound(branchId));
        validateBusinessHours(request.items());
        businessHours.deleteAllByBranchId(branchId);
        businessHours.flush();
        var saved = businessHours.saveAll(request.items().stream()
                .map(item -> BusinessHour.create(
                        branchId, item.dayOfWeek().shortValue(), item.opensAt(), item.closesAt()))
                .toList());
        return new BusinessHoursResponse(saved.stream()
                .sorted(Comparator.comparingInt(BusinessHour::dayOfWeek)
                        .thenComparing(BusinessHour::opensAt))
                .map(BusinessHourResponse::from)
                .toList());
    }

    @GetMapping("/branches/{branchId}/booking-blocks")
    @Transactional(readOnly = true)
    List<BookingBlockResponse> listBookingBlocks(
            @PathVariable UUID branchId,
            @RequestParam Instant startsAt,
            @RequestParam Instant endsAt) {
        requireBranch(branchId);
        validateInterval(startsAt, endsAt);
        return bookingBlocks.findOverlapping(branchId, startsAt, endsAt).stream()
                .map(BookingBlockResponse::from)
                .toList();
    }

    @PostMapping("/branches/{branchId}/booking-blocks")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    BookingBlockResponse createBookingBlock(
            @PathVariable UUID branchId,
            @Valid @RequestBody CreateBookingBlockRequest request) {
        requireBranch(branchId);
        validateInterval(request.startsAt(), request.endsAt());
        if (request.diningTableId() == null) {
            tables.findAllByBranchIdForUpdate(branchId);
            if (reservations.countBranchOccupancyConflicts(
                            branchId,
                            request.startsAt(),
                            request.endsAt(),
                            CAPACITY_HOLDING_STATUSES)
                    > 0) {
                throw blockConflict();
            }
        } else {
            tables.findByIdAndBranchIdForUpdate(request.diningTableId(), branchId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "테이블", "DINING_TABLE_NOT_FOUND", request.diningTableId()));
            if (reservations.countTableOccupancyConflicts(
                            branchId,
                            request.diningTableId(),
                            request.startsAt(),
                            request.endsAt(),
                            CAPACITY_HOLDING_STATUSES)
                    > 0) {
                throw blockConflict();
            }
        }
        return BookingBlockResponse.from(bookingBlocks.save(BookingBlock.create(
                branchId,
                request.diningTableId(),
                request.startsAt(),
                request.endsAt(),
                request.reason())));
    }

    @DeleteMapping("/booking-blocks/{bookingBlockId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void deleteBookingBlock(@PathVariable UUID bookingBlockId) {
        var block = bookingBlocks.findById(bookingBlockId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "차단 시간", "BOOKING_BLOCK_NOT_FOUND", bookingBlockId));
        if (block.diningTableId() == null) {
            tables.findAllByBranchIdForUpdate(block.branchId());
        } else {
            tables.findByIdAndBranchIdForUpdate(block.diningTableId(), block.branchId());
        }
        bookingBlocks.delete(block);
    }

    private void requireBranch(UUID branchId) {
        if (!branches.existsById(branchId)) throw branchNotFound(branchId);
    }

    private static ResourceNotFoundException branchNotFound(UUID id) {
        return new ResourceNotFoundException("지점", "BRANCH_NOT_FOUND", id);
    }

    private static void validateBusinessHours(List<BusinessHourRequest> items) {
        for (var item : items) {
            if (item.opensAt().getSecond() != 0 || item.opensAt().getNano() != 0
                    || item.closesAt().getSecond() != 0 || item.closesAt().getNano() != 0) {
                throw policyViolation("영업시간은 분 단위로 입력해 주세요.");
            }
            if (!item.opensAt().isBefore(item.closesAt())) {
                throw policyViolation("영업 시작 시각은 종료 시각보다 빨라야 합니다.");
            }
        }
        var byDay = items.stream().collect(java.util.stream.Collectors.groupingBy(
                BusinessHourRequest::dayOfWeek));
        for (var entries : byDay.values()) {
            var sorted = entries.stream()
                    .sorted(Comparator.comparing(BusinessHourRequest::opensAt))
                    .toList();
            for (int index = 1; index < sorted.size(); index++) {
                if (sorted.get(index).opensAt().isBefore(sorted.get(index - 1).closesAt())) {
                    throw policyViolation("같은 요일의 영업 구간은 서로 겹칠 수 없습니다.");
                }
            }
        }
    }

    private static void validateInterval(Instant startsAt, Instant endsAt) {
        if (!startsAt.isBefore(endsAt)) {
            throw policyViolation("시작 시각은 종료 시각보다 빨라야 합니다.");
        }
    }

    private static ApiException blockConflict() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "BOOKING_BLOCK_RESERVATION_CONFLICT",
                "차단 시간과 겹치는 활성 예약이 있습니다.");
    }

    private static ApiException policyViolation(String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "BOOKING_POLICY_VIOLATION", detail);
    }

    record ReplaceBusinessHoursRequest(
            @NotNull @Size(max = 28) List<@Valid BusinessHourRequest> items) {}

    record BusinessHourRequest(
            @NotNull @Min(1) @Max(7) Integer dayOfWeek,
            @NotNull LocalTime opensAt,
            @NotNull LocalTime closesAt) {}

    record BusinessHoursResponse(List<BusinessHourResponse> items) {}

    record BusinessHourResponse(UUID id, short dayOfWeek, LocalTime opensAt, LocalTime closesAt) {
        static BusinessHourResponse from(BusinessHour businessHour) {
            return new BusinessHourResponse(
                    businessHour.id(), businessHour.dayOfWeek(),
                    businessHour.opensAt(), businessHour.closesAt());
        }
    }

    record CreateBookingBlockRequest(
            UUID diningTableId,
            @NotNull Instant startsAt,
            @NotNull Instant endsAt,
            @NotBlank @Size(max = 500) String reason) {}

    record BookingBlockResponse(
            UUID id,
            UUID branchId,
            UUID diningTableId,
            Instant startsAt,
            Instant endsAt,
            String reason,
            Instant createdAt) {
        static BookingBlockResponse from(BookingBlock block) {
            return new BookingBlockResponse(
                    block.id(), block.branchId(), block.diningTableId(), block.startsAt(),
                    block.endsAt(), block.reason(), block.createdAtValue());
        }
    }
}
