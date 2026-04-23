package com.example.chat.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// 메인 Application 클래스에서 분리하여, 테스트 시 @TestConfiguration 으로 이 설정을 제외하면 스케줄링을 비활성화할 수 있다.
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
