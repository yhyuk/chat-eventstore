package com.example.chat.common.config;

import com.example.chat.common.filter.MdcFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }

    @Bean
    public FilterRegistrationBean<MdcFilter> mdcFilterRegistration() {
        // Spring Boot의 ServerHttpObservationFilter(HIGHEST_PRECEDENCE + 1) 이후에 실행되어야
        // Micrometer Observation 스코프가 열린 상태에서 MDC를 채울 수 있다.
        // 순서가 역전되면 HTTP 요청이 Zipkin 스팬을 생성하지 못한다.
        FilterRegistrationBean<MdcFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new MdcFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 10);
        registration.setName("mdcFilter");
        return registration;
    }
}
