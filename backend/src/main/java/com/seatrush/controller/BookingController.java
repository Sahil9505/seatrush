package com.seatrush.controller;

import com.seatrush.dto.BookingRequest;
import com.seatrush.dto.BookingResponse;
import com.seatrush.service.BookingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<BookingResponse> createBooking(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody BookingRequest request,
            Authentication authentication
    ) {
        // userId from the JWT only
        Long userId = Long.valueOf(authentication.getName());
        BookingResponse response = bookingService.book(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
