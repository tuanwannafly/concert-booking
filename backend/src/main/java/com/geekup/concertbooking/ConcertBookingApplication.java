package com.geekup.concertbooking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling   // Cần cho BookingExpiryScheduler (expire PENDING bookings)
public class ConcertBookingApplication {
    public static void main(String[] args) {
        SpringApplication.run(ConcertBookingApplication.class, args);
    }
}
