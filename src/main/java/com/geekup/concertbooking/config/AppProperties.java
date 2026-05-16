package com.geekup.concertbooking.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app")
@Getter
@Setter
public class AppProperties {

    private Jwt jwt = new Jwt();
    private Booking booking = new Booking();
    private Redis redis = new Redis();

    @Getter
    @Setter
    public static class Jwt {
        private String secret;
        private long expirationMs = 86400000L;
    }

    @Getter
    @Setter
    public static class Booking {
        private int expirationMinutes = 15;
    }

    @Getter
    @Setter
    public static class Redis {
        private String inventoryKeyPrefix = "inventory:ticket:";
        private String idempotencyKeyPrefix = "idem:booking:";
        private int idempotencyTtlHours = 24;
    }
}
