package com.seatrush.exception;

import org.springframework.http.HttpStatus;

public class JourneyNotFoundException extends AppException {
    public JourneyNotFoundException(String message) {
        super("JOURNEY_NOT_FOUND", message, HttpStatus.NOT_FOUND);
    }
}
