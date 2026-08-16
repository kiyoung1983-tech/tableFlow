package com.example.fullstack.reservation;

import java.sql.PreparedStatement;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

@Component
class IdempotencyLock {
    private final JdbcTemplate jdbc;

    IdempotencyLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void acquire(UUID idempotencyKey) {
        var lockKey = idempotencyKey.getMostSignificantBits()
                ^ idempotencyKey.getLeastSignificantBits();
        jdbc.query(
                "select pg_advisory_xact_lock(?)",
                (PreparedStatement statement) -> statement.setLong(1, lockKey),
                (RowCallbackHandler) resultSet -> {});
    }
}
