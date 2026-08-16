package com.example.fullstack.restaurant;

import com.example.fullstack.common.error.ApiException;
import com.example.fullstack.common.error.ResourceNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
class AdminRestaurantController {
    private final RestaurantRepository restaurants;
    private final BranchRepository branches;

    AdminRestaurantController(RestaurantRepository restaurants, BranchRepository branches) {
        this.restaurants = restaurants;
        this.branches = branches;
    }

    @PostMapping("/restaurants")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    RestaurantResponse createRestaurant(@Valid @RequestBody CreateRestaurantRequest request) {
        return RestaurantResponse.from(restaurants.save(Restaurant.create(request.name())));
    }

    @GetMapping("/restaurants")
    @Transactional(readOnly = true)
    List<RestaurantResponse> listRestaurants() {
        return restaurants.findAllByOrderByNameAsc().stream()
                .map(RestaurantResponse::from)
                .toList();
    }

    @GetMapping("/restaurants/{restaurantId}")
    @Transactional(readOnly = true)
    RestaurantResponse getRestaurant(@PathVariable UUID restaurantId) {
        return RestaurantResponse.from(findRestaurant(restaurantId));
    }

    @PatchMapping("/restaurants/{restaurantId}")
    @Transactional
    RestaurantResponse updateRestaurant(
            @PathVariable UUID restaurantId,
            @Valid @RequestBody UpdateRestaurantRequest request) {
        var restaurant = findRestaurant(restaurantId);
        requireVersion(request.expectedVersion(), restaurant.version());
        restaurant.update(request.name(), request.status());
        return RestaurantResponse.from(restaurant);
    }

    @PostMapping("/restaurants/{restaurantId}/branches")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    BranchResponse createBranch(
            @PathVariable UUID restaurantId,
            @Valid @RequestBody CreateBranchRequest request) {
        findRestaurant(restaurantId);
        validateTimezone(request.timezone());
        var branch = Branch.create(
                restaurantId, request.name(), request.address(), request.timezone());
        return BranchResponse.from(branches.save(branch));
    }

    @GetMapping("/restaurants/{restaurantId}/branches")
    @Transactional(readOnly = true)
    List<BranchResponse> listBranches(@PathVariable UUID restaurantId) {
        findRestaurant(restaurantId);
        return branches.findAllByRestaurantIdOrderByNameAsc(restaurantId).stream()
                .map(BranchResponse::from)
                .toList();
    }

    @GetMapping("/branches/{branchId}")
    @Transactional(readOnly = true)
    BranchResponse getBranch(@PathVariable UUID branchId) {
        return BranchResponse.from(findBranch(branchId));
    }

    @PatchMapping("/branches/{branchId}")
    @Transactional
    BranchResponse updateBranch(
            @PathVariable UUID branchId,
            @Valid @RequestBody UpdateBranchRequest request) {
        var branch = branches.findByIdForUpdate(branchId)
                .orElseThrow(() -> branchNotFound(branchId));
        requireVersion(request.expectedVersion(), branch.version());
        if (request.timezone() != null) validateTimezone(request.timezone());
        if (request.slotIntervalMinutes() != null
                && 1440 % request.slotIntervalMinutes() != 0) {
            throw policyViolation("슬롯 간격은 하루를 나누어떨어지게 해야 합니다.");
        }
        branch.update(
                request.name(),
                request.address(),
                request.timezone(),
                request.status(),
                request.slotIntervalMinutes(),
                request.defaultDurationMinutes(),
                request.bufferMinutes(),
                request.minAdvanceMinutes(),
                request.bookingHorizonDays(),
                request.changeCutoffMinutes(),
                request.noShowGraceMinutes(),
                request.maxPartySize(),
                request.maxCapacityGap());
        return BranchResponse.from(branch);
    }

    private Restaurant findRestaurant(UUID id) {
        return restaurants.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "식당", "RESTAURANT_NOT_FOUND", id));
    }

    private Branch findBranch(UUID id) {
        return branches.findById(id).orElseThrow(() -> branchNotFound(id));
    }

    private static ResourceNotFoundException branchNotFound(UUID id) {
        return new ResourceNotFoundException("지점", "BRANCH_NOT_FOUND", id);
    }

    private static void validateTimezone(String timezone) {
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException exception) {
            throw policyViolation("올바른 IANA 시간대를 입력해 주세요.");
        }
    }

    private static void requireVersion(long expected, long actual) {
        if (expected != actual) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "VERSION_CONFLICT",
                    "다른 요청이 먼저 데이터를 변경했습니다. 최신 정보를 다시 조회해 주세요.");
        }
    }

    private static ApiException policyViolation(String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "BOOKING_POLICY_VIOLATION", detail);
    }

    record CreateRestaurantRequest(@NotBlank @Size(max = 100) String name) {}

    record UpdateRestaurantRequest(
            @Pattern(regexp = ".*\\S.*", message = "공백만 입력할 수 없습니다.")
            @Size(max = 100) String name,
            OperationalStatus status,
            @NotNull @Min(0) Long expectedVersion) {}

    record CreateBranchRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 300) String address,
            @NotBlank @Size(max = 50) String timezone) {}

    record UpdateBranchRequest(
            @Pattern(regexp = ".*\\S.*", message = "공백만 입력할 수 없습니다.")
            @Size(max = 100) String name,
            @Pattern(regexp = ".*\\S.*", message = "공백만 입력할 수 없습니다.")
            @Size(max = 300) String address,
            @Size(min = 1, max = 50) String timezone,
            OperationalStatus status,
            @Min(5) @Max(120) Integer slotIntervalMinutes,
            @Min(15) @Max(720) Integer defaultDurationMinutes,
            @Min(0) @Max(240) Integer bufferMinutes,
            @Min(0) @Max(10080) Integer minAdvanceMinutes,
            @Min(1) @Max(365) Integer bookingHorizonDays,
            @Min(0) @Max(10080) Integer changeCutoffMinutes,
            @Min(0) @Max(1440) Integer noShowGraceMinutes,
            @Min(1) @Max(100) Integer maxPartySize,
            @Min(0) @Max(100) Integer maxCapacityGap,
            @NotNull @Min(0) Long expectedVersion) {}

    record RestaurantResponse(
            UUID id,
            String name,
            OperationalStatus status,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        static RestaurantResponse from(Restaurant restaurant) {
            return new RestaurantResponse(
                    restaurant.id(),
                    restaurant.name(),
                    restaurant.status(),
                    restaurant.version(),
                    restaurant.createdAtValue(),
                    restaurant.updatedAtValue());
        }
    }

    record BranchResponse(
            UUID id,
            UUID restaurantId,
            String name,
            String address,
            String timezone,
            OperationalStatus status,
            int slotIntervalMinutes,
            int defaultDurationMinutes,
            int bufferMinutes,
            int minAdvanceMinutes,
            int bookingHorizonDays,
            int changeCutoffMinutes,
            int noShowGraceMinutes,
            int maxPartySize,
            int maxCapacityGap,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        static BranchResponse from(Branch branch) {
            return new BranchResponse(
                    branch.id(), branch.restaurantId(), branch.name(), branch.address(),
                    branch.timezone(), branch.status(), branch.slotIntervalMinutes(),
                    branch.defaultDurationMinutes(), branch.bufferMinutes(),
                    branch.minAdvanceMinutes(), branch.bookingHorizonDays(),
                    branch.changeCutoffMinutes(), branch.noShowGraceMinutes(),
                    branch.maxPartySize(), branch.maxCapacityGap(), branch.version(),
                    branch.createdAtValue(), branch.updatedAtValue());
        }
    }
}
