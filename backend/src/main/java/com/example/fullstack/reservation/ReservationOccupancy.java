package com.example.fullstack.reservation;

import java.time.Instant;
import java.util.UUID;

public record ReservationOccupancy(
        UUID reservationId, UUID diningTableId, Instant startsAt, Instant occupiedUntil) {}
