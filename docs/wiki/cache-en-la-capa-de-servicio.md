# Caché: por qué vive en el servicio y no en el repositorio

Estado a **2026-09-01**, tras mover las nueve cachés que colgaban de los repositorios a tres
cachés en la capa de servicio. Desplegado en la versión **14** de la Lambda.

## La regla

**La caché pertenece al punto donde una petición se convierte en una respuesta, no al punto
donde se lee una fila.**

Un `@Cacheable` sobre un método de `JpaRepository` parece la opción natural —está justo
encima de lo caro, que es la consulta— y por eso es la que sale sola. Tiene cuatro problemas,
en orden de importancia:

| Problema | Consecuencia |
|---|---|
| Guarda entidades, no respuestas | Cada acierto sigue pagando el mapeo entidad → DTO |
| Guarda objetos JPA desconectados | Grafos mutables compartidos entre invocaciones; un acceso perezoso no traído por el `FETCH` revienta fuera de la sesión |
| Una caché por consulta | Un caso de uso con cuatro consultas necesita cuatro aciertos para ahorrarse algo; si falla uno, va a la base de datos igual |
| La clave la escribe quien escribe la consulta | Y tiende a describir *los parámetros de la consulta*, no *la identidad de la petición* — ver más abajo |

Cacheando en el servicio, el valor es el `record` de respuesta: inmutable, ya mapeado, y la
clave son los parámetros de la petición. En `/api/strongs/{code}/stats` un acierto pasó de
ahorrar una consulta a ahorrar **las tres o cuatro del caso de uso completo** más todo el
armado de la respuesta.

## La clave debe ser la identidad de la petición

Este es el defecto real que había, y es el argumento más fuerte de toda la página:

```java
@Cacheable(value = "keywordTransliteratedWord",
        key = "#root.methodName + '_' + #p0 + '_' + T(java.util.Objects).hash(#p1)", ...)
SourceWordEntity findByIdWordAndTransliteratedWordWithVerses(
        String idWord, String transliteratedWord, String translatedWord, List<String> sources);
```

La clave lleva `idWord` (`#p0`) y `transliteratedWord` (`#p1`), y **omite
`translatedWord`**. Dos peticiones del mismo código Strong con distinta palabra traducida
colisionaban: la segunda recibía la respuesta de la primera.

Nadie lo detectó porque solo afectaba a los tres códigos de la lista blanca de
`WordCacheCondition` y solo a la caché en memoria de cada instancia —el controlador responde
`no-store` en ese endpoint, así que CloudFront nunca lo propagó—.

Lo interesante no es el despiste, es que **la posición invita al despiste**: en un repositorio
uno numera parámetros (`#p0`, `#p1`) y es fácil que sobre uno; en un caso de uso, la clave son
los argumentos con los que llega la petición, y omitir uno se nota. Cachear a la altura
correcta convierte esa clase de error en algo poco probable en lugar de algo detectable.

**Regla:** si dos peticiones pueden devolver respuestas distintas, la clave tiene que
distinguirlas. Si algún parámetro no cabe en la clave, es que no debía cachearse.

## El orden de los aspectos no es un detalle

Los métodos cacheados son además `@Transactional`. Los dos aspectos traen
`Ordered.LOWEST_PRECEDENCE` por defecto, así que **cuál envuelve a cuál queda sin definir**.

Si el transaccional queda por fuera, un acierto de caché sigue pidiendo una conexión al pool y
abriendo y cerrando una transacción contra RDS: exactamente el viaje que la caché existe para
evitar. La caché "funciona" —no repite la consulta— pero el ahorro se evapora, y no hay ningún
error que lo delate.

```java
@SpringBootApplication
@EnableCaching(order = Ordered.HIGHEST_PRECEDENCE)
public class BibleReferencesApplication { ... }
```

Con eso el aspecto de caché envuelve al transaccional: en un acierto no se toca la base de
datos, y en un fallo la transacción se abre por dentro, de modo que las varias consultas del
caso de uso siguen compartiendo una sola.

**Generalizable:** siempre que dos aspectos de Spring actúen sobre el mismo método y el orden
cambie el comportamiento —caché y transacción, seguridad y caché, reintentos y transacción—,
hay que fijarlo explícitamente. Lo que no se declara, no se cumple; solo se acierta.

## Cómo se prueba

Las expresiones SpEL de `@Cacheable` **no se compilan**: se evalúan al invocar el método. Una
errata en un nombre de parámetro o en el nombre del bean de condición pasa el `mvn test`
entero y falla en la primera petición de producción.

Las pruebas de servicio con Mockito no sirven para esto: construyen la clase directamente y no
pasan por los proxies de Spring, que es donde vive la caché. Hace falta una prueba que levante
un contexto real con `@EnableCaching`, los beans de condición y repositorios mockeados —en
este proyecto, `ServiceCachingTest`—, y que compruebe:

1. Que una entrada de la lista blanca **se cachea**: dos llamadas, una sola invocación del
   repositorio.
2. Que una que no está en la lista **no se cachea**: dos llamadas, dos invocaciones. Sin esto,
   una condición que devolviera siempre `true` pasaría desapercibida.
3. Que claves que deben coincidir coinciden (`includeLXX` nulo y `false` son la misma biblia).
4. Que claves que deben diferir difieren — el caso del defecto de arriba, como regresión.

Leer el `application.yaml` real con `ConfigDataApplicationContextInitializer` añade que un
nombre de caché que desaparezca de `spring.cache.cache-names` haga fallar la prueba.

## Coherencia con la caché del borde

Hay dos cachés y conviene que digan lo mismo:

- La **de memoria**, por instancia de Lambda, gobernada por `VerseCacheCondition` /
  `WordCacheCondition`.
- La **de CloudFront**, gobernada por el `Cache-Control` que emite el controlador.

En este proyecto comparten lista a propósito: un capítulo que merece cachearse localmente
merece cachearse en el borde, y ampliarla es tocar un único sitio. Pero la del borde tiene una
restricción que la local no tiene: **la política de caché de CloudFront no lleva las query
strings en la clave**, así que cualquier endpoint cuya respuesta dependa de un parámetro de
consulta debe salir con `no-store` explícito, no con ausencia de cabecera. Que hoy no se
cachee por tener `DefaultTTL=0` en la distribución es una propiedad de configuración remota,
editable sin pasar por revisión de código; `no-store` sobrevive a que alguien la cambie.

## Configuración resultante

```yaml
spring:
  cache:
    type: caffeine
    cache-names: chapters, strongStats, strongDetails
    caffeine:
      spec: maximumSize=500,expireAfterWrite=24h
```

`maximumSize` es un tope de seguridad, no un límite operativo: lo que entra ya lo restringen
las condiciones SpEL —nueve capítulos y tres códigos Strong—. Importa porque la caché vive en
la memoria del proceso y conviene que tenga techo.
