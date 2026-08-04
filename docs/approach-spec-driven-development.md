# Approach: Spec-Driven Development (Spring Boot)

Propósito y alcance
-------------------
Este documento define las reglas de desarrollo y convenciones para aplicar un enfoque de Spec-Driven Development en este proyecto Spring Boot. Su objetivo es reducir la ambigüedad entre requisitos, pruebas y código, promoviendo componentes claros y pruebas automatizadas que validen los contratos expuestos por la API.

Reglas principales
------------------
- Exposición y formatos (API): usar `@RestController` exclusivamente para controlar la entrada/salida HTTP. Los controladores no contienen lógica de negocio; se limitan a validar input mínimo, orquestar llamadas a `@Service` y transformar resultados a DTOs.
- Lógica de negocio: colocar en clases con `@Service`. Estas clases encapsulan las reglas de dominio, orquestan repositorios, y son el foco de las pruebas unitarias.
- Acceso a datos: utilizar `@Repository` y entidades/DTOs para la persistencia. Repositorios contienen consultas y mapeo a entidades JPA (o DAOs equivalentes). No deben contener lógica de negocio compleja.

Contratos por endpoint (mini-"contract")
-----------------------------------------
Para cada endpoint público documentar un contrato corto con: inputs, outputs y errores esperados.
Formato sugerido (por endpoint):
- URI, método HTTP
- Inputs: path params, query params, body (schema breve)
- Outputs: 200: body schema; otros códigos y condiciones (400, 404, 500)
- Errores: enum de errores esperados y mensajes/estructuras

Ejemplo (resumen): GET /api/strongs/{strongCode}/stats
- Inputs: path: strongCode (String), query: includeLXX (boolean)
- Output 200: SourceWordWithKeywordStatsResponse
- Errores: 404 cuando no existe; 400 cuando strongCode vacío

Convenciones y estructura
-------------------------
- Paquetes principales: `controller`, `service`, `repository`, `model`, `mapper`, `config`, `dto` (si aplica).
- Nombres: `XController`, `IXService` (interface), `XService` (implementación), `XRepository`, `XMapper`, `XResponse`, `XRequest`.
- DTOs inmutables: preferir `record` para Request y Response del controller (Java 16+). Usar clases cuando:
  - Se necesita anotación JPA o mutabilidad para frameworks.
  - Se requiere lógica adicional (validaciones complejas o métodos utilitarios).
- Mappers: centralizar mapeos entre entidades y DTOs en `mapper` (puede usarse MapStruct para reducir boilerplate).

Records en Request y Response (Controller)
------------------------------------------
Usar `record` como tipo por defecto para los DTOs de entrada y salida de controladores. Reglas específicas:

**Request records:**
- Declarar el record en el mismo paquete del controller o en un subpaquete `dto`.
- Anotar los componentes con anotaciones de Jakarta Validation directamente: `@NotBlank`, `@NotNull`, `@Size`, etc.
- Aplicar `@Valid` en el parámetro del método del controller para activar la validación.
- No incluir lógica de negocio ni transformaciones; el record solo transporta datos de entrada.
- Ejemplo:
  ```java
  public record CreateNotificationRequest(
      @NotBlank String recipient,
      @NotBlank @Size(max = 255) String message,
      @NotNull NotificationType type
  ) {}
  ```

**Response records:**
- Usar siempre `record` para respuestas: inmutabilidad garantiza que el controller no modifica la salida después de construirla.
- Nombrar con sufijo `Response`. Ejemplos: `Response`: `NotificationResponse`, `PagedNotificationResponse`.
- Para listas paginadas, encapsular en un record wrapper que incluya metadatos (`page`, `size`, `totalElements`).
- Jackson serializa `record` sin configuración extra (Spring Boot 2.7+/3.x); no agregar anotaciones `@JsonProperty` salvo que el nombre del campo deba diferir del JSON.
- Ejemplo:
  ```java
  public record NotificationResponse(
      String recipient,
      String message,
      NotificationType type,
      Instant createdAt
  ) {}
  ```

