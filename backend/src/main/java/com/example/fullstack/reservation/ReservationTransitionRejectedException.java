package com.example.fullstack.reservation;

public class ReservationTransitionRejectedException extends IllegalStateException {
    public ReservationTransitionRejectedException(String message) {
        super(message);
    }
}
