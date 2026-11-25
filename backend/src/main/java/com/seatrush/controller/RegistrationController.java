package com.seatrush.controller;

import com.seatrush.dto.DropResponse;
import com.seatrush.dto.MyRegistrationResponse;
import com.seatrush.dto.RegistrationRequest;
import com.seatrush.dto.RegistrationResponse;
import com.seatrush.service.BookingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/registrations")
@RequiredArgsConstructor
public class RegistrationController {

    private final BookingService bookingService;

    @PostMapping
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<RegistrationResponse> register(
            @Valid @RequestBody RegistrationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication
    ) {
        Long studentId = Long.parseLong(authentication.getName());
        RegistrationResponse response = bookingService.register(studentId, request.sectionId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<List<MyRegistrationResponse>> getMyRegistrations(Authentication authentication) {
        Long studentId = Long.parseLong(authentication.getName());
        return ResponseEntity.ok(bookingService.getMyRegistrations(studentId));
    }

    @DeleteMapping("/{sectionId}")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<DropResponse> drop(
            @PathVariable Long sectionId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication
    ) {
        Long studentId = Long.parseLong(authentication.getName());
        return ResponseEntity.ok(bookingService.drop(studentId, sectionId));
    }
}