**Cuándo NO usar record para Request/Response:**
- Cuando el framework de deserialización requiere un constructor sin argumentos o setters (ej. versiones antiguas de Jackson sin módulo de records).
- Cuando la respuesta requiere polimorfismo con `@JsonSubTypes`.
- Cuando se necesita herencia de campos comunes entre múltiples DTOs (usar clase abstracta base).

Interfaces en la capa Service
------------------------------
Toda clase de servicio debe exponer una interface pública e implementarse en una clase concreta separada. Reglas:

- Definir `IXService` como interface en el mismo paquete: declara el contrato del servicio con sus métodos públicos y su documentación de excepciones esperadas (`@throws`).
- Implementar en `XService` anotada con `@Service`: contiene la lógica de negocio, dependencias inyectadas y anotaciones transaccionales.
- El `@Controller` depende siempre de la interface `IXService`, nunca de `XService`.
- Los tests unitarios del service crean una instancia de `XService` directamente con dependencias mockeadas; los tests de integración usan la interface inyectada por Spring.
- Ejemplo de estructura:
  ```java
  // interface
  public interface INotificationService {
      NotificationResponse create(CreateNotificationRequest request);
      NotificationResponse findById(UUID id);
      void delete(UUID id);
  }

  // implementación
  @Service
  @RequiredArgsConstructor
  public class NotificationService implements INotificationService {
      private final NotificationRepository repository;
      private final NotificationMapper mapper;

      @Override
      @Transactional
      public NotificationResponse create(CreateNotificationRequest request) { ... }
  }
  ```
- No crear una interface cuando el servicio es puramente infraestructural y sin lógica de negocio (ej. `EmailSenderService` que solo delega a un cliente externo); en ese caso, una clase concreta anotada con `@Service` es suficiente.

Validación y manejo de errores
------------------------------
**Validación de entrada:**
- Usar `@Valid` en los parámetros del controller para activar Jakarta Validation.
- Validaciones de dominio (reglas que dependen del estado del sistema) van en `@Service`, no en el controller.
- No duplicar validaciones: si una regla requiere acceso a base de datos, pertenece al service.

**Jerarquía de excepciones propias:**
- Definir una excepción base `AppException extends RuntimeException` con campo `errorCode` (enum o String) para identificar el tipo de error programáticamente.
- Derivar excepciones específicas del dominio:
  - `ResourceNotFoundException extends AppException` → HTTP 404
  - `BusinessRuleException extends AppException` → HTTP 422 Unprocessable Entity
  - `ExternalServiceException extends AppException` → HTTP 502 Bad Gateway
- Lanzar siempre la excepción más específica disponible; nunca lanzar `RuntimeException` directamente desde la capa de service.

**ControllerAdvice:**
- Un único `@ControllerAdvice` global maneja todas las excepciones. No dispersar `@ExceptionHandler` en controllers individuales.
- Handlers mínimos requeridos:
  - `MethodArgumentNotValidException` → 400, incluyendo la lista de campos con error.
  - `ConstraintViolationException` → 400 (para validaciones en path/query params).
  - `ResourceNotFoundException` → 404.
  - `BusinessRuleException` → 422.
  - `ExternalServiceException` → 502.
  - `Exception` (catch-all) → 500, sin exponer stack trace al cliente.
- Estructura de error response consistente (usar `record`):
  ```java
  public record ErrorResponse(
      String errorCode,
      String message,
      String path,
      Instant timestamp,
      List<FieldError> fieldErrors
  ) {
      public record FieldError(String field, String message) {}
  }
  ```

**Reglas adicionales de manejo de errores:**
- Nunca exponer stack traces, nombres de clases internas ni mensajes de excepción crudos en respuestas HTTP.
- Loggear con `log.error("context message", exception)` en el `@ControllerAdvice` para errores 5xx; para 4xx loggear a nivel `WARN` o no loggear (son errores del cliente, no del sistema).
- Incluir en el log suficiente contexto: ID de recurso, usuario (si aplica), operación en curso. Nunca loggear datos sensibles (contraseñas, tokens, PII).
- Los errores de validación (400) deben listar todos los campos inválidos en una sola respuesta, no solo el primero.
- Usar HTTP status codes semánticamente correctos; no devolver siempre 400 para todo error del cliente.
- Para errores transitorios de servicios externos, no propagar la excepción original; envolver en `ExternalServiceException` con mensaje genérico.

