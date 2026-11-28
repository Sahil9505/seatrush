package com.seatrush.exception;

import org.springframework.http.HttpStatus;

public class JourneyNotOpenException extends AppException {
    public JourneyNotOpenException(String message) {
        super("JOURNEY_NOT_OPEN", message, HttpStatus.CONFLICT);
    }
}
