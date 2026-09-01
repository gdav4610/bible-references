package com.gdav.bible.bible_references.config;

import jakarta.annotation.PostConstruct;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Comprueba que la petición viene de nuestra distribución de CloudFront y no directamente
 * del origen.
 *
 * <h2>Por qué existe</h2>
 * Hasta ahora esta comprobación la hacía el <b>listener del ALB</b> mediante una regla sobre
 * la cabecera {@code X-Origin-Verify} (hallazgo 2). Al migrar a Lambda el ALB desaparece del
 * camino y <b>API Gateway HTTP API no tiene enrutado por cabecera</b>: sin este filtro, la
 * URL de ejecución de API Gateway quedaría abierta a cualquiera que la conozca —y aparece en
 * la configuración de CloudFront y en los logs—, de modo que todo lo que aporta el borde
 * (WAF, rate limiting, caché) se esquivaría apuntando ahí.
 *
 * <p>No sustituye a {@link OriginValidationFilter}: aquél valida qué <i>frontend</i> llama
 * ({@code X-Client-Origin}, que el cliente puede falsificar); éste valida que la petición
 * <i>atravesó nuestro CDN</i>, con un secreto que el cliente no conoce. Corre antes porque
 * es la comprobación más barata y la que menos información filtra.
 *
 * <h2>Se activa solo si hay secreto configurado</h2>
 * Con {@code app.origin-verify.secret} vacío el filtro deja pasar todo. Es deliberado: en
 * ECS la comprobación la sigue haciendo el ALB y en local no hay CDN, así que ninguno de los
 * dos entornos debe cambiar de comportamiento. La variable de entorno
 * {@code ORIGIN_VERIFY_SECRET} solo se define en la función Lambda.
 *
 * <p>Consecuencia a tener presente: <b>una configuración ausente no protege nada y no
 * avisa</b>. Por eso el arranque deja constancia en el log de en qué modo quedó el filtro.
 */
@Component
@Order(0)
public class OriginVerifyFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(OriginVerifyFilter.class);

    static final String HEADER = "X-Origin-Verify";

    @Value("${app.origin-verify.secret:}")
    private String secret;

    private byte[] secretBytes;
    private boolean enabled;

    @PostConstruct
    public void init() {
        String valor = secret == null ? "" : secret.trim();
        enabled = !valor.isEmpty();
        secretBytes = valor.getBytes(StandardCharsets.UTF_8);
        if (enabled) {
            logger.info("Verificación de origen ACTIVA: se exigirá la cabecera {}", HEADER);
        } else {
            logger.info("Verificación de origen INACTIVA: sin secreto configurado, "
                    + "el origen queda expuesto salvo que lo proteja la infraestructura");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (!enabled) {
            filterChain.doFilter(request, response);
            return;
        }

        String uri = request.getRequestURI();

        // El health check del target group lo emite el propio ALB, que no envía cabeceras
        // propias. Exento para que activar el secreto por error en ECS no mate las tasks.
        if (uri != null && uri.startsWith("/actuator/health")) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!coincide(request.getHeader(HEADER))) {
            // Sin el valor recibido ni la ruta completa: un log de esta línea acabaría
            // conteniendo intentos de secreto, y con ellos el secreto real el día que
            // alguien lo acierte.
            logger.warn("Petición rechazada: cabecera {} ausente o incorrecta", HEADER);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("Forbidden");
            return;
        }

        filterChain.doFilter(request, response);
    }

    /** Comparación en tiempo constante: {@code equals} sobre String delata el prefijo acertado. */
    private boolean coincide(String recibido) {
        if (recibido == null) {
            return false;
        }
        return MessageDigest.isEqual(recibido.getBytes(StandardCharsets.UTF_8), secretBytes);
    }
}