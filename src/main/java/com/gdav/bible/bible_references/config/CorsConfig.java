package com.gdav.bible.bible_references.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${app.frontend.origin:}")
    private String frontendOrigin;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> allowed = Arrays.stream(frontendOrigin.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        String[] origins;
        if (allowed.isEmpty()) {
            origins = new String[]{"*"};
        } else {
            origins = allowed.toArray(new String[0]);
        }

        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false);
    }
}
