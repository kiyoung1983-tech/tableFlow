package com.example.fullstack.restaurant;

import com.example.fullstack.common.error.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class RestaurantBookingPolicy {
    private final RestaurantRepository restaurants;

    RestaurantBookingPolicy(RestaurantRepository restaurants) {
        this.restaurants = restaurants;
    }

    public void requireActive(UUID restaurantId) {
        if (!restaurants.existsByIdAndStatus(restaurantId, OperationalStatus.ACTIVE)) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "BOOKING_POLICY_VIOLATION",
                    "현재 예약을 받지 않는 식당입니다.");
        }
    }
}
