package com.example.fullstack.restaurant;

import com.example.fullstack.common.persistence.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

@Entity
@Table(name = "branches")
public class Branch extends BaseTimeEntity {
    @Id
    private UUID id;

    @Column(name = "restaurant_id", nullable = false)
    private UUID restaurantId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 300)
    private String address;

    @Column(nullable = false, length = 50)
    private String timezone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OperationalStatus status;

    @Column(name = "slot_interval_minutes", nullable = false)
    private int slotIntervalMinutes;

    @Column(name = "default_duration_minutes", nullable = false)
    private int defaultDurationMinutes;

    @Column(name = "buffer_minutes", nullable = false)
    private int bufferMinutes;

    @Column(name = "min_advance_minutes", nullable = false)
    private int minAdvanceMinutes;

    @Column(name = "booking_horizon_days", nullable = false)
    private int bookingHorizonDays;

    @Column(name = "change_cutoff_minutes", nullable = false)
    private int changeCutoffMinutes;

    @Column(name = "no_show_grace_minutes", nullable = false)
    private int noShowGraceMinutes;

    @Column(name = "max_party_size", nullable = false)
    private int maxPartySize;

    @Column(name = "max_capacity_gap", nullable = false)
    private int maxCapacityGap;

    @Version
    @Column(nullable = false)
    private long version;

    protected Branch() {}

    static Branch create(UUID restaurantId, String name, String address, String timezone) {
        ZoneId.of(timezone);
        var branch = new Branch();
        branch.id = UUID.randomUUID();
        branch.restaurantId = restaurantId;
        branch.name = name.strip();
        branch.address = address.strip();
        branch.timezone = timezone;
        branch.status = OperationalStatus.ACTIVE;
        branch.slotIntervalMinutes = 30;
        branch.defaultDurationMinutes = 90;
        branch.bufferMinutes = 15;
        branch.minAdvanceMinutes = 60;
        branch.bookingHorizonDays = 30;
        branch.changeCutoffMinutes = 180;
        branch.noShowGraceMinutes = 15;
        branch.maxPartySize = 8;
        branch.maxCapacityGap = 2;
        return branch;
    }

    void update(
            String name,
            String address,
            String timezone,
            OperationalStatus status,
            Integer slotIntervalMinutes,
            Integer defaultDurationMinutes,
            Integer bufferMinutes,
            Integer minAdvanceMinutes,
            Integer bookingHorizonDays,
            Integer changeCutoffMinutes,
            Integer noShowGraceMinutes,
            Integer maxPartySize,
            Integer maxCapacityGap) {
        if (name != null) this.name = name.strip();
        if (address != null) this.address = address.strip();
        if (timezone != null) {
            ZoneId.of(timezone);
            this.timezone = timezone;
        }
        if (status != null) this.status = status;
        if (slotIntervalMinutes != null) this.slotIntervalMinutes = slotIntervalMinutes;
        if (defaultDurationMinutes != null) this.defaultDurationMinutes = defaultDurationMinutes;
        if (bufferMinutes != null) this.bufferMinutes = bufferMinutes;
        if (minAdvanceMinutes != null) this.minAdvanceMinutes = minAdvanceMinutes;
        if (bookingHorizonDays != null) this.bookingHorizonDays = bookingHorizonDays;
        if (changeCutoffMinutes != null) this.changeCutoffMinutes = changeCutoffMinutes;
        if (noShowGraceMinutes != null) this.noShowGraceMinutes = noShowGraceMinutes;
        if (maxPartySize != null) this.maxPartySize = maxPartySize;
        if (maxCapacityGap != null) this.maxCapacityGap = maxCapacityGap;
    }

    public UUID id() { return id; }
    public UUID restaurantId() { return restaurantId; }
    public String name() { return name; }
    public String address() { return address; }
    public String timezone() { return timezone; }
    public ZoneId zoneId() { return ZoneId.of(timezone); }
    public OperationalStatus status() { return status; }
    public int slotIntervalMinutes() { return slotIntervalMinutes; }
    public int defaultDurationMinutes() { return defaultDurationMinutes; }
    public int bufferMinutes() { return bufferMinutes; }
    public int minAdvanceMinutes() { return minAdvanceMinutes; }
    public int bookingHorizonDays() { return bookingHorizonDays; }
    public int changeCutoffMinutes() { return changeCutoffMinutes; }
    public int noShowGraceMinutes() { return noShowGraceMinutes; }
    public int maxPartySize() { return maxPartySize; }
    public int maxCapacityGap() { return maxCapacityGap; }
    public long version() { return version; }
    Instant createdAtValue() { return super.createdAt(); }
    Instant updatedAtValue() { return super.updatedAt(); }
}
