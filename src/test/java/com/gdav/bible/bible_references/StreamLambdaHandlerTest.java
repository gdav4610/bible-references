package com.gdav.bible.bible_references;

import com.amazonaws.services.lambda.runtime.ClientContext;
import com.amazonaws.services.lambda.runtime.CognitoIdentity;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueba de extremo a extremo del handler de Lambda.
 *
 * <p>Responde a la pregunta que ninguna otra prueba cubre: <b>¿arranca esta aplicación
 * dentro de Lambda y llega una petición hasta el controlador?</b> Verificar que el JAR
 * reempaquetado está bien formado no dice nada de eso.
 *
 * <p>Lo que ejercita, en un solo camino:
 * <ol>
 *   <li>que {@code aws-serverless-java-container} 3.0.0 levanta un contexto de Spring Boot 4.1
 *       —la incompatibilidad más probable, por lo reciente de ambos—;</li>
 *   <li>que un evento de <b>API Gateway REST con payload v1</b> se deserializa correctamente;</li>
 *   <li>que el evento se traduce a la API de Servlet y Spring MVC lo enruta;</li>
 *   <li>que {@code OriginValidationFilter} sigue ejecutándose, cosa que no es evidente:
 *       depende de que el adaptador respete la cadena de filtros;</li>
 *   <li>que la respuesta vuelve serializada en el formato que espera Lambda.</li>
 * </ol>
 *
 * <h2>Por qué H2 y no PostgreSQL</h2>
 * El contexto no arranca sin un {@code DataSource}, así que hace falta una base de datos.
 * Con H2 las tablas se crean vacías desde las entidades, que basta: {@code getChapter}
 * devuelve 200 con una lista vacía en lugar de fallar. Lo que <b>no</b> se puede probar así
 * es {@code /api/bible/search}, cuya consulta nativa usa {@code unaccent} y el operador
 * {@code ~*} de PostgreSQL.
 */
