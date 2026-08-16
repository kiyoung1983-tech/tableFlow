package com.example.fullstack.common.error;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends ApiException {
    public ResourceNotFoundException(String resource, String code, UUID id) {
        super(HttpStatus.NOT_FOUND, code, resource + "을(를) 찾을 수 없습니다: " + id);
    }
}
