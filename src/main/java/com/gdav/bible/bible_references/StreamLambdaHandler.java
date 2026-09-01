package com.gdav.bible.bible_references;

import com.amazonaws.serverless.exceptions.ContainerInitializationException;
import com.amazonaws.serverless.proxy.model.AwsProxyRequest;
import com.amazonaws.serverless.proxy.model.AwsProxyResponse;
import com.amazonaws.serverless.proxy.spring.SpringBootLambdaContainerHandler;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Punto de entrada cuando la aplicación se ejecuta dentro de AWS Lambda.
 *
 * <p>Traduce los eventos de Lambda a la API de Servlet y los entrega a Spring MVC, de modo
 * que los {@code @RestController}, el {@code @ControllerAdvice} y el
 * {@code OriginValidationFilter} funcionan sin cambios. En ECS esta clase no se usa: allí
 * el punto de entrada sigue siendo {@link BibleReferencesApplication}.
 *
 * <p><b>Handler a configurar en la función:</b>
 * {@code com.gdav.bible.bible_references.StreamLambdaHandler::handleRequest}
 *
 * <h2>Por qué el handler de payload v1 y no el v2</h2>
 * El formato del evento lo decide <b>quién invoca la función</b>, y en {@code mx-central-1}
 * eso está más restringido de lo que parece:
 *
 * <ul>
 *   <li><b>Function URLs</b> (payload v2): no existen en la región.</li>
 *   <li><b>API Gateway HTTP API</b> (payload v2): tampoco —{@code CreateApi} responde
 *       <i>"HTTP APIs are not available in this AWS region"</i>—.</li>
 *   <li><b>API Gateway REST API</b> (payload v1): disponible. Es la que se usa.</li>
 * </ul>
 *
 * Por eso {@code getAwsProxyHandler} y no {@code getHttpApiV2ProxyHandler}: la integración
 * proxy de una REST API emite el formato 1.0, y el handler v2 fallaría al deserializarlo.
 * Si algún día la región gana Function URLs, este es el punto exacto que habría que
 * cambiar —junto con el formato de los eventos de {@code StreamLambdaHandlerTest}—.
 *
 * <h2>Relación con SnapStart</h2>
 * La inicialización va en un bloque estático a propósito: se ejecuta durante la fase INIT,
 * que es justo lo que SnapStart captura en el snapshot. Gracias a eso el arranque de ~48 s
 * de Spring se paga una vez al publicar la versión y no en cada arranque en frío.
 *
 * <p>El snapshot congela también el pool de conexiones de HikariCP, cuyos sockets estarían
 * muertos al restaurar. <b>No hace falta código propio para eso:</b> con {@code org.crac} en
 * el classpath, Spring Boot activa {@code HikariCheckpointRestoreLifecycle}, que suspende y
 * vacía el pool antes del checkpoint y lo reanuda después.
 */
public class StreamLambdaHandler implements RequestStreamHandler {

    private static final SpringBootLambdaContainerHandler<AwsProxyRequest, AwsProxyResponse> HANDLER;

    static {
        try {
            HANDLER = SpringBootLambdaContainerHandler
                    .getAwsProxyHandler(BibleReferencesApplication.class);
        } catch (ContainerInitializationException e) {
            // Sin contexto de Spring no hay nada que servir. Fallar aquí hace que Lambda
            // marque la inicialización como fallida, que es el comportamiento correcto:
            // es preferible a que la función acepte peticiones y las conteste con error.
            throw new IllegalStateException("No se pudo inicializar el contexto de Spring Boot", e);
        }
    }

    @Override
    public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {
        HANDLER.proxyStream(input, output, context);
    }
}
