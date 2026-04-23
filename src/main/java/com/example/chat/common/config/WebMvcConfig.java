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
        // Run AFTER Spring Boot's ServerHttpObservationFilter (HIGHEST_PRECEDENCE + 1) so
        // the Micrometer Observation scope is already open when we populate MDC. Otherwise
        // HTTP requests would not produce Zipkin spans.
        FilterRegistrationBean<MdcFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new MdcFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 10);
        registration.setName("mdcFilter");
        return registration;
    }
}
