package com.example.fullstack.reservation;

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
@Table(name = "reservations")
public class Reservation extends BaseTimeEntity {
    @Id
    private UUID id;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(name = "dining_table_id", nullable = false)
    private UUID diningTableId;

    @Column(name = "reservation_code", nullable = false, length = 10)
    private String reservationCode;

    @Column(name = "manage_token_hash", nullable = false, length = 64)
    private String manageTokenHash;

    @Column(name = "idempotency_key", nullable = false)
    private UUID idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "guest_name_ciphertext", nullable = false, columnDefinition = "text")
    private String guestNameCiphertext;

    @Column(name = "guest_phone_ciphertext", nullable = false, columnDefinition = "text")
    private String guestPhoneCiphertext;

    @Column(name = "contact_phone_hash", nullable = false, length = 64)
    private String contactPhoneHash;

    @Column(name = "phone_last_four", nullable = false, length = 4)
    private String phoneLastFour;

    @Column(name = "party_size", nullable = false)
    private int partySize;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "occupied_until", nullable = false)
    private Instant occupiedUntil;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "privacy_policy_version", nullable = false, length = 50)
    private String privacyPolicyVersion;

    @Column(name = "privacy_agreed_at", nullable = false)
    private Instant privacyAgreedAt;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "personal_data_erased_at")
    private Instant personalDataErasedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected Reservation() {}

    static Reservation create(
            UUID branchId,
            UUID diningTableId,
            String reservationCode,
            String manageTokenHash,
            UUID idempotencyKey,
            String requestFingerprint,
            String guestNameCiphertext,
            String guestPhoneCiphertext,
            String contactPhoneHash,
            String phoneLastFour,
            int partySize,
            Instant startsAt,
            Instant endsAt,
            Instant occupiedUntil,
            String privacyPolicyVersion,
            Instant privacyAgreedAt) {
        var reservation = new Reservation();
        reservation.id = UUID.randomUUID();
        reservation.branchId = branchId;
        reservation.diningTableId = diningTableId;
        reservation.reservationCode = reservationCode;
        reservation.manageTokenHash = manageTokenHash;
        reservation.idempotencyKey = idempotencyKey;
        reservation.requestFingerprint = requestFingerprint;
        reservation.guestNameCiphertext = guestNameCiphertext;
        reservation.guestPhoneCiphertext = guestPhoneCiphertext;
        reservation.contactPhoneHash = contactPhoneHash;
        reservation.phoneLastFour = phoneLastFour;
        reservation.partySize = partySize;
        reservation.startsAt = startsAt;
        reservation.endsAt = endsAt;
        reservation.occupiedUntil = occupiedUntil;
        reservation.status = ReservationStatus.PENDING;
        reservation.privacyPolicyVersion = privacyPolicyVersion;
        reservation.privacyAgreedAt = privacyAgreedAt;
        return reservation;
    }

    void reschedule(
            UUID diningTableId,
            int partySize,
            Instant startsAt,
            Instant endsAt,
            Instant occupiedUntil) {
        this.diningTableId = diningTableId;
        this.partySize = partySize;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.occupiedUntil = occupiedUntil;
    }

    void cancel(String reason, Instant cancelledAt) {
        this.status = ReservationStatus.CANCELLED;
        this.cancellationReason = reason == null || reason.isBlank() ? null : reason.strip();
        this.cancelledAt = cancelledAt;
    }

    void transitionTo(ReservationStatus targetStatus, String reason, Instant changedAt) {
        if (targetStatus == ReservationStatus.CANCELLED) {
            cancel(reason, changedAt);
            return;
        }
        this.status = targetStatus;
    }

    void erasePersonalData(
            String guestNameCiphertext,
            String guestPhoneCiphertext,
            String contactPhoneHash,
            String manageTokenHash,
            String requestFingerprint,
            Instant erasedAt) {
        if (personalDataErasedAt != null) return;
        this.guestNameCiphertext = guestNameCiphertext;
        this.guestPhoneCiphertext = guestPhoneCiphertext;
        this.contactPhoneHash = contactPhoneHash;
        this.phoneLastFour = "0000";
        this.manageTokenHash = manageTokenHash;
        this.requestFingerprint = requestFingerprint;
        this.personalDataErasedAt = erasedAt;
    }

    void rotatePersonalDataEncryption(
            String guestNameCiphertext, String guestPhoneCiphertext) {
        this.guestNameCiphertext = guestNameCiphertext;
        this.guestPhoneCiphertext = guestPhoneCiphertext;
    }

    public UUID id() { return id; }
    public UUID branchId() { return branchId; }
    public UUID diningTableId() { return diningTableId; }
    public String reservationCode() { return reservationCode; }
    public String manageTokenHash() { return manageTokenHash; }
    public UUID idempotencyKey() { return idempotencyKey; }
    public String requestFingerprint() { return requestFingerprint; }
    String guestNameCiphertext() { return guestNameCiphertext; }
    String guestPhoneCiphertext() { return guestPhoneCiphertext; }
    public String contactPhoneHash() { return contactPhoneHash; }
    public String phoneLastFour() { return phoneLastFour; }
    public int partySize() { return partySize; }
    public Instant startsAt() { return startsAt; }
    public Instant endsAt() { return endsAt; }
    public Instant occupiedUntil() { return occupiedUntil; }
    public ReservationStatus status() { return status; }
    public Instant personalDataErasedAt() { return personalDataErasedAt; }
    public long version() { return version; }
    public Instant createdAtValue() { return super.createdAt(); }
    public Instant updatedAtValue() { return super.updatedAt(); }
}
