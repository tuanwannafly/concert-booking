package com.geekup.concertbooking.config;

import com.geekup.concertbooking.entity.TicketCategory;
import com.geekup.concertbooking.module.auth.UserRepository;
import com.geekup.concertbooking.module.concert.ConcertRepository;
import com.geekup.concertbooking.module.concert.TicketCategoryRepository;
import com.geekup.concertbooking.shared.enums.ConcertStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Chạy 1 lần sau khi Spring context khởi động xong.
 *
 * Nhiệm vụ:
 *  1. Fix password cho seeded users — dùng PasswordEncoder thật, không hardcode hash.
 *  2. Pre-load Redis inventory cho tất cả concert đang PUBLISHED.
 *     → Đảm bảo inventory luôn có sau mỗi lần restart, không cần gọi API publish lại.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupRunner implements ApplicationRunner {

    private static final String       SEED_PASSWORD     = "password123";
    private static final List<String> SEED_EMAILS       = List.of(
            "admin@geekup.vn",
            "operator1@geekup.vn",
            "customer1@test.com",
            "customer2@test.com",
            "customer3@test.com"
    );
    private static final String INVENTORY_KEY_PREFIX = "inventory:ticket:";

    private final UserRepository                    userRepository;
    private final ConcertRepository                 concertRepository;
    private final TicketCategoryRepository          ticketCategoryRepository;
    private final RedisTemplate<String, String>     redisTemplate;
    private final PasswordEncoder                   passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        fixSeedPasswords();
        preloadRedisInventory();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Fix seed user passwords
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reset password cho các seeded accounts về "password123".
     * Dùng PasswordEncoder.encode() trực tiếp → hash luôn đúng.
     * Chỉ update nếu password hiện tại KHÔNG match → tránh encode lại mỗi lần restart.
     */
    private void fixSeedPasswords() {
        int fixed = 0;
        for (String email : SEED_EMAILS) {
            try {
                userRepository.findByEmail(email).ifPresent(user -> {
                    if (!passwordEncoder.matches(SEED_PASSWORD, user.getPasswordHash())) {
                        user.setPasswordHash(passwordEncoder.encode(SEED_PASSWORD));
                        userRepository.save(user);
                        log.info("[StartupRunner] Fixed password for: {}", email);
                    } else {
                        log.debug("[StartupRunner] Password OK for: {}", email);
                    }
                });
                fixed++;
            } catch (Exception e) {
                log.warn("[StartupRunner] Could not fix password for {}: {}", email, e.getMessage());
            }
        }
        log.info("[StartupRunner] Password check completed for {} seed accounts", fixed);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Pre-load Redis inventory
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Load inventory vào Redis cho tất cả concert PUBLISHED.
     *
     * Dùng setIfAbsent() thay vì set() để:
     * - Nếu key chưa có (Redis mới restart) → restore từ DB.
     * Luôn ghi đè (set thay vì setIfAbsent) để đảm bảo inventory
     * khớp với DB sau mỗi lần restart — tránh stale/negative values từ run trước.
     */
    private void preloadRedisInventory() {
        try {
            List<com.geekup.concertbooking.entity.Concert> publishedConcerts =
                    concertRepository.findByStatus(ConcertStatus.PUBLISHED, Pageable.unpaged())
                            .getContent();

            if (publishedConcerts.isEmpty()) {
                log.info("[StartupRunner] No PUBLISHED concerts — Redis inventory preload skipped");
                return;
            }

            int loaded = 0;

            for (var concert : publishedConcerts) {
                List<TicketCategory> categories =
                        ticketCategoryRepository.findByConcertId(concert.getId());

                for (TicketCategory cat : categories) {
                    String key = INVENTORY_KEY_PREFIX + cat.getId();
                    try {
                        // Dùng set() thay vì setIfAbsent() — luôn sync từ DB
                        // DB là source of truth, Redis chỉ là fast-path cache
                        redisTemplate.opsForValue().set(key, String.valueOf(cat.getAvailableQuantity()));
                        log.debug("[StartupRunner] Inventory loaded: {} = {}", key, cat.getAvailableQuantity());
                        loaded++;
                    } catch (Exception e) {
                        log.warn("[StartupRunner] Redis unavailable for key {}: {}", key, e.getMessage());
                    }
                }
            }

            log.info("[StartupRunner] Redis inventory preload done: {} categories loaded from DB",
                    loaded);

        } catch (Exception e) {
            log.error("[StartupRunner] Redis inventory preload failed: {}", e.getMessage());
            // Không throw — app vẫn start bình thường, booking sẽ fallback về DB optimistic lock
        }
    }
}