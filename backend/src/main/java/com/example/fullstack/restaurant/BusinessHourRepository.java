package com.example.fullstack.restaurant;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessHourRepository extends JpaRepository<BusinessHour, UUID> {
    List<BusinessHour> findAllByBranchIdOrderByDayOfWeekAscOpensAtAsc(UUID branchId);

    List<BusinessHour> findAllByBranchIdAndDayOfWeekOrderByOpensAtAsc(UUID branchId, short dayOfWeek);

    void deleteAllByBranchId(UUID branchId);
}
