package com.example.fullstack.restaurant;

import com.example.fullstack.common.persistence.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "business_hours")
public class BusinessHour extends BaseTimeEntity {
    @Id
    private UUID id;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(name = "day_of_week", nullable = false)
    private short dayOfWeek;

    @Column(name = "opens_at", nullable = false)
    private LocalTime opensAt;

    @Column(name = "closes_at", nullable = false)
    private LocalTime closesAt;

    protected BusinessHour() {}

    static BusinessHour create(UUID branchId, short dayOfWeek, LocalTime opensAt, LocalTime closesAt) {
        var businessHour = new BusinessHour();
        businessHour.id = UUID.randomUUID();
        businessHour.branchId = branchId;
        businessHour.dayOfWeek = dayOfWeek;
        businessHour.opensAt = opensAt;
        businessHour.closesAt = closesAt;
        return businessHour;
    }

    public UUID id() { return id; }
    public UUID branchId() { return branchId; }
    public short dayOfWeek() { return dayOfWeek; }
    public LocalTime opensAt() { return opensAt; }
    public LocalTime closesAt() { return closesAt; }
}
