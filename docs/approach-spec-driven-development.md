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
- Nombres: `XController`, `XService`, `XRepository`, `XMapper`, `XResponse`, `XRequest`.
- DTOs inmutables: prefer `record` para DTOs simples (Java 16+) cuando no se necesita compatibilidad con frameworks que requieren mutabilidad o proxies (ej. algunas bibliotecas de serialización antiguas). Usar clases cuando:
  - Se necesita anotación JPA o mutabilidad para frameworks.
  - Se requiere lógica adicional (validaciones complejas o métodos utilitarios).
- Mappers: centralizar mapeos entre entidades y DTOs en `mapper` (puede usarse MapStruct para reducir boilerplate).

Validación y manejo de errores
------------------------------
- Validaciones de contrato: usar `@Valid` y anotaciones de Jakarta Validation en DTOs. Validación adicional en `@Service` si depende del estado del dominio.
- Manejo global de errores: `@ControllerAdvice` con `@ExceptionHandler` para convertir excepciones a `ResponseEntity` con estructura de error consistente (codigo, message, details, timestamp).
- Errores esperados: devolver 4xx con mensajes claros; 5xx para excepciones no previstas. Registrar excepciones con suficiente contexto sin exponer datos sensibles.

Transacciones y límites de servicio
-----------------------------------
- Anotar operaciones que mutan datos con `@Transactional` en la capa de `@Service` (no en `@Controller`).
- Mantener transacciones lo más cortas posible. Evitar operaciones I/O pesadas dentro de una transacción.
- Para operaciones costosas, usar paginación y límites por defecto en consultas.

Pruebas recomendadas
--------------------
- Unitarias (`@ExtendWith(MockitoExtension.class)`): probar `@Service` y `@Mapper` aislados; mocks para `@Repository`.
- Integración (`@SpringBootTest` o slice tests): probar `@Controller` y `@Repository` con base de datos en memoria o testcontainers.
- Contract tests / consumer-driven: documentar y automatizar contratos por endpoint (ej. Pact o tests que validen respuestas esperadas).
- Casos borde: parámetros nulos/vacíos, límites de paginación, concurrencia en mutaciones.

Observabilidad y outbox
-----------------------
- Logging: usar SLF4J. Loggear a nivel DEBUG/INFO para trazabilidad y WARN/ERROR para problemas. Evitar logs excesivos en loops.
- Métricas: instrumentar paths clave con Micrometer.
- Outbox pattern: para eventos que deben publicarse de forma confiable, escribir eventos a tabla de outbox en la misma transacción que la mutación y un componente asincrónico para publicarlos (ver `OutboxRepository`). Registrar el evento con payload mínimo y metadata.

Records vs Clases (guía rápida)
-------------------------------
- Usar `record` para DTOs inmutables, value objects y respuestas simples. Ventajas: menos boilerplate, igualdad por contenido.
- Preferir clase cuando: necesitas compatibilidad con frameworks (JPA entities), constructores personalizados, herencia, o añadir lógica/validaciones en la clase.

Checklist para Pull Request
---------------------------
- ¿El PR cumple con un contrato definido? (docs/tests)
- ¿Added/updated tests? (unit + integration si aplica)
- ¿No se añaden logs con datos sensibles? ¿Se respetan tamaños de página y límites por defecto?
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

