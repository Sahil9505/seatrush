package com.seatrush.exception;

import org.springframework.http.HttpStatus;

public class ConflictingBookingException extends AppException {
    public ConflictingBookingException(String message) {
        super("CONFLICTING_BOOKING", message, HttpStatus.CONFLICT);
    }
}
