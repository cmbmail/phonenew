package com.phonecost.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * M-37: WebMvc configuration.
 * CORS is handled by SecurityConfig.corsConfigurationSource() — no duplicate CORS mapping here.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    // CORS is managed by Spring Security (SecurityConfig).
    // Previously had addCorsMappings() which duplicated SecurityConfig CORS — removed.
}
