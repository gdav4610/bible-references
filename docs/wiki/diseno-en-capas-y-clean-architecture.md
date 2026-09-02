# Diseño en capas: cuánta ceremonia compensa

Estado a **2026-09-01**, tras evaluar si convenía adoptar clean architecture en este
proyecto. La respuesta fue *no en su forma canónica, sí en cuatro principios sueltos*, y lo
que sigue es el razonamiento, para poder revisarlo cuando cambien las condiciones.

## El tamaño decide la ceremonia

Cifras del proyecto en el momento de decidir:

| Dato | Valor |
|---|---|
| Líneas de código de producción | 2.615 |
| Endpoints públicos | 4 |
| Operaciones de escritura | 0 — la API es de solo lectura |
| Consumidores | 1 (la SPA de `escrituraclave.com`) |
| Almacenes de datos | 1 (PostgreSQL) |

Con ese perfil, la ceremonia completa —dominio sin Spring, puertos y adaptadores, casos de
uso, modelo duplicado con mappers de ida y vuelta— **se paga en cada cambio y no compra
nada**: no hay intención de cambiar de base de datos, y no hay reglas de negocio con
invariantes que proteger de la infraestructura. El coste no es escribirla, es mantener dos
jerarquías paralelas sincronizadas para siempre.

## El argumento de latencia apunta al revés de lo que parece

Es tentador rechazar la arquitectura hexagonal "porque añade capas y las capas cuestan
tiempo". En runtime eso es ruido: unas cuantas copias de objetos por petición no se miden.

Donde sí cuesta es en el **arranque**: más clases y más beans es más tiempo de INIT. Y en
esta arquitectura el INIT es exactamente lo que SnapStart congela en el snapshot, así que el
efecto real de añadir ceremonia es *publicar versiones más lentas*, no *servir peticiones más
lentas*.

Conclusión práctica: **descartar la ceremonia por coste de mantenimiento, no por
rendimiento.** Si algún día hace falta, no será la latencia lo que lo impida.

## Los cuatro principios que sí se adoptan

Ninguno exige reestructurar el proyecto y todos tienen coste de latencia cero o negativo.

### 1. Que la capa de negocio no dependa de la de configuración

`BibleService` llamaba a `VerseCacheCondition.idBibleFor(...)`: una regla de dominio —la
traducción `includeLXX` → `idBible = 2`— viviendo dentro de una clase cuyo propósito es
decidir política de caché de CDN. La inversión correcta es extraer esa regla a un tipo de
dominio del que dependan *ambos*, servicio y condición.

Es literalmente la D de SOLID, y aquí son unas quince líneas.

### 2. Un value object donde hoy hay manipulación de cadenas

En `StrongService` aparecía tres veces el mismo patrón: `strongCode.contains(" ")`,
`toUpperCase()`, `split(" ")`. Eso no es manipulación de texto, es un concepto del dominio
—un código Strong simple o compuesto— disfrazado. Un `StrongCode.of(raw)` que sepa
distinguirlos y exponga `first()` / `second()` / `reversed()` quita unas 40 líneas y hace
testeable sin base de datos la parte que hoy solo se prueba a través de ella.

### 3. Que el detalle de persistencia no cruce hacia arriba

Dos síntomas concretos:

- `StrongService` recibía `List<Object[]>` y hacía `((Number) r[1]).intValue()` cuatro veces.
- Las cachés guardaban **entidades JPA** en lugar de respuestas (ver
  [`cache-en-la-capa-de-servicio.md`](cache-en-la-capa-de-servicio.md)).

La corrección no es una capa nueva: es que el repositorio devuelva proyecciones o `record`, y
que lo que salga de él ya no sepa a JPA.

### 4. Cachear en la frontera del caso de uso, no en la consulta

Tiene página propia. Resumen: la caché pertenece al punto donde una petición se convierte en
una respuesta, no al punto donde se lee una fila.

## Lo que explícitamente no se hace

| Tentación | Por qué no, aquí |
|---|---|
| Duplicar `model/` en DTO de HTTP y modelo de dominio | Dos jerarquías paralelas y sus mappers, para 4 endpoints de lectura, es impuesto puro. Los `record` actuales sirven a las dos cosas |
| Introducir MapStruct | `KeywordMapper` es estático y sin dependencias: arranca más rápido, que en Lambda importa, y ya funciona |
| Un módulo Maven por capa | Multiplica el tiempo de build y el artefacto sale igual |
| Interfaces por todo | Las de servicio ya existen por convención del proyecto y no cuestan nada; añadir más sin una segunda implementación a la vista es ruido |

## Cuándo volver a plantearlo

La decisión depende de las cifras de arriba. Conviene reabrirla si aparece alguna de estas:

- **Un segundo consumidor** con necesidades distintas a las de la SPA: ahí sí empieza a doler
  que el `record` de respuesta y el modelo interno sean el mismo objeto.
- **Escrituras con invariantes de negocio**: hoy no hay ninguna: es una API de lectura.
- **Un segundo almacén** (un índice de búsqueda, una caché distribuida con estado propio).
- **Más de una persona tocando el código a la vez**: parte del valor de la ceremonia es
  coordinar, y con un solo autor ese valor es cero.

Mientras ninguna se cumpla, la forma actual —controlador fino, servicio con la lógica,
repositorio con las consultas, `record` inmutables entre ellos— es la proporcionada.
