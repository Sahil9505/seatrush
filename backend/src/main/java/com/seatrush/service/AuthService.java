package com.seatrush.service;

import com.seatrush.dto.AuthResponse;
import com.seatrush.dto.LoginRequest;
import com.seatrush.dto.RegisterRequest;
import com.seatrush.entity.User;
import com.seatrush.exception.AppException;
import com.seatrush.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Transactional
    public void register(RegisterRequest req) {
        if (userRepository.findByEmail(req.email()).isPresent()) {
            throw new AppException("DUPLICATE", "Email already registered", HttpStatus.CONFLICT);
        }
        User user = User.builder()
                .name(req.name())
                .email(req.email())
                .passwordHash(passwordEncoder.encode(req.password()))
                .build();
        userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        User user = userRepository.findByEmail(req.email())
                .orElseThrow(() -> new AppException("UNAUTHORIZED", "Invalid credentials", HttpStatus.UNAUTHORIZED));

        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new AppException("UNAUTHORIZED", "Invalid credentials", HttpStatus.UNAUTHORIZED);
        }

        String token = jwtService.generateToken(user.getId(), user.getRole().name());
        return new AuthResponse(token, user.getRole().name());
    }
}
