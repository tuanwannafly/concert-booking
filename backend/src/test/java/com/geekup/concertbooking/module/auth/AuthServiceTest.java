package com.geekup.concertbooking.module.auth;

import com.geekup.concertbooking.module.auth.dto.LoginRequest;
import com.geekup.concertbooking.module.auth.dto.RegisterRequest;
import com.geekup.concertbooking.shared.exception.AppException;
import com.geekup.concertbooking.shared.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuthenticationManager authenticationManager;
    @Mock private JwtUtil jwtUtil;

    @InjectMocks
    private AuthService authService;

    // =========================================================================
    // Test 11: register_duplicateEmail_throwsAppException
    // =========================================================================

    @Test
    void register_duplicateEmail_throwsAppException() {
        // Arrange — email already registered in the system
        // RegisterRequest dùng @Getter + @NoArgsConstructor (không có setter)
        // → dùng ReflectionTestUtils để set field trực tiếp
        RegisterRequest request = new RegisterRequest();
        ReflectionTestUtils.setField(request, "email",    "existing@test.com");
        ReflectionTestUtils.setField(request, "password", "password123");
        ReflectionTestUtils.setField(request, "fullName", "Existing User");

        given(userRepository.existsByEmail("existing@test.com")).willReturn(true);

        // Act & Assert
        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.EMAIL_ALREADY_EXISTS);

        // Verify user was NOT saved to DB
        verify(userRepository, never()).save(any());
    }

    // =========================================================================
    // Test 12: login_wrongPassword_throwsAppException
    // =========================================================================

    @Test
    void login_wrongPassword_throwsAppException() {
        // Arrange — AuthenticationManager rejects credentials with BadCredentialsException
        LoginRequest request = new LoginRequest();
        ReflectionTestUtils.setField(request, "email",    "customer1@test.com");
        ReflectionTestUtils.setField(request, "password", "wrongPassword");

        given(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .willThrow(new BadCredentialsException("Bad credentials"));

        // Act & Assert
        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }
}