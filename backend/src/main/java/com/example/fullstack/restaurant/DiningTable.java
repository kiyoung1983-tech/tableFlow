package com.example.fullstack.restaurant;

import com.example.fullstack.common.persistence.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "dining_tables")
public class DiningTable extends BaseTimeEntity {
    @Id
    private UUID id;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false)
    private int capacity;

    @Column(nullable = false)
    private boolean enabled;

    @Version
    @Column(nullable = false)
    private long version;

    protected DiningTable() {}

    static DiningTable create(UUID branchId, String name, int capacity) {
        var table = new DiningTable();
        table.id = UUID.randomUUID();
        table.branchId = branchId;
        table.name = name.strip();
        table.capacity = capacity;
        table.enabled = true;
        return table;
    }

    void update(String name, Integer capacity, Boolean enabled) {
        if (name != null) this.name = name.strip();
        if (capacity != null) this.capacity = capacity;
        if (enabled != null) this.enabled = enabled;
    }

    public UUID id() { return id; }
    public UUID branchId() { return branchId; }
    public String name() { return name; }
    public int capacity() { return capacity; }
    public boolean enabled() { return enabled; }
    public long version() { return version; }
    Instant createdAtValue() { return super.createdAt(); }
    Instant updatedAtValue() { return super.updatedAt(); }
}
