package com.example.seating.common;

import org.postgresql.util.PSQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/*
 * 集中式例外處理。
 *
 * 傳統 Servlet / Struts / Spring MVC 專案常會在每個 Controller try-catch。
 * Spring Boot 建議用 @RestControllerAdvice 統一轉換例外，確保 API 錯誤格式一致。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> validation(MethodArgumentNotValidException ex) {
        /*
         * DTO 上的 @NotBlank / @Pattern / @NotEmpty 驗證失敗會進到這裡。
         */
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.VALIDATION_ERROR, "Request validation failed", null));
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiResponse<Object>> business(BusinessException ex) {
        /*
         * BusinessException 是我們自己定義的可預期錯誤。
         * VALIDATION_ERROR -> 400，其餘衝突類錯誤 -> 409。
         */
        HttpStatus status = ex.getCode() == ErrorCode.VALIDATION_ERROR ? HttpStatus.BAD_REQUEST : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(ApiResponse.error(ex.getCode(), ex.getMessage(), ex.getData()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiResponse<Void>> integrity(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ErrorCode.CONFLICT_SEAT_TAKEN, "Seat assignment conflicts with current state", null));
    }

    @ExceptionHandler(PSQLException.class)
    ResponseEntity<ApiResponse<Void>> postgres(PSQLException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ErrorCode.CONFLICT_SEAT_TAKEN, "Seat assignment conflicts with current state", null));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> fallback(Exception ex) {
        /*
         * 未預期錯誤只記 log，不把 stack trace / SQL 細節回給前端。
         */
        log.error("Unhandled error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR, "Unexpected server error", null));
    }
}
