package com.example.fullstack.restaurant;

import java.util.UUID;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface RestaurantRepository extends JpaRepository<Restaurant, UUID> {
    List<Restaurant> findAllByOrderByNameAsc();
    List<Restaurant> findAllByStatusOrderByNameAsc(OperationalStatus status);
    boolean existsByIdAndStatus(UUID id, OperationalStatus status);
}
