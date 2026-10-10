package com.example.ledger.service;

import org.springframework.http.HttpStatus;

public class LedgerException extends RuntimeException {
    private final HttpStatus status;

    public LedgerException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() { return status; }
}
