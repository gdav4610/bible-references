package com.gdav.bible.bible_references.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuración de OpenAPI/Swagger.
 *
 * <p>La UI queda disponible en {@code /swagger-ui.html} y el JSON de OpenAPI en
 * {@code /v3/api-docs}. Ese JSON puede importarse directamente en Postman
 * (Import → Link → http://localhost:8080/v3/api-docs) para generar una colección
 * con todos los endpoints, sus ejemplos y el header obligatorio.</p>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI bibleReferencesOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .version("v1")
                        .title("Bible References API")
                        .description("""
                                API de referencias bíblicas: capítulos, búsqueda de versículos y
                                estadísticas de códigos Strong.

                                **Importante para probar en Postman/Swagger:** todas las rutas `/api/**`
                                exigen el header `X-Client-Origin` con un origen permitido
                                (por ejemplo `http://localhost:3000`). Sin ese header el servidor responde 403.""")
                        .contact(new Contact().name("gdav").email("gdav07@live.com")))
                .addServersItem(new Server().url("http://localhost:8080").description("Entorno local"));
    }

    /**
     * Añade el header obligatorio {@code X-Client-Origin} a todas las operaciones,
     * de modo que Swagger UI y la colección exportada a Postman ya lo incluyan.
     */
    @Bean
    public OperationCustomizer clientOriginHeaderCustomizer() {
        return (operation, handlerMethod) -> {
            boolean alreadyPresent = operation.getParameters() != null && operation.getParameters().stream()
                    .anyMatch(p -> "X-Client-Origin".equalsIgnoreCase(p.getName()));
            if (!alreadyPresent) {
                operation.addParametersItem(new Parameter()
                        .in("header")
                        .name("X-Client-Origin")
                        .required(true)
                        .description("Origen permitido configurado en app.frontend.origin. Obligatorio para /api/**.")
                        .schema(new StringSchema()._default("http://localhost:3000"))
                        .example("http://localhost:3000"));
            }
            return operation;
        };
    }
}