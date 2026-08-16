package com.example.fullstack.reservation;

import java.util.EnumSet;
import java.util.Set;

public enum ReservationStatus {
    PENDING,
    CONFIRMED,
    SEATED,
    COMPLETED,
    CANCELLED,
    NO_SHOW;

    private static final Set<ReservationStatus> CAPACITY_HOLDING =
            EnumSet.of(PENDING, CONFIRMED, SEATED, COMPLETED);

    public boolean holdsRecordedCapacity() {
        return CAPACITY_HOLDING.contains(this);
    }
}
