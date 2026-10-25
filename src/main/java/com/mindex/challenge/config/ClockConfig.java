package com.mindex.challenge.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    // Injected wherever the code needs "now" or "today", so tests can substitute a fixed clock
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
