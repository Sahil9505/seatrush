package com.seatrush.service;

import com.seatrush.dto.AuthResponse;
import com.seatrush.dto.LoginRequest;
import com.seatrush.dto.RegisterRequest;
import com.seatrush.entity.Student;
import com.seatrush.exception.AppException;
import com.seatrush.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final StudentRepository studentRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Transactional
    public void register(RegisterRequest req) {
        if (studentRepository.findByEmail(req.email()).isPresent()) {
            throw new AppException("DUPLICATE", "Email already registered", HttpStatus.CONFLICT);
        }
        if (studentRepository.findByRollNo(req.rollNo()).isPresent()) {
            throw new AppException("DUPLICATE", "Roll number already registered", HttpStatus.CONFLICT);
        }
        Student student = Student.builder()
                .rollNo(req.rollNo())
                .name(req.name())
                .email(req.email())
                .passwordHash(passwordEncoder.encode(req.password()))
                .build();
        studentRepository.save(student);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        Student student = studentRepository.findByEmail(req.email())
                .orElseThrow(() -> new AppException("UNAUTHORIZED", "Invalid credentials", HttpStatus.UNAUTHORIZED));

        if (!passwordEncoder.matches(req.password(), student.getPasswordHash())) {
            throw new AppException("UNAUTHORIZED", "Invalid credentials", HttpStatus.UNAUTHORIZED);
        }

        String token = jwtService.generateToken(student.getId(), student.getRole().name());
        return new AuthResponse(token, student.getRole().name());
    }
}
