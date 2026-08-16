package com.example.fullstack.restaurant;

import com.example.fullstack.common.persistence.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "booking_blocks")
public class BookingBlock extends BaseTimeEntity {
    @Id
    private UUID id;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(name = "dining_table_id")
    private UUID diningTableId;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(nullable = false, length = 500)
    private String reason;

    protected BookingBlock() {}

    static BookingBlock create(
            UUID branchId, UUID diningTableId, Instant startsAt, Instant endsAt, String reason) {
        var block = new BookingBlock();
        block.id = UUID.randomUUID();
        block.branchId = branchId;
        block.diningTableId = diningTableId;
        block.startsAt = startsAt;
        block.endsAt = endsAt;
        block.reason = reason.strip();
        return block;
    }

    public UUID id() { return id; }
    public UUID branchId() { return branchId; }
    public UUID diningTableId() { return diningTableId; }
    public Instant startsAt() { return startsAt; }
    public Instant endsAt() { return endsAt; }
    public String reason() { return reason; }
    Instant createdAtValue() { return super.createdAt(); }
}
