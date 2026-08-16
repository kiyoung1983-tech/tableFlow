package com.example.fullstack.restaurant;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingBlockRepository extends JpaRepository<BookingBlock, UUID> {
    @Query("""
            select b from BookingBlock b
            where b.branchId = :branchId
              and b.startsAt < :endsAt
              and b.endsAt > :startsAt
            order by b.startsAt
            """)
    List<BookingBlock> findOverlapping(
            @Param("branchId") UUID branchId,
            @Param("startsAt") Instant startsAt,
            @Param("endsAt") Instant endsAt);
}
