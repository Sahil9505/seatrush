package com.seatrush.exception;

import org.springframework.http.HttpStatus;

public class InvalidRouteException extends AppException {
    public InvalidRouteException(String message) {
        super("INVALID_RANGE", message, HttpStatus.BAD_REQUEST);
    }
}
