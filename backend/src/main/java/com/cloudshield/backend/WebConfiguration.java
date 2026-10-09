package com.cloudshield.backend;

import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {
    private final String frontendOrigin;
    public WebConfiguration(@Value("${cloudshield.cors.allowed-origin:http://localhost:5173}") String frontendOrigin) {
        if (frontendOrigin == null || frontendOrigin.isBlank() || "*".equals(frontendOrigin.trim())) {
            throw new IllegalArgumentException("A specific frontend CORS origin must be configured");
        }
        this.frontendOrigin = frontendOrigin.trim();
    }
    @Override public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins(frontendOrigin)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "X-XSRF-TOKEN", "X-Probe-Key")
                .allowCredentials(true).maxAge(3600);
    }
}
