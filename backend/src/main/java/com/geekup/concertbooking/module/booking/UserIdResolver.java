package com.geekup.concertbooking.module.booking;

import com.geekup.concertbooking.module.auth.UserRepository;
import com.geekup.concertbooking.shared.exception.AppException;
import com.geekup.concertbooking.shared.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

/**
 * Helper để lấy userId (Long) từ UserDetails đang đăng nhập.
 *
 * UserDetails.getUsername() trả về email theo cấu hình của UserDetailsServiceImpl.
 * Ta cần lookup DB để lấy ID thực sự. Trong production nên cache hoặc embed vào JWT claims.
 */
@Component
@RequiredArgsConstructor
public class UserIdResolver {

    private final UserRepository userRepository;

    public Long resolveUserId(UserDetails userDetails) {
        return userRepository.findByEmail(userDetails.getUsername())
            .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND))
            .getId();
    }
}
