package com.seatrush.exception;

import org.springframework.http.HttpStatus;

public class LockTimeoutException extends AppException {
    public LockTimeoutException(String message) {
        super("LOCK_TIMEOUT", message, HttpStatus.SERVICE_UNAVAILABLE);
    }
}
