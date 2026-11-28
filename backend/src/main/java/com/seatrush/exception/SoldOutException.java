package com.seatrush.exception;

import org.springframework.http.HttpStatus;

public class SoldOutException extends AppException {
    public SoldOutException(String message) {
        super("SOLD_OUT", message, HttpStatus.CONFLICT);
    }
}
