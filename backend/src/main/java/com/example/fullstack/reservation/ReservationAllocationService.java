package com.example.fullstack.reservation;

import com.example.fullstack.common.error.ApiException;
import com.example.fullstack.restaurant.BookingBlock;
import com.example.fullstack.restaurant.BookingBlockRepository;
import com.example.fullstack.restaurant.Branch;
import com.example.fullstack.restaurant.BusinessHourRepository;
import com.example.fullstack.restaurant.DiningTable;
import com.example.fullstack.restaurant.DiningTableRepository;
import com.example.fullstack.restaurant.OperationalStatus;
import com.example.fullstack.restaurant.RestaurantBookingPolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
class ReservationAllocationService {
    static final EnumSet<ReservationStatus> CAPACITY_HOLDING_STATUSES =
            EnumSet.of(
                    ReservationStatus.PENDING,
                    ReservationStatus.CONFIRMED,
                    ReservationStatus.SEATED,
                    ReservationStatus.COMPLETED);

    private final ReservationAvailabilityReader availabilityReader;
    private final DiningTableRepository tables;
    private final BusinessHourRepository businessHours;
    private final BookingBlockRepository bookingBlocks;
    private final RestaurantBookingPolicy restaurantBookingPolicy;
    private final Clock clock;

    ReservationAllocationService(
            ReservationAvailabilityReader availabilityReader,
            DiningTableRepository tables,
            BusinessHourRepository businessHours,
            BookingBlockRepository bookingBlocks,
            RestaurantBookingPolicy restaurantBookingPolicy,
            Clock clock) {
        this.availabilityReader = availabilityReader;
        this.tables = tables;
        this.businessHours = businessHours;
        this.bookingBlocks = bookingBlocks;
        this.restaurantBookingPolicy = restaurantBookingPolicy;
        this.clock = clock;
    }

    Allocation allocate(
            Branch branch, Instant requestedStart, int partySize, UUID excludedReservationId) {
        var interval = validatePolicyAndCalculateInterval(
                branch, requestedStart, partySize, clock.instant());
        var candidates = tables.findAllByBranchIdForUpdate(branch.id()).stream()
                .filter(DiningTable::enabled)
                .filter(table -> table.capacity() >= partySize)
                .filter(table -> table.capacity() - partySize <= branch.maxCapacityGap())
                .toList();
        if (candidates.isEmpty()) throw noTableAvailable();

        var blocks = bookingBlocks.findOverlapping(
                branch.id(), interval.startsAt(), interval.occupiedUntil());
        var occupancies = availabilityReader.findOccupancies(
                branch.id(),
                interval.startsAt(),
                interval.occupiedUntil(),
                CAPACITY_HOLDING_STATUSES);
        var selectedTable = candidates.stream()
                .filter(table -> isAvailable(
                        table, blocks, occupancies, interval, excludedReservationId))
                .findFirst()
                .orElseThrow(ReservationAllocationService::noTableAvailable);
        return new Allocation(
                selectedTable,
                interval.startsAt(),
                interval.endsAt(),
                interval.occupiedUntil());
    }

    private BookingInterval validatePolicyAndCalculateInterval(
            Branch branch, Instant requestedStart, int partySize, Instant now) {
        if (branch.status() != OperationalStatus.ACTIVE) {
            throw policyViolation("현재 예약을 받지 않는 지점입니다.");
        }
        restaurantBookingPolicy.requireActive(branch.restaurantId());
        if (partySize < 1 || partySize > branch.maxPartySize()) {
            throw policyViolation("예약 인원은 1명 이상 지점 최대 인원 이하여야 합니다.");
        }
        var zone = branch.zoneId();
        var localStart = requestedStart.atZone(zone).toLocalDateTime();
        var validOffsets = zone.getRules().getValidOffsets(localStart);
        if (validOffsets.size() != 1) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_LOCAL_TIME",
                    "지점 시간대에 존재하지 않거나 모호한 예약 시각입니다.");
        }
        if (localStart.getSecond() != 0
                || localStart.getNano() != 0
                || localStart.toLocalTime().toSecondOfDay() / 60
                        % branch.slotIntervalMinutes()
                        != 0) {
            throw policyViolation("예약 시작 시각이 지점 슬롯 간격과 맞지 않습니다.");
        }
        var today = now.atZone(zone).toLocalDate();
        var reservationDate = localStart.toLocalDate();
        if (reservationDate.isBefore(today)
                || reservationDate.isAfter(today.plusDays(branch.bookingHorizonDays()))) {
            throw policyViolation("예약 가능 기간을 벗어났습니다.");
        }
        if (requestedStart.isBefore(now.plusSeconds(branch.minAdvanceMinutes() * 60L))) {
            throw policyViolation("최소 사전 예약 시간을 충족하지 않습니다.");
        }

        var localEnd = localStart.plusMinutes(branch.defaultDurationMinutes());
        var endOffsets = zone.getRules().getValidOffsets(localEnd);
        if (endOffsets.size() != 1) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_LOCAL_TIME",
                    "지점 시간대에 존재하지 않거나 모호한 예약 종료 시각입니다.");
        }
        var insideBusinessHours = businessHours
                .findAllByBranchIdAndDayOfWeekOrderByOpensAtAsc(
                        branch.id(), (short) reservationDate.getDayOfWeek().getValue())
                .stream()
                .anyMatch(hours -> !localStart.toLocalTime().isBefore(hours.opensAt())
                        && !localEnd.toLocalTime().isAfter(hours.closesAt())
                        && localEnd.toLocalDate().equals(reservationDate));
        if (!insideBusinessHours) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "OUTSIDE_BUSINESS_HOURS",
                    "예약 이용 구간이 지점 영업시간 안에 있어야 합니다.");
        }
        var endsAt = localEnd.toInstant(endOffsets.getFirst());
        return new BookingInterval(
                requestedStart,
                endsAt,
                endsAt.plusSeconds(branch.bufferMinutes() * 60L));
    }

    private static boolean isAvailable(
            DiningTable table,
            List<BookingBlock> blocks,
            List<ReservationOccupancy> occupancies,
            BookingInterval interval,
            UUID excludedReservationId) {
        var blocked = blocks.stream().anyMatch(block ->
                (block.diningTableId() == null || block.diningTableId().equals(table.id()))
                        && overlaps(
                                interval.startsAt(),
                                interval.occupiedUntil(),
                                block.startsAt(),
                                block.endsAt()));
        var reserved = occupancies.stream()
                .filter(occupancy -> !occupancy.reservationId().equals(excludedReservationId))
                .anyMatch(occupancy -> occupancy.diningTableId().equals(table.id())
                        && overlaps(
                                interval.startsAt(),
                                interval.occupiedUntil(),
                                occupancy.startsAt(),
                                occupancy.occupiedUntil()));
        return !blocked && !reserved;
    }

    private static boolean overlaps(
            Instant firstStart, Instant firstEnd, Instant secondStart, Instant secondEnd) {
        return firstStart.isBefore(secondEnd) && secondStart.isBefore(firstEnd);
    }

    private static ApiException noTableAvailable() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "NO_TABLE_AVAILABLE",
                "요청한 시간과 인원에 배정할 수 있는 테이블이 없습니다.");
    }

    private static ApiException policyViolation(String detail) {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT, "BOOKING_POLICY_VIOLATION", detail);
    }

    record Allocation(
            DiningTable table, Instant startsAt, Instant endsAt, Instant occupiedUntil) {}

    private record BookingInterval(Instant startsAt, Instant endsAt, Instant occupiedUntil) {}
}
