package com.example.fullstack.restaurant;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BranchRepository extends JpaRepository<Branch, UUID> {
    List<Branch> findAllByRestaurantIdOrderByNameAsc(UUID restaurantId);
    List<Branch> findAllByRestaurantIdInAndStatusOrderByNameAsc(
            List<UUID> restaurantIds, OperationalStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Branch b where b.id = :id")
    Optional<Branch> findByIdForUpdate(@Param("id") UUID id);
}