Transacciones y límites de servicio
-----------------------------------
- Anotar operaciones que mutan datos con `@Transactional` en la capa de `@Service` (no en `@Controller`).
- Mantener transacciones lo más cortas posible. Evitar operaciones I/O pesadas dentro de una transacción.
- Para operaciones costosas, usar paginación y límites por defecto en consultas.

Pruebas recomendadas
--------------------
- Unitarias (`@ExtendWith(MockitoExtension.class)`): probar `XService` y `@Mapper` aislados; mocks para `@Repository`.
- Integración (`@SpringBootTest` o slice tests): probar `@Controller` y `@Repository` con base de datos en memoria o testcontainers.
- Contract tests / consumer-driven: documentar y automatizar contratos por endpoint (ej. Pact o tests que validen respuestas esperadas).
- Casos borde: parámetros nulos/vacíos, límites de paginación, concurrencia en mutaciones.
- Para el `@ControllerAdvice`, escribir tests de integración que disparen cada tipo de excepción y validen el status HTTP y la estructura de `ErrorResponse`.

Observabilidad y outbox
-----------------------
- Logging: usar SLF4J. Loggear a nivel DEBUG/INFO para trazabilidad y WARN/ERROR para problemas. Evitar logs excesivos en loops.
- Métricas: instrumentar paths clave con Micrometer.
- Outbox pattern: para eventos que deben publicarse de forma confiable, escribir eventos a tabla de outbox en la misma transacción que la mutación y un componente asincrónico para publicarlos (ver `OutboxRepository`). Registrar el evento con payload mínimo y metadata.

Dependencias Maven (pom.xml)
-----------------------------
Este proyecto usa Spring Boot 3.5.x con Spring Cloud 2025.x. Las dependencias gestionadas por el BOM padre (`spring-boot-starter-parent` y `spring-cloud-dependencies`) no requieren `<version>` en el bloque `<dependency>`.

**Reglas generales para agregar dependencias:**
- Nunca especificar versión en dependencias ya gestionadas por `spring-boot-starter-parent` o el BOM de Spring Cloud declarado en `<dependencyManagement>`.
- Declarar el BOM de Spring Cloud en `<dependencyManagement>` usando `<type>pom</type>` y `<scope>import</scope>` antes de agregar cualquier `spring-cloud-*` en `<dependencies>`.
- Dependencias solo necesarias en tiempo de ejecución: agregar `<scope>runtime</scope>`.
- Herramientas de desarrollo que no deben incluirse en el artefacto final: agregar `<optional>true</optional>`.
- Dependencias de prueba: siempre con `<scope>test</scope>`.

-Utilizar las siguientes versiones del parent:
```xml
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.0</version>
    <relativePath/> <!-- lookup parent from repository -->
</parent>
```

-Utilizar las siguientes versiones de java:
```xml
<java.version>25</java.version>
<spring-cloud.version>2025.1.2</spring-cloud.version>
```

**Dependencias de uso común y cómo incluirlas:**

Validación de DTOs con Jakarta Validation:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

Testing (JUnit 5 + Mockito + Spring Test, incluido por defecto):
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
```

DevTools (recarga en caliente, solo desarrollo):
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-devtools</artifactId>
    <scope>runtime</scope>
    <optional>true</optional>
</dependency>
```

Actuator (endpoints de salud, métricas, info):
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

Spring Cloud Config Client (configuración centralizada desde Config Server):
```xml
<!-- Requiere el BOM de Spring Cloud en <dependencyManagement> -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-config</artifactId>
</dependency>
```

Persistencia JPA con base de datos relacional:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
```

Lombok (reducción de boilerplate: `@Data`, `@RequiredArgsConstructor`, etc.):
```xml
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <optional>true</optional>
</dependency>
```

MapStruct (generación de mappers en tiempo de compilación):
```xml
<dependency>
    <groupId>org.mapstruct</groupId>
    <artifactId>mapstruct</artifactId>
    <version>1.6.3</version>