@DisplayName("Handler de Lambda")
class StreamLambdaHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * El handler lee los perfiles activos al inicializarse en su bloque estático, y eso
     * ocurre la primera vez que se instancia la clase. La propiedad tiene que estar puesta
     * antes: por eso se fija aquí y no se referencia {@link StreamLambdaHandler} hasta
     * dentro de los métodos de prueba.
     */
    @BeforeAll
    static void activarPerfilDePrueba() {
        System.setProperty("spring.profiles.active", "lambdatest");
    }

    /** Surefire reutiliza la JVM entre clases: dejar la propiedad puesta contaminaría al resto. */
    @AfterAll
    static void limpiarPerfilDePrueba() {
        System.clearProperty("spring.profiles.active");
    }

    @Test
    @DisplayName("una petición con origen permitido llega al controlador y responde 200")
    void peticionValidaLlegaAlControlador() throws IOException {
        JsonNode respuesta = invocar(evento("GET", "/api/bible/chapter/1/1", "http://localhost:3000"));

        assertThat(respuesta.get("statusCode").asInt())
                .as("debe atravesar filtro, controlador, servicio y repositorio")
                .isEqualTo(200);

        JsonNode cuerpo = MAPPER.readTree(respuesta.get("body").asText());
        // Las tablas están vacías, así que la lista de versículos viene vacía; lo relevante
        // es que el controlador construyó la respuesta y Jackson la serializó.
        assertThat(cuerpo.has("verses")).isTrue();
        assertThat(cuerpo.get("verses")).isEmpty();
        assertThat(cuerpo.get("chapter").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("sin la cabecera X-Client-Origin el filtro devuelve 403")
    void peticionSinOrigenEsRechazada() throws IOException {
        JsonNode respuesta = invocar(evento("GET", "/api/bible/chapter/1/1", null));

        assertThat(respuesta.get("statusCode").asInt())
                .as("OriginValidationFilter debe seguir activo dentro de Lambda")
                .isEqualTo(403);
    }

    @Test
    @DisplayName("un capítulo de la lista de populares viaja con Cache-Control para CloudFront")
    void capituloPopularEsCacheable() throws IOException {
        // Génesis 1 está en VerseCacheCondition.
        JsonNode respuesta = invocar(evento("GET", "/api/bible/chapter/1/1", "http://localhost:3000"));

        assertThat(cabecera(respuesta, "Cache-Control"))
                .as("sin esta cabecera CloudFront no guarda nada, por muy alto que esté el TTL")
                .contains("max-age=86400")
                .contains("public");
    }

    @Test
    @DisplayName("un capítulo fuera de la lista sale con no-store")
    void capituloNoPopularNoEsCacheable() throws IOException {
        // Génesis 40 no está en la lista: el comportamiento por defecto es no cachear, de
        // modo que ampliar la caché sea siempre una decisión explícita.
        JsonNode respuesta = invocar(evento("GET", "/api/bible/chapter/1/40", "http://localhost:3000"));

        assertThat(cabecera(respuesta, "Cache-Control")).contains("no-store");
    }

    @Test
    @DisplayName("un parámetro inválido devuelve 400 desde el ControllerAdvice")
    void parametroInvalidoDevuelve400() throws IOException {
        // idBook=0 incumple @Positive: comprueba que la validación de Jakarta y el
        // @ControllerAdvice global también funcionan bajo el adaptador.
        JsonNode respuesta = invocar(evento("GET", "/api/bible/chapter/0/1", "http://localhost:3000"));

        assertThat(respuesta.get("statusCode").asInt()).isEqualTo(400);
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /**
     * Lee una cabecera de la respuesta sin depender de mayúsculas/minúsculas ni de si el
     * adaptador la puso en {@code headers} o en {@code multiValueHeaders}: son detalles de
     * serialización del formato v1 que no deben hacer fallar la prueba.
     */
    private String cabecera(JsonNode respuesta, String nombre) {
        String valor = buscarEn(respuesta.get("headers"), nombre);
        return valor.isEmpty() ? buscarEn(respuesta.get("multiValueHeaders"), nombre) : valor;
    }

    private String buscarEn(JsonNode cabeceras, String nombre) {
        if (cabeceras == null || cabeceras.isNull()) {
            return "";
        }
        var it = cabeceras.fields();
        while (it.hasNext()) {
            var entrada = it.next();
            if (entrada.getKey().equalsIgnoreCase(nombre)) {
                JsonNode valor = entrada.getValue();
                return valor.isArray() ? valor.get(0).asText() : valor.asText();
            }
        }
        return "";
    }

    /** Invoca el handler y devuelve la respuesta ya parseada. */
    private JsonNode invocar(String eventoJson) throws IOException {
        var salida = new ByteArrayOutputStream();
        new StreamLambdaHandler().handleRequest(
                new ByteArrayInputStream(eventoJson.getBytes(StandardCharsets.UTF_8)),
                salida,
                new ContextDePrueba());
        return MAPPER.readTree(salida.toByteArray());
    }

    /**
     * Construye un evento con el formato de payload <b>1.0</b>, que es el que emite la
     * integración proxy de una API Gateway REST API —la única disponible en
     * {@code mx-central-1}—. Se escribe a mano en vez de usar un builder para que la prueba
     * falle si el handler dejara de aceptar ese formato concreto.
     *
     * <p>Detalle que importa: {@code path} <b>no</b> lleva el nombre del stage y
     * {@code requestContext.path} <b>sí</b>. El adaptador debe usar el primero; si usara el
     * segundo, Spring MVC vería {@code /prod/api/...} y devolvería 404. Por eso el evento
     * reproduce ambos con el prefijo real en su sitio en vez de simplificarlo.
     */
    private String evento(String metodo, String ruta, String clientOrigin) {
        String host = "example.execute-api.mx-central-1.amazonaws.com";
        String cabeceras = clientOrigin == null
                ? "{\"Host\":\"" + host + "\"}"
                : "{\"Host\":\"" + host + "\",\"X-Client-Origin\":\"" + clientOrigin + "\"}";
        String cabecerasMulti = clientOrigin == null
                ? "{\"Host\":[\"" + host + "\"]}"
                : "{\"Host\":[\"" + host + "\"],\"X-Client-Origin\":[\"" + clientOrigin + "\"]}";
        return """
                {
                  "resource": "/{proxy+}",
                  "path": "%s",
                  "httpMethod": "%s",
                  "headers": %s,
                  "multiValueHeaders": %s,
                  "queryStringParameters": null,
                  "multiValueQueryStringParameters": null,
                  "pathParameters": { "proxy": "%s" },
                  "stageVariables": null,
                  "requestContext": {
                    "resourcePath": "/{proxy+}",
                    "httpMethod": "%s",
                    "path": "/prod%s",
                    "stage": "prod",
                    "requestId": "prueba-local",
                    "identity": { "sourceIp": "203.0.113.1", "userAgent": "prueba" },
                    "protocol": "HTTP/1.1"
                  },
                  "body": null,
                  "isBase64Encoded": false
                }
                """.formatted(ruta, metodo, cabeceras, cabecerasMulti,
                        ruta.startsWith("/") ? ruta.substring(1) : ruta, metodo, ruta);
    }

    /** Implementación mínima de {@link Context}: el handler no consulta ninguno de estos valores. */
    private static final class ContextDePrueba implements Context {
        @Override public String getAwsRequestId() { return "prueba-local"; }
        @Override public String getLogGroupName() { return "/aws/lambda/prueba"; }
        @Override public String getLogStreamName() { return "prueba"; }
        @Override public String getFunctionName() { return "bible-references"; }
        @Override public String getFunctionVersion() { return "$LATEST"; }
        @Override public String getInvokedFunctionArn() { return "arn:aws:lambda:mx-central-1:0:function:prueba"; }
        @Override public CognitoIdentity getIdentity() { return null; }
        @Override public ClientContext getClientContext() { return null; }
        @Override public int getRemainingTimeInMillis() { return 300_000; }
        @Override public int getMemoryLimitInMB() { return 1024; }
        @Override public LambdaLogger getLogger() {
            return new LambdaLogger() {
                @Override public void log(String message) { System.out.println(message); }
                @Override public void log(byte[] message) { System.out.println(new String(message, StandardCharsets.UTF_8)); }
            };
        }
    }
}
