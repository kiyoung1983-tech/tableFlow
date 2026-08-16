package com.example.fullstack.restaurant;

import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/booking-context")
class PublicBookingController {
    private final RestaurantRepository restaurants;
    private final BranchRepository branches;
    private final String privacyPolicyVersion;
    private final int privacyRetentionDays;

    PublicBookingController(
            RestaurantRepository restaurants,
            BranchRepository branches,
            @Value("${app.reservation.privacy-policy-version}") String privacyPolicyVersion,
            @Value("${app.reservation.retention.days:365}") int privacyRetentionDays) {
        this.restaurants = restaurants;
        this.branches = branches;
        this.privacyPolicyVersion = privacyPolicyVersion;
        this.privacyRetentionDays = privacyRetentionDays;
    }

    @GetMapping
    @Transactional(readOnly = true)
    BookingContextResponse getBookingContext() {
        var activeRestaurants = restaurants.findAllByStatusOrderByNameAsc(OperationalStatus.ACTIVE);
        Map<UUID, Restaurant> restaurantById = activeRestaurants.stream()
                .collect(Collectors.toMap(Restaurant::id, Function.identity()));
        var activeBranches = activeRestaurants.isEmpty()
                ? java.util.List.<Branch>of()
                : branches.findAllByRestaurantIdInAndStatusOrderByNameAsc(
                        activeRestaurants.stream().map(Restaurant::id).toList(),
                        OperationalStatus.ACTIVE);
        var publicBranches = activeBranches.stream()
                .map(branch -> PublicBranchResponse.from(
                        branch, restaurantById.get(branch.restaurantId())))
                .sorted(Comparator.comparing(PublicBranchResponse::restaurantName)
                        .thenComparing(PublicBranchResponse::name))
                .toList();
        return new BookingContextResponse(
                privacyPolicyVersion, privacyRetentionDays, publicBranches);
    }

    record BookingContextResponse(
            String privacyPolicyVersion,
            int privacyRetentionDays,
            java.util.List<PublicBranchResponse> branches) {}

    record PublicBranchResponse(
            UUID id,
            String restaurantName,
            String name,
            String address,
            String timezone,
            int bookingHorizonDays,
            int maxPartySize) {
        static PublicBranchResponse from(Branch branch, Restaurant restaurant) {
            if (restaurant == null) {
                throw new IllegalStateException("활성 지점의 식당을 찾을 수 없습니다.");
            }
            return new PublicBranchResponse(
                    branch.id(),
                    restaurant.name(),
                    branch.name(),
                    branch.address(),
                    branch.timezone(),
                    branch.bookingHorizonDays(),
                    branch.maxPartySize());
        }
    }
}