</dependency>
```

Springdoc OpenAPI / Swagger UI (documentación de API):
```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>3.0.3</version>
</dependency>
```

Testcontainers (bases de datos reales en tests de integración):
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-testcontainers</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
```

**BOM de Spring Cloud (bloque `<dependencyManagement>` obligatorio para cualquier `spring-cloud-*`):**
```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-dependencies</artifactId>
            <version>${spring-cloud.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```


Ejecución asíncrona
-----------------------
Cuando se requiera la ejecución asíncrona de hilos, configurar un bean del ThreadPoolTaskExecutor en una clase de configuración como la siguiente:
```java
@Configuration
@EnableAsync
public class AsyncConfig {
    
    @Bean("XTaskExecutor")
    public Executor XTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("x-exec-");
        executor.initialize();
        return executor;
    }
    
}
```



Recomendaciones finales
-----------------------
Basarse en el siguiente archivo YAML para crear un archivo application.yaml inicial, adecuado a cada microservicio en particular para el parámetro spring.application.name:
```yaml
spring:
  application.name: x-service
  profiles.active: local-insecure
  cloud:
    config:
      # Properties used when spring.cloud.config.enabled is true
      enabled: false
      uri: http://config-service:8080
      name: ${spring.application.name}
      label: master
      profile: ${spring.profiles.active}
  config:
    # Properties used in LOCAL environment, when spring.cloud.config.enabled is false
    name: ${spring.application.name}
#---------------------------------------
# Logger configs
#---------------------------------------
logging:
  level:
    com.gdav: INFO
spring.threads.virtual.enabled: false
```


Records vs Clases (guía rápida)
-------------------------------
| Caso de uso                              | Tipo recomendado |
|------------------------------------------|------------------|
| Request DTO del controller               | `record`         |
| Response DTO del controller              | `record`         |
| Entidad JPA                              | clase            |
| DTO con polimorfismo JSON                | clase            |
| Value object sin lógica                  | `record`         |
| DTO que requiere herencia                | clase abstracta  |
| ErrorResponse del ControllerAdvice       | `record`         |

Contenedores y health checks
----------------------------
Reglas derivadas de incidentes reales en este proyecto (2026-07-31). Ver
`docs/aws-architecture.md` y los runbooks asociados para el detalle.

**Nunca asumir que un binario existe en la imagen base.**
Las imágenes `-jre-` de Eclipse Temurin son mínimas: no incluyen `curl`, `wget`, `nc` ni
`python3`. Un `HEALTHCHECK` o un `ENTRYPOINT` que invoque una de esas herramientas falla
siempre, con independencia del estado de la aplicación. Comprobar antes de escribirlo:

```bash
docker run --rm --entrypoint sh <imagen> -c "command -v curl || echo AUSENTE"
```

Si la herramienta hace falta, instalarla explícitamente en la etapa de runtime:

```dockerfile
FROM eclipse-temurin:25-jre-jammy
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*
```

**Diagnóstico: un chequeo externo que pasa mientras el interno falla apunta al mecanismo,
no a la aplicación.**
Si el balanceador reporta el target `healthy` y el contenedor sale `UNHEALTHY` contra el
mismo endpoint, el problema está en cómo se ejecuta el chequeo. Antes de tocar tiempos o
recursos, verificar el comando en sí. Confirmar además que la aplicación arranca sin
errores revisando los logs; `Started XApplication in N seconds` es la prueba de que el
fallo no es del arranque.

**El `startPeriod` debe superar el tiempo de arranque medido, no el estimado.**
Tomar el valor real de `Started XApplication in N seconds` y dejar margen amplio. En este
proyecto el arranque es de ~64 s con Hibernate, 6 repositorios JPA y Hazelcast embebido en
0,5 vCPU; el `startPeriod` quedó en 180 s.

**No dar por buena una hipótesis sin comprobarla contra el artefacto real.**
Que un contenedor sobreviva más tiempo del que sugiere `startPeriod + interval × retries`
no descarta que el comando falle: el planificador de ECS no reemplaza la task en cuanto la
marca `UNHEALTHY`, sino con una latencia observada de entre 8 y 20 minutos.

