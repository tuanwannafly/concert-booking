package com.geekup.concertbooking.module.auth.dto;

import com.geekup.concertbooking.shared.enums.UserRole;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AuthResponse {

    private String token;
    private String email;
    private String fullName;
    private UserRole role;
}
