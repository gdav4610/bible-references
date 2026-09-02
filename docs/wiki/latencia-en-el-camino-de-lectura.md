# Latencia: trabajo que el cliente no pidió

Estado a **2026-09-01**. Recoge lo encontrado al revisar el camino de una petición de lectura
de principio a fin. Nada de esto es arquitectura: son cosas que estaban ahí y que se pagaban
en cada petición.

## La regla

**Nada que el cliente no necesite para recibir su respuesta debe estar en el camino
síncrono.** Suena obvio; lo que no es obvio es cuánto se cuela sin que nadie lo decida, porque
cada pieza se añadió por separado y ninguna parecía cara.

La forma de encontrarlo es leer el camino completo de una petición —controlador, servicio,
repositorio, configuración— preguntando en cada línea *¿esto lo necesita quien está esperando
la respuesta?*.

## Caso 1: un efecto colateral que nadie consumía

Cada `GET` grababa un evento en una tabla de outbox:

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void record(String eventType, String payload) { ... }
```

`REQUIRES_NEW` significa **una segunda conexión del pool** mientras la de lectura sigue
tomada, más un `INSERT` y un `COMMIT`, contra una `db.t4g.micro` alcanzada desde una Lambda
dentro de la VPC. Ocurría incluso cuando la respuesta venía de la caché.

Y al buscar quién leía esa tabla: nadie. Ni publicador, ni planificador, ni consumidor. El
patrón outbox estaba a medias —la mitad que cuesta, sin la mitad que sirve—.

**Lo generalizable:** un patrón implementado a medias suele ser peor que no implementarlo,
porque tiene todo su coste y ninguna de sus garantías. Antes de dejar un efecto colateral en
el camino de una petición, comprobar que alguien consume lo que produce.

## Por qué `@Async` no es la salida en Lambda

La reacción instintiva es "no lo quito, lo hago asíncrono". **En Lambda eso no funciona.**

El entorno de ejecución se **congela** cuando la función devuelve la respuesta. Un trabajo
lanzado a otro hilo se queda a medias: puede reanudarse en la siguiente invocación, minutos u
horas después, o desaparecer si el entorno se recicla. No hay error, no hay traza; simplemente
a veces se escribe y a veces no.

Las salidas reales, por orden de sencillez:

1. **Quitarlo**, si nadie lo consume.
2. **Dejar la traza en el log**, que ya va a CloudWatch por el camino del runtime.
3. **Sacarlo a un destino asíncrono de verdad** —una cola, `Lambda destinations`, un evento—
   si el dato importa.

Lo que no vale es fiarse de un hilo propio dentro de una función que va a ser congelada.

## Caso 2: configuración de depuración en producción

```yaml
spring:
  jpa:
    show-sql: true
    properties.hibernate.format_sql: true
```

Formatea e imprime **cada** consulta por stdout, en el camino de la petición. En Lambda eso es
CPU facturada, volumen en un log group que además no tiene retención configurada —crece para
siempre— y ruido que tapa las trazas que sí importan.

Para depurar una consulta puntual, `logging.level.org.hibernate.SQL: DEBUG` de forma temporal;
no dejarlo encendido de manera permanente.

## Caso 3: reconstruir lo constante en cada petición

`getBookName` construía un `HashMap` de 66 entradas —siete `Map.of` y siete `putAll`— **en
cada petición de capítulo**, para hacer una búsqueda y tirarlo.

```java
private static final Map<Integer, String> BOOK_NAMES = Map.ofEntries(...);
```

El ahorro por petición es pequeño; lo que lo hace interesante es el efecto con SnapStart: una
constante `static final` se inicializa **al cargar la clase**, es decir, dentro de la fase INIT
que el snapshot congela. Se paga una vez al publicar la versión y nunca más.

**Generalizable:** en esta arquitectura, mover trabajo del camino de la petición al INIT es
doblemente bueno, porque el INIT ya no se paga en cada arranque en frío.

## Orden de revisión sugerido

Cuando toque volver a mirar la latencia, en este orden:

1. **Viajes a la base de datos que no piden datos** (escrituras de telemetría, contadores,
   auditoría) — son los más caros y los más fáciles de quitar.
2. **Transacciones que se abren sin necesidad** — ver el orden de aspectos en
   [`cache-en-la-capa-de-servicio.md`](cache-en-la-capa-de-servicio.md).
3. **Consultas sin índice** — ver
   [`indices-de-expresion-en-postgres.md`](indices-de-expresion-en-postgres.md).
4. **E/S de logging en el camino**.
5. **Asignaciones por petición de cosas constantes**.

Los tres primeros son órdenes de magnitud; los dos últimos son higiene. Conviene no empezar
por el final, que es la tentación porque es lo más fácil de arreglar.