**Health checks del orquestador y del balanceador son independientes.**
El del balanceador consulta al contenedor por red y no atraviesa las reglas del listener;
el del contenedor se ejecuta dentro. Ambos pueden discrepar sin que nada lo advierta.
Conviene que apunten al mismo endpoint (`/actuator/health`) para que la discrepancia sea
diagnóstica.

Exposición de servicios detrás de un CDN
---------------------------------------
Cuando un CDN fronteda la API —en este proyecto, CloudFront enruta `/api/*` al balanceador—
el origen **no debe ser alcanzable directamente**. Si lo es, todo lo que aporte el borde
(WAF, rate limiting, caché, TLS) se esquiva apuntando al DNS del balanceador. Dos capas,
ambas necesarias:

1. **Red:** restringir el ingress del balanceador a la managed prefix list del CDN
   (`com.amazonaws.global.cloudfront.origin-facing`), no a `0.0.0.0/0`.
2. **Identidad del origen:** la capa anterior no distingue tu distribución de la de otra
   cuenta. El CDN inyecta un header secreto y el listener lo exige mediante una regla; la
   acción por defecto responde `403`.

**El orden de aplicación importa.** Primero inyectar el header en el CDN y esperar a que
despliegue en todos los edges; después crear la regla que reenvía con el header; y solo al
final cambiar la acción por defecto a `403`. Invertir los dos últimos pasos corta
producción entre un comando y el siguiente.

Consecuencia de diseño: si el CDN y la API comparten origen, **el CORS no interviene en el
camino de producción**. La configuración de orígenes permitidos sigue siendo útil para
desarrollo local y como defensa en profundidad, pero no es lo que habilita el tráfico real;
conviene no confundir una cosa con la otra al diagnosticar.

Infraestructura y fuente de verdad
----------------------------------
**Verificar qué archivo despliega realmente el pipeline antes de editarlo.**
El workflow de este proyecto ejecuta `aws ecs describe-task-definition > task-definition.json`
antes de renderizar, de modo que **sobrescribe** el archivo versionado con lo que ya está
registrado en AWS. Editar el archivo del repositorio no tiene ningún efecto: los cambios de
CPU, memoria o health check hay que registrarlos como revisión nueva.

Lo correcto es invertir esa relación —eliminar el paso de descarga y dejar que el render
parta del archivo versionado— para que los cambios de infraestructura pasen por revisión de
código. Mientras no se haga, todo cambio aplicado a mano debe quedar documentado con su
procedimiento de reversión.

Checklist para Pull Request
---------------------------
- ¿El PR cumple con un contrato definido? (docs/tests)
- ¿Los Request/Response del controller usan `record` salvo excepción justificada?
- ¿Los servicios exponen interface y la implementación está en `XService`?
- ¿El `@ControllerAdvice` cubre las nuevas excepciones introducidas?
- ¿Added/updated tests? (unit + integration si aplica)
- ¿No se añaden logs con datos sensibles? ¿Se respetan tamaños de página y límites por defecto?
- Si el PR toca el `Dockerfile` o el health check: ¿se verificó que las herramientas que invoca existen en la imagen resultante?
- Si el PR toca el arranque de la aplicación: ¿sigue el `startPeriod` por encima del tiempo real medido?
- Si el PR toca `task-definition.json`: ¿se registró además la revisión en AWS? (el pipeline sobrescribe ese archivo)
- ¿Mensajes de commit claros y atómicos? Ejemplos:
  - feat(strong-controller): add stats endpoint
  - fix(search-service): handle null query
  - test(keyword-mapper): add unit tests for edge cases

Recomendaciones finales
-----------------------
- Mantén controladores ligeros y servicios testeables.
  - Documenta contratos con OpenAPI/Swagger y manténlos sincronizados con tests.
- Revisa cambios que afecten la API con cuidado y actualiza `docs` y tests de contrato.

Este documento debe guardarse en `docs/approach-spec-driven-development.md` y revisarse periódicamente.