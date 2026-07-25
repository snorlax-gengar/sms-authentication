package com.example.sms.common.exception;

import com.example.sms.common.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(SmsRateLimitException.class)
    public ResponseEntity<ApiResponse<Void>> handleRateLimit(SmsRateLimitException ex) {
        log.warn("Rate limit exceeded: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiResponse.failure(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(SmsVerificationExpiredException.class)
    public ResponseEntity<ApiResponse<Void>> handleVerificationExpired(SmsVerificationExpiredException ex) {
        log.warn("Verification expired: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(SmsVerificationMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleVerificationMismatch(SmsVerificationMismatchException ex) {
        log.warn("Verification mismatch");
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(SmsVerificationAttemptExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleVerificationAttemptExceeded(SmsVerificationAttemptExceededException ex) {
        log.warn("Verification attempts exceeded: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiResponse.failure(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(SmsSendFailedException.class)
    public ResponseEntity<ApiResponse<Void>> handleSendFailed(SmsSendFailedException ex) {
        log.error("SMS send failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.failure(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(SmsException.class)
    public ResponseEntity<ApiResponse<Void>> handleSmsException(SmsException ex) {
        log.error("SMS error [{}]: {}", ex.getErrorCode(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.failure(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("Validation failed: {}", message);
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure("INVALID_REQUEST", message));
    }
}
