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
import java.util.UUID;

@Entity
@Table(name = "restaurants")
public class Restaurant extends BaseTimeEntity {
    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OperationalStatus status;

    @Version
    @Column(nullable = false)
    private long version;

    protected Restaurant() {}

    static Restaurant create(String name) {
        var restaurant = new Restaurant();
        restaurant.id = UUID.randomUUID();
        restaurant.name = name.strip();
        restaurant.status = OperationalStatus.ACTIVE;
        return restaurant;
    }

    void update(String name, OperationalStatus status) {
        if (name != null) {
            this.name = name.strip();
        }
        if (status != null) {
            this.status = status;
        }
    }

    UUID id() { return id; }
    String name() { return name; }
    OperationalStatus status() { return status; }
    long version() { return version; }
    Instant createdAtValue() { return super.createdAt(); }
    Instant updatedAtValue() { return super.updatedAt(); }
}
