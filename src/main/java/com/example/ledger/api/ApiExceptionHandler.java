package com.example.ledger.api;

import com.example.ledger.service.LedgerException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class ApiExceptionHandler {
    record ErrorResponse(Instant timestamp, int status, String error) {}

    @ExceptionHandler(LedgerException.class)
    ResponseEntity<ErrorResponse> handleLedger(LedgerException exception) {
        return ResponseEntity.status(exception.status())
                .body(new ErrorResponse(Instant.now(), exception.status().value(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(Instant.now(), 400, "Request contains invalid or missing fields"));
    }
}
