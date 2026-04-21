package com.gdav.bible.bible_references.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Component
@Order(1)
public class OriginValidationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(OriginValidationFilter.class);

    @Value("${app.frontend.origin:}")
    private String frontendOrigin;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String origin = request.getHeader("X-Client-Origin");

        String uri = request.getRequestURI();

        // Excepción para el endpoint de health
        if (uri != null && uri.startsWith("/actuator/health")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Ahora bloqueamos si no viene header Origin
        if (origin == null || origin.isBlank()) {
            logger.warn("Request blocked: missing Origin header, path={}", request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("Forbidden: missing Origin header");
            return;
        }

        List<String> allowed = Arrays.stream(frontendOrigin.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        // Si no hay orígenes configurados, rechazamos todas las peticiones con Origin (seguridad por defecto)
        if (allowed.isEmpty()) {
            logger.warn("Request blocked: no allowed origins configured, origin={}, path={}", origin, request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("Forbidden: no allowed origins configured");
            return;
        }

        // Soporta comodín '*'
        if (allowed.contains("*")) {
            filterChain.doFilter(request, response);
            return;
        }

        boolean ok = allowed.contains(origin);
        if (!ok) {
            logger.warn("Request blocked: origin not allowed ({}), path={}", origin, request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("Forbidden origin: " + origin);
            return;
        }

        filterChain.doFilter(request, response);
    }
}
