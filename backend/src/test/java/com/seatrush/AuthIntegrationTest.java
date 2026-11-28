package com.seatrush;

import com.seatrush.dto.AuthResponse;
import com.seatrush.dto.ErrorResponse;
import com.seatrush.dto.LoginRequest;
import com.seatrush.dto.RegisterRequest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    private static String jwtToken;

    private static final RegisterRequest VALID_REGISTER = new RegisterRequest(
            "Alice", "alice@example.com", "password123"
    );

    // ------------------------------------------------------------------ register
    @Test
    @Order(1)
    void register_success() {
        ResponseEntity<Void> resp = rest.postForEntity(
                "/api/auth/register", VALID_REGISTER, Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @Order(2)
    void register_duplicate_returns_409() {
        ResponseEntity<ErrorResponse> resp = rest.postForEntity(
                "/api/auth/register", VALID_REGISTER, ErrorResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo("DUPLICATE");
    }

    // ------------------------------------------------------------------ login
    @Test
    @Order(3)
    void login_success() {
        rest.postForEntity("/api/auth/register", VALID_REGISTER, Void.class);

        ResponseEntity<AuthResponse> resp = rest.postForEntity(
                "/api/auth/login",
                new LoginRequest("alice@example.com", "password123"),
                AuthResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().token()).isNotBlank();
        assertThat(resp.getBody().role()).isEqualTo("USER");

        jwtToken = resp.getBody().token();
    }

    @Test
    @Order(4)
    void login_wrong_password_returns_401() {
        ResponseEntity<ErrorResponse> resp = rest.postForEntity(
                "/api/auth/login",
                new LoginRequest("alice@example.com", "wrongpass"),
                ErrorResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ------------------------------------------------------------------ protected endpoint
    @Test
    @Order(5)
    void protected_endpoint_without_token_returns_401() {
        ResponseEntity<Void> resp = rest.getForEntity("/api/stations", Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @Order(6)
    void protected_endpoint_with_valid_token_returns_not_401() {
        rest.postForEntity("/api/auth/register", VALID_REGISTER, Void.class);
        ResponseEntity<AuthResponse> loginResp = rest.postForEntity(
                "/api/auth/login",
                new LoginRequest("alice@example.com", "password123"),
                AuthResponse.class);
        String token = loginResp.getBody() != null ? loginResp.getBody().token() : jwtToken;

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<Void> resp = rest.exchange(
                "/api/stations", HttpMethod.GET, new HttpEntity<>(headers), Void.class);

        assertThat(resp.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
