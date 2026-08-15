package com.example.fullstack.common.error;

import com.example.fullstack.common.web.RequestTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApiException(ApiException exception, HttpServletRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(exception.status(), exception.getMessage());
        problem.setTitle(exception.status().getReasonPhrase());
        problem.setProperty("code", exception.code());
        addTraceId(problem, request);
        return problem;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request) {
        Map<String, List<String>> errors = exception.getConstraintViolations().stream()
                .collect(Collectors.groupingBy(
                        violation -> lastPathSegment(violation.getPropertyPath().toString()),
                        LinkedHashMap::new,
                        Collectors.mapping(violation -> violation.getMessage(), Collectors.toList())));
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "요청값을 확인해 주세요.");
        problem.setTitle("Validation failed");
        problem.setProperty("code", "VALIDATION_ERROR");
        problem.setProperty("errors", errors);
        addTraceId(problem, request);
        return problem;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpectedException(Exception exception, HttpServletRequest request) {
        log.error("Unhandled request error", exception);
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "요청을 처리하는 중 예상하지 못한 오류가 발생했습니다.");
        problem.setTitle("Internal Server Error");
        problem.setProperty("code", "INTERNAL_SERVER_ERROR");
        addTraceId(problem, request);
        return problem;
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        Map<String, List<String>> errors = exception.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.groupingBy(
                        fieldError -> fieldError.getField(),
                        LinkedHashMap::new,
                        Collectors.mapping(
                                fieldError -> fieldError.getDefaultMessage() == null
                                        ? "올바르지 않은 값입니다."
                                        : fieldError.getDefaultMessage(),
                                Collectors.toList())));
        return validationProblem(exception, headers, status, request, errors);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        Map<String, List<String>> errors = new LinkedHashMap<>();
        exception.getParameterValidationResults().forEach(result -> {
            var parameterName = result.getMethodParameter().getParameterName();
            var key = parameterName == null ? "request" : parameterName;
            var messages = result.getResolvableErrors().stream()
                    .map(error -> error.getDefaultMessage() == null
                            ? "올바르지 않은 값입니다."
                            : error.getDefaultMessage())
                    .toList();
            errors.put(key, messages);
        });
        return validationProblem(exception, headers, status, request, errors);
    }

    private ResponseEntity<Object> validationProblem(
            Exception exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request,
            Map<String, List<String>> errors) {
        var problem = ProblemDetail.forStatusAndDetail(status, "요청값을 확인해 주세요.");
        problem.setTitle("Validation failed");
        problem.setProperty("code", "VALIDATION_ERROR");
        problem.setProperty("errors", errors);
        if (request instanceof ServletWebRequest servletWebRequest) {
            addTraceId(problem, servletWebRequest.getRequest());
        }
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            if (problem.getProperties() == null || !problem.getProperties().containsKey("code")) {
                problem.setProperty("code", "REQUEST_ERROR");
            }
            if (request instanceof ServletWebRequest servletWebRequest) {
                addTraceId(problem, servletWebRequest.getRequest());
            }
        }
        return super.handleExceptionInternal(exception, body, headers, status, request);
    }

    private void addTraceId(ProblemDetail problem, HttpServletRequest request) {
        var traceId = request.getAttribute(RequestTraceFilter.TRACE_ID_ATTRIBUTE);
        if (traceId != null) {
            problem.setProperty("traceId", traceId.toString());
        }
    }

    private String lastPathSegment(String path) {
        var separator = path.lastIndexOf('.');
        return separator < 0 ? path : path.substring(separator + 1);
    }
}
