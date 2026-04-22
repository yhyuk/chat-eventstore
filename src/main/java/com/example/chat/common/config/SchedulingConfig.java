package com.example.chat.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Kept out of the main Application class so tests can disable scheduling by excluding this config
// via @TestConfiguration overrides when needed.
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
