package com.example.fullstack.restaurant;

import com.example.fullstack.common.error.ApiException;
import com.example.fullstack.common.error.ResourceNotFoundException;
import com.example.fullstack.reservation.ReservationAvailabilityReader;
import com.example.fullstack.reservation.ReservationOccupancy;
import com.example.fullstack.reservation.ReservationStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AvailabilityService {
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
    private final RestaurantBookingPolicy restaurantBookingPolicy;
    private final Clock clock;

    AvailabilityService(
            BranchRepository branches,
            DiningTableRepository tables,
            BusinessHourRepository businessHours,
            BookingBlockRepository bookingBlocks,
            ReservationAvailabilityReader reservations,
            RestaurantBookingPolicy restaurantBookingPolicy,
            Clock clock) {
        this.branches = branches;
        this.tables = tables;
        this.businessHours = businessHours;
        this.bookingBlocks = bookingBlocks;
        this.reservations = reservations;
        this.restaurantBookingPolicy = restaurantBookingPolicy;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    AvailabilityResult find(UUID branchId, LocalDate date, int partySize) {
        var branch = branches.findById(branchId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "지점", "BRANCH_NOT_FOUND", branchId));
        if (branch.status() != OperationalStatus.ACTIVE) {
            throw policyViolation("현재 예약을 받지 않는 지점입니다.");
        }
        restaurantBookingPolicy.requireActive(branch.restaurantId());
        if (partySize < 1 || partySize > branch.maxPartySize()) {
            throw policyViolation("예약 인원은 1명 이상 지점 최대 인원 이하여야 합니다.");
        }

        var now = clock.instant();
        var zone = branch.zoneId();
        var today = now.atZone(zone).toLocalDate();
        if (date.isBefore(today) || date.isAfter(today.plusDays(branch.bookingHorizonDays()))) {
            throw policyViolation("예약 가능 기간을 벗어났습니다.");
        }

        var candidateTables = tables
                .findAllByBranchIdAndEnabledTrueOrderByCapacityAscNameAsc(branchId).stream()
                .filter(table -> table.capacity() >= partySize)
                .filter(table -> table.capacity() - partySize <= branch.maxCapacityGap())
                .toList();
        var hours = businessHours.findAllByBranchIdAndDayOfWeekOrderByOpensAtAsc(
                branchId, (short) date.getDayOfWeek().getValue());

        var dayStart = date.atStartOfDay(zone).toInstant();
        var queryEnd = date.plusDays(2).atStartOfDay(zone).toInstant();
        var blocks = bookingBlocks.findOverlapping(branchId, dayStart, queryEnd);
        var occupancies = reservations.findOccupancies(
                branchId, dayStart, queryEnd, CAPACITY_HOLDING_STATUSES);
        var slotsByStart = new TreeMap<Instant, AvailabilitySlot>();

        for (var hour : hours) {
            addSlots(
                    branch,
                    date,
                    hour,
                    candidateTables,
                    blocks,
                    occupancies,
                    now,
                    slotsByStart);
        }
        return new AvailabilityResult(
                branch.id(), date, branch.timezone(), partySize, now, List.copyOf(slotsByStart.values()));
    }

    private static void addSlots(
            Branch branch,
            LocalDate date,
            BusinessHour hour,
            List<DiningTable> tables,
            List<BookingBlock> blocks,
            List<ReservationOccupancy> occupancies,
            Instant now,
            TreeMap<Instant, AvailabilitySlot> slots) {
        var alignedStart = alignUp(hour.opensAt(), branch.slotIntervalMinutes());
        if (alignedStart.isEmpty()) return;
        var localStart = alignedStart.orElseThrow();
        while (!localStart.plusMinutes(branch.defaultDurationMinutes()).isAfter(hour.closesAt())) {
            var localEnd = localStart.plusMinutes(branch.defaultDurationMinutes());
            var startInstant = strictInstant(date.atTime(localStart), branch.zoneId());
            var endInstant = strictInstant(date.atTime(localEnd), branch.zoneId());
            if (startInstant.isPresent() && endInstant.isPresent()) {
                var startsAt = startInstant.orElseThrow();
                var endsAt = endInstant.orElseThrow();
                var occupiedUntil = endsAt.plusSeconds(branch.bufferMinutes() * 60L);
                if (!startsAt.isBefore(now.plusSeconds(branch.minAdvanceMinutes() * 60L))) {
                    var availableCount = countAvailableTables(
                            tables, blocks, occupancies, startsAt, occupiedUntil);
                    if (availableCount > 0) {
                        slots.putIfAbsent(
                                startsAt,
                                new AvailabilitySlot(startsAt, endsAt, availableCount));
                    }
                }
            }
            var nextStart = localStart.plusMinutes(branch.slotIntervalMinutes());
            if (!nextStart.isAfter(localStart)) return;
            localStart = nextStart;
        }
    }

    private static int countAvailableTables(
            List<DiningTable> tables,
            List<BookingBlock> blocks,
            List<ReservationOccupancy> occupancies,
            Instant startsAt,
            Instant occupiedUntil) {
        var count = 0;
        for (var table : tables) {
            var blocked = blocks.stream().anyMatch(block ->
                    (block.diningTableId() == null || block.diningTableId().equals(table.id()))
                            && overlaps(startsAt, occupiedUntil, block.startsAt(), block.endsAt()));
            var reserved = occupancies.stream().anyMatch(occupancy ->
                    occupancy.diningTableId().equals(table.id())
                            && overlaps(
                                    startsAt,
                                    occupiedUntil,
                                    occupancy.startsAt(),
                                    occupancy.occupiedUntil()));
            if (!blocked && !reserved) count++;
        }
        return count;
    }

    private static boolean overlaps(
            Instant firstStart, Instant firstEnd, Instant secondStart, Instant secondEnd) {
        return firstStart.isBefore(secondEnd) && secondStart.isBefore(firstEnd);
    }

    private static Optional<LocalTime> alignUp(LocalTime time, int intervalMinutes) {
        var minuteOfDay = time.toSecondOfDay() / 60;
        var remainder = minuteOfDay % intervalMinutes;
        var alignedMinute = remainder == 0
                ? minuteOfDay
                : minuteOfDay + intervalMinutes - remainder;
        if (alignedMinute >= 1440) return Optional.empty();
        return Optional.of(LocalTime.MIN.plusMinutes(alignedMinute));
    }

    private static Optional<Instant> strictInstant(LocalDateTime localDateTime, ZoneId zone) {
        var offsets = zone.getRules().getValidOffsets(localDateTime);
        if (offsets.size() != 1) return Optional.empty();
        return Optional.of(localDateTime.toInstant(offsets.getFirst()));
    }

    private static ApiException policyViolation(String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "BOOKING_POLICY_VIOLATION", detail);
    }

    record AvailabilityResult(
            UUID branchId,
            LocalDate date,
            String timezone,
            int partySize,
            Instant asOf,
            List<AvailabilitySlot> slots) {}

    record AvailabilitySlot(Instant startsAt, Instant endsAt, int availableTableCount) {}
}
