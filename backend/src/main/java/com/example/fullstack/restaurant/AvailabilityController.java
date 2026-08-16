package com.example.fullstack.restaurant;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/public/branches")
class AvailabilityController {
    private final AvailabilityService availability;

    AvailabilityController(AvailabilityService availability) {
        this.availability = availability;
    }

    @GetMapping("/{branchId}/availability")
    AvailabilityService.AvailabilityResult getAvailability(
            @PathVariable UUID branchId,
            @RequestParam LocalDate date,
            @RequestParam @Min(1) @Max(100) int partySize) {
        return availability.find(branchId, date, partySize);
    }
}
