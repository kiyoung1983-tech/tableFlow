package com.example.fullstack.todo;

import com.example.fullstack.common.error.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

class TodoNotFoundException extends ApiException {
    TodoNotFoundException(UUID id) {
        super(HttpStatus.NOT_FOUND, "TODO_NOT_FOUND", "Todo를 찾을 수 없습니다: " + id);
    }
}
