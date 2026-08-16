package com.example.fullstack.reservation;

import com.example.fullstack.common.error.ResourceNotFoundException;
import com.example.fullstack.restaurant.BookingBlock;
import com.example.fullstack.restaurant.BookingBlockRepository;
import com.example.fullstack.restaurant.BranchRepository;
import com.example.fullstack.restaurant.BusinessHourRepository;
import com.example.fullstack.restaurant.DiningTable;
import com.example.fullstack.restaurant.DiningTableRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AdminReservationDashboardService {
    private final ReservationRepository reservations;
    private final ReservationAvailabilityReader availabilityReader;
    private final BranchRepository branches;
    private final DiningTableRepository tables;
    private final BusinessHourRepository businessHours;
    private final BookingBlockRepository bookingBlocks;
    private final Clock clock;

    AdminReservationDashboardService(
            ReservationRepository reservations,
            ReservationAvailabilityReader availabilityReader,
            BranchRepository branches,
            DiningTableRepository tables,
            BusinessHourRepository businessHours,
            BookingBlockRepository bookingBlocks,
            Clock clock) {
        this.reservations = reservations;
        this.availabilityReader = availabilityReader;
        this.branches = branches;
        this.tables = tables;
        this.businessHours = businessHours;
        this.bookingBlocks = bookingBlocks;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    DashboardResult get(UUID branchId, LocalDate date) {
        var branch = branches.findById(branchId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "지점", "BRANCH_NOT_FOUND", branchId));
        var zone = branch.zoneId();
        var dayStart = date.atStartOfDay(zone).toInstant();
        var dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
        var dayReservations = reservations
                .findAllByBranchIdAndStartsAtGreaterThanEqualAndStartsAtLessThanOrderByStartsAtAsc(
                        branchId, dayStart, dayEnd);
        var statusCounts = new EnumMap<ReservationStatus, Long>(ReservationStatus.class);
        for (var status : ReservationStatus.values()) statusCounts.put(status, 0L);
        for (var reservation : dayReservations) {
            statusCounts.compute(reservation.status(), (ignored, count) -> count + 1);
        }

        var enabledTables = tables
                .findAllByBranchIdAndEnabledTrueOrderByCapacityAscNameAsc(branchId);
        var blocks = bookingBlocks.findOverlapping(branchId, dayStart, dayEnd);
        var occupancies = availabilityReader.findOccupancies(
                branchId,
                dayStart,
                dayEnd,
                ReservationAllocationService.CAPACITY_HOLDING_STATUSES);
        var hours = businessHours.findAllByBranchIdAndDayOfWeekOrderByOpensAtAsc(
                branchId, (short) date.getDayOfWeek().getValue());
        var timeline = new TreeMap<Instant, CapacityBucket>();
        for (var hour : hours) {
            var localStart = date.atTime(hour.opensAt());
            var localClose = date.atTime(hour.closesAt());
            while (localStart.isBefore(localClose)) {
                var localEnd = localStart.plusMinutes(branch.slotIntervalMinutes());
                if (localEnd.isAfter(localClose)) localEnd = localClose;
                var startsAt = strictInstant(localStart, zone);
                var endsAt = strictInstant(localEnd, zone);
                if (startsAt.isPresent() && endsAt.isPresent()) {
                    var bucket = capacityBucket(
                            enabledTables,
                            blocks,
                            occupancies,
                            startsAt.orElseThrow(),
                            endsAt.orElseThrow());
                    timeline.putIfAbsent(bucket.startsAt(), bucket);
                }
                localStart = localEnd;
            }
        }

        return new DashboardResult(
                branchId,
                date,
                branch.timezone(),
                clock.instant(),
                enabledTables.size(),
                new StatusCounts(
                        statusCounts.get(ReservationStatus.PENDING),
                        statusCounts.get(ReservationStatus.CONFIRMED),
                        statusCounts.get(ReservationStatus.SEATED),
                        statusCounts.get(ReservationStatus.COMPLETED),
                        statusCounts.get(ReservationStatus.CANCELLED),
                        statusCounts.get(ReservationStatus.NO_SHOW)),
                List.copyOf(timeline.values()));
    }

    private static CapacityBucket capacityBucket(
            List<DiningTable> tables,
            List<BookingBlock> blocks,
            List<ReservationOccupancy> occupancies,
            Instant startsAt,
            Instant endsAt) {
        var tableIds = tables.stream().map(DiningTable::id).collect(java.util.stream.Collectors.toSet());
        var blocked = new HashSet<UUID>();
        for (var block : blocks) {
            if (!overlaps(startsAt, endsAt, block.startsAt(), block.endsAt())) continue;
            if (block.diningTableId() == null) {
                blocked.addAll(tableIds);
            } else if (tableIds.contains(block.diningTableId())) {
                blocked.add(block.diningTableId());
            }
        }
        var occupied = new HashSet<UUID>();
        for (var occupancy : occupancies) {
            if (tableIds.contains(occupancy.diningTableId())
                    && !blocked.contains(occupancy.diningTableId())
                    && overlaps(
                            startsAt,
                            endsAt,
                            occupancy.startsAt(),
                            occupancy.occupiedUntil())) {
                occupied.add(occupancy.diningTableId());
            }
        }
        return new CapacityBucket(
                startsAt,
                endsAt,
                tables.size() - blocked.size() - occupied.size(),
                occupied.size(),
                blocked.size());
    }

    private static boolean overlaps(
            Instant firstStart, Instant firstEnd, Instant secondStart, Instant secondEnd) {
        return firstStart.isBefore(secondEnd) && secondStart.isBefore(firstEnd);
    }

    private static Optional<Instant> strictInstant(LocalDateTime localDateTime, ZoneId zone) {
        var offsets = zone.getRules().getValidOffsets(localDateTime);
        if (offsets.size() != 1) return Optional.empty();
        return Optional.of(localDateTime.toInstant(offsets.getFirst()));
    }

    record DashboardResult(
            UUID branchId,
            LocalDate date,
            String timezone,
            Instant asOf,
            int totalEnabledTables,
            StatusCounts statusCounts,
            List<CapacityBucket> capacityTimeline) {}

    record StatusCounts(
            long pending,
            long confirmed,
            long seated,
            long completed,
            long cancelled,
            long noShow) {}

    record CapacityBucket(
            Instant startsAt,
            Instant endsAt,
            int availableTableCount,
            int occupiedTableCount,
            int blockedTableCount) {}
}
