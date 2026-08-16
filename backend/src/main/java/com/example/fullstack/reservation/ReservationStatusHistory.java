package com.example.fullstack.reservation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reservation_status_history")
public class ReservationStatusHistory {
    @Id
    private UUID id;

    @Column(name = "reservation_id", nullable = false)
    private UUID reservationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20)
    private ReservationStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private ReservationStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 20)
    private ReservationActorType actorType;

    @Column(name = "actor_id", length = 100)
    private String actorId;

    @Column(length = 500)
    private String reason;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    protected ReservationStatusHistory() {}

    static ReservationStatusHistory initial(UUID reservationId, Instant changedAt) {
        var history = new ReservationStatusHistory();
        history.id = UUID.randomUUID();
        history.reservationId = reservationId;
        history.fromStatus = null;
        history.toStatus = ReservationStatus.PENDING;
        history.actorType = ReservationActorType.CUSTOMER;
        history.changedAt = changedAt;
        return history;
    }

    static ReservationStatusHistory transition(
            UUID reservationId,
            ReservationStatus fromStatus,
            ReservationStatus toStatus,
            ReservationActorType actorType,
            String actorId,
            String reason,
            Instant changedAt) {
        var history = new ReservationStatusHistory();
        history.id = UUID.randomUUID();
        history.reservationId = reservationId;
        history.fromStatus = fromStatus;
        history.toStatus = toStatus;
        history.actorType = actorType;
        history.actorId = actorId;
        history.reason = reason == null || reason.isBlank() ? null : reason.strip();
        history.changedAt = changedAt;
        return history;
    }

    public UUID reservationId() { return reservationId; }
    public ReservationStatus fromStatus() { return fromStatus; }
    public ReservationStatus toStatus() { return toStatus; }
    public ReservationActorType actorType() { return actorType; }
    public String actorId() { return actorId; }
    public String reason() { return reason; }
    public Instant changedAt() { return changedAt; }
}
