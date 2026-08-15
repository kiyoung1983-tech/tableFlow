package com.example.fullstack.todo;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface TodoRepository extends JpaRepository<Todo, UUID> {
    Page<Todo> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
