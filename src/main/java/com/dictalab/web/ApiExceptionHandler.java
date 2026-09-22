package com.dictalab.web;

import com.dictalab.dto.ApiDtos;
import com.dictalab.exception.InvalidPracticeStateException;
import com.dictalab.exception.ResourceNotFoundException;
import com.dictalab.exception.AuthenticationFailedException;
import com.dictalab.exception.EmailAlreadyExistsException;
import com.dictalab.exception.ContentManagementException;
import com.dictalab.exception.MediaStorageException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.Instant;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiDtos.ApiError> notFound(ResourceNotFoundException exception,
                                                      HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidPracticeStateException.class)
    public ResponseEntity<ApiDtos.ApiError> invalidState(InvalidPracticeStateException exception,
                                                         HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, exception.getMessage(), request);
    }

    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<ApiDtos.ApiError> authenticationFailed(AuthenticationFailedException exception,
                                                                  HttpServletRequest request) {
        return error(HttpStatus.UNAUTHORIZED, exception.getMessage(), request);
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ApiDtos.ApiError> emailExists(EmailAlreadyExistsException exception,
                                                         HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, exception.getMessage(), request);
    }

    @ExceptionHandler(ContentManagementException.class)
    public ResponseEntity<ApiDtos.ApiError> contentConflict(ContentManagementException exception,
                                                             HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, exception.getMessage(), request);
    }

    @ExceptionHandler(MediaStorageException.class)
    public ResponseEntity<ApiDtos.ApiError> invalidMedia(MediaStorageException exception,
                                                          HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage(), request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiDtos.ApiError> uploadTooLarge(MaxUploadSizeExceededException exception,
                                                            HttpServletRequest request) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "File audio vượt quá giới hạn cho phép.", request);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    public ResponseEntity<ApiDtos.ApiError> validation(Exception exception, HttpServletRequest request) {
        String message = exception instanceof MethodArgumentNotValidException validationException
                ? validationException.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage() == null ? "Dữ liệu không hợp lệ." : error.getDefaultMessage())
                .orElse("Dữ liệu không hợp lệ.")
                : "Dữ liệu không hợp lệ.";
        return error(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiDtos.ApiError> unexpected(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Đã có lỗi xảy ra. Vui lòng thử lại.", request);
    }

    private ResponseEntity<ApiDtos.ApiError> error(HttpStatus status, String message,
                                                    HttpServletRequest request) {
        ApiDtos.ApiError body = new ApiDtos.ApiError(
                status.value(), message, request.getRequestURI(), Instant.now()
        );
        return ResponseEntity.status(status).body(body);
    }
}
