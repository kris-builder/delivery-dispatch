package org.example.delivery.web;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.databind.ObjectMapper;

@RestControllerAdvice
public class ApiProblems {
    public static final String REQUEST_ID = "X-Request-Id";
    private static final Logger log = LoggerFactory.getLogger(ApiProblems.class);

    public static void write(ObjectMapper json, HttpServletResponse response,
                             HttpStatus status, String code, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), body(response, status, code, detail));
    }

    @ExceptionHandler(Failure.class)
    ResponseEntity<Problem> failure(Failure failure, HttpServletResponse response) {
        return response(response, failure.status, failure.code, failure.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, ServletRequestBindingException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<Problem> invalidRequest(HttpServletResponse response) {
        return response(response, HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请检查请求参数。");
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<Problem> forbidden(HttpServletResponse response) {
        return response(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "当前岗位或所属单位无权执行该操作。");
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Problem> storageFailure(DataAccessException failure, HttpServletResponse response) {
        log.error("Database operation failed requestId={} exception={}",
                response.getHeader(REQUEST_ID), failure.getClass().getSimpleName());
        return response(response, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "数据提交遇到错误。请使用原操作标识核对结果或重试。");
    }

    private static ResponseEntity<Problem> response(HttpServletResponse response, HttpStatus status,
                                                     String code, String detail) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body(response, status, code, detail));
    }

    private static Problem body(HttpServletResponse response, HttpStatus status, String code, String detail) {
        return new Problem("about:blank", status.getReasonPhrase(), status.value(), detail, code,
                response.getHeader(REQUEST_ID));
    }

    record Problem(String type, String title, int status, String detail, String code, String requestId) {}

    public static final class Failure extends RuntimeException {
        private final HttpStatus status;
        private final String code;

        public Failure(HttpStatus status, String code, String detail) {
            super(detail);
            this.status = status;
            this.code = code;
        }
    }
}
