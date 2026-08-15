package com.example.fullstack.todo;

import com.example.fullstack.common.persistence.BaseTimeEntity;
import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "todos")
class Todo extends BaseTimeEntity {
    @Id private UUID id;
    @Column(nullable = false, length = 200) private String title;
    @Column(nullable = false) private boolean completed;
    protected Todo() {}
    Todo(String title) { this.id = UUID.randomUUID(); this.title = title.strip(); }
    UUID id() { return id; }
    String title() { return title; }
    boolean completed() { return completed; }
    @Override protected Instant createdAt() { return super.createdAt(); }
    @Override protected Instant updatedAt() { return super.updatedAt(); }
}
