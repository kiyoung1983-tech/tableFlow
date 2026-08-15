package com.example.fullstack.todo;

import com.example.fullstack.common.web.PageResponse;
import java.time.Instant;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api/todos")
class TodoController {
    private final TodoRepository repository;
    TodoController(TodoRepository repository) { this.repository = repository; }

    @GetMapping
    @Transactional(readOnly = true)
    PageResponse<TodoResponse> findAll(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var todos = repository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size));
        return PageResponse.from(todos, TodoResponse::from);
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    TodoResponse findOne(@PathVariable UUID id) {
        return repository.findById(id)
                .map(TodoResponse::from)
                .orElseThrow(() -> new TodoNotFoundException(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    TodoResponse create(@Valid @RequestBody CreateTodoRequest request) {
        return TodoResponse.from(repository.save(new Todo(request.title())));
    }

    record CreateTodoRequest(@NotBlank @Size(max = 200) String title) {}
    record TodoResponse(UUID id, String title, boolean completed, Instant createdAt, Instant updatedAt) {
        static TodoResponse from(Todo todo) {
            return new TodoResponse(
                    todo.id(), todo.title(), todo.completed(), todo.createdAt(), todo.updatedAt());
        }
    }
}
