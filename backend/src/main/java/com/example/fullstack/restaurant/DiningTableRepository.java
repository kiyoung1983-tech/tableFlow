package com.example.fullstack.restaurant;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DiningTableRepository extends JpaRepository<DiningTable, UUID> {
    List<DiningTable> findAllByBranchIdOrderByCapacityAscNameAsc(UUID branchId);

    List<DiningTable> findAllByBranchIdAndEnabledTrueOrderByCapacityAscNameAsc(UUID branchId);

    Optional<DiningTable> findByIdAndBranchId(UUID id, UUID branchId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from DiningTable t where t.branchId = :branchId order by t.capacity, t.name")
    List<DiningTable> findAllByBranchIdForUpdate(@Param("branchId") UUID branchId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from DiningTable t where t.id = :id")
    Optional<DiningTable> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from DiningTable t where t.id = :id and t.branchId = :branchId")
    Optional<DiningTable> findByIdAndBranchIdForUpdate(
            @Param("id") UUID id, @Param("branchId") UUID branchId);
}
