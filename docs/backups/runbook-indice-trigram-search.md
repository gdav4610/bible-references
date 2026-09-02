# Runbook — índice de trigramas para `/api/bible/search`

Objetivo: que la búsqueda deje de recorrer la tabla entera de versículos aplicando una
expresión regular fila a fila, y pase por un índice GIN de trigramas.

**Estado: preparado, sin aplicar.** El código ya está cambiado en el repositorio; la base de
datos no. Mientras no se ejecuten los pasos 1 y 2, la versión desplegada tiene que seguir
siendo la anterior. Ver *Orden de aplicación*.

## Por qué

La consulta de `SearchRepository` filtra así:

```sql
WHERE verse.id_bible = :id_bible
  AND public.unaccent(verse.text) ~* public.unaccent(:translatedWord)
```

Ninguna parte de eso puede usar un índice. PostgreSQL recorre las ~31.000 filas de
`bible_schema.bible_verses`, llama a `unaccent()` en cada una y evalúa la expresión regular
sobre el resultado. Es el endpoint más caro de la API, y además va con `no-store`, así que
**nunca se cachea**: cada búsqueda paga el escaneo completo.

`pg_trgm` sabe indexar expresiones regulares: descompone el patrón en trigramas, los busca en
el índice GIN y solo evalúa la regex sobre las filas candidatas.

## El obstáculo, y por qué el código cambia

No se puede indexar `unaccent(text)` directamente:

```
ERROR:  functions in index expression must be marked IMMUTABLE
```

`unaccent()` es `STABLE`, no `IMMUTABLE`, porque su resultado depende de un diccionario que
puede recargarse. La salida estándar es un envoltorio que fija el diccionario y por tanto sí
es inmutable.

Y como el índice es *de expresión*, PostgreSQL solo lo usa cuando la consulta escribe la
expresión **exactamente igual**. De ahí que `SearchRepository` pase a llamar a
`public.f_unaccent` en los dos lados de la comparación. El comportamiento no cambia:
`f_unaccent` hace lo mismo que `unaccent` con el diccionario por defecto.

## Orden de aplicación

Importa, y en el sentido incómodo: **el código nuevo no funciona sin la función**. Si se
despliega antes, la búsqueda responde 500 con `function public.f_unaccent(text) does not
exist` hasta que se cree.

1. Crear extensión y función (paso 1).
2. Crear el índice (paso 2).
3. Verificar con `EXPLAIN` que el plan cambió (paso 3).
4. **Solo entonces**, publicar la versión de la Lambda con el código nuevo.

Los pasos 1 y 2 son compatibles hacia atrás: la versión actualmente desplegada sigue usando
`public.unaccent` y no se entera de nada.

## Los scripts

Los comandos de abajo están además en `docs/sql/`, listos para `psql -f`, con sus
comprobaciones incorporadas:

| Script | Qué hace | Modifica |
|---|---|---|
| `00-diagnostico.sql` | Identidad, privilegios, extensiones, tamaño y **plan ANTES** | No |
| `01-funcion.sql` | Extensiones y `f_unaccent`, con verificación de equivalencia | Sí |
| `02-indice.sql` | Índice, `ANALYZE` y **plan DESPUÉS** | Sí |

Conviene ejecutarlos por archivo y no copiando el SQL a mano: el cuerpo de la función va
entre `$function$`, y `$$` escrito dentro de comillas dobles en Git Bash se expande al PID
del proceso, con lo que la función quedaría corrupta sin dar error.

## Acceso a la base de datos

`database-2` no tiene IP pública y su grupo de seguridad solo admite tráfico desde dentro de
la VPC, así que hace falta port forwarding por SSM o un bastion. Y está **parada de 23:10 a
07:40 (CDMX)** por el apagado nocturno: fuera de esa ventana no hay a qué conectarse.

Los pasos 1 y 2 exigen el **usuario maestro**: en RDS solo él puede `CREATE EXTENSION`.

## Paso 1 — extensión y envoltorio inmutable

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;
-- unaccent ya debería estar: la consulta actual la usa. Por si acaso:
CREATE EXTENSION IF NOT EXISTS unaccent WITH SCHEMA public;

CREATE OR REPLACE FUNCTION public.f_unaccent(text)
RETURNS text
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
STRICT
AS $$ SELECT public.unaccent('public.unaccent'::regdictionary, $1) $$;
```

La forma de dos argumentos —con el diccionario nombrado explícitamente— es lo que permite
declararla `IMMUTABLE` con honestidad: ya no depende de cuál sea el diccionario por defecto.

Comprobación rápida de que hace lo mismo que antes:

```sql
SELECT public.unaccent('Éxodo José') = public.f_unaccent('Éxodo José');  -- t
```

## Paso 2 — el índice

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_bible_verses_text_trgm
    ON bible_schema.bible_verses
    USING gin (public.f_unaccent(text) gin_trgm_ops);

ANALYZE bible_schema.bible_verses;
```

`CONCURRENTLY` **no puede ir dentro de una transacción**: si el cliente abre una por defecto,
ejecutarlo suelto. Con ~31.000 filas la construcción es cuestión de segundos y un
`CREATE INDEX` normal también valdría; `CONCURRENTLY` solo evita bloquear escrituras, que
aquí no hay.

El índice no incluye `id_bible` a propósito: solo tiene dos valores, así que no aporta
selectividad, y meterlo obligaría además a instalar `btree_gin`. El filtro por `id_bible` se
aplica sobre las pocas filas que devuelve el índice.

## Paso 3 — verificar que el plan cambió

Antes y después, con el mismo término:

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT verse.id_bible, verse.id_book, verse.chapter, verse.verse, verse.text
FROM bible_schema.bible_verses AS verse
WHERE verse.id_bible = 1
  AND public.f_unaccent(verse.text) ~* public.f_unaccent('\melohim')
ORDER BY verse.id_book, verse.chapter, verse.verse
LIMIT 501;
```

Lo que se busca:

- **Antes:** `Seq Scan on bible_verses` con `Rows Removed by Filter` en decenas de miles.
- **Después:** `Bitmap Index Scan on ix_bible_verses_text_trgm` seguido de un `Bitmap Heap
  Scan` con `Recheck Cond`.

Si sigue saliendo `Seq Scan`, las causas probables, por orden:

| Síntoma | Causa |
|---|---|
| El plan ignora el índice con cualquier término | La consulta no llama a `f_unaccent` en el lado izquierdo, o lo llama con otro esquema |
| Solo con términos de 1 o 2 letras | Un trigrama son 3 caracteres: no hay nada que extraer y el escaneo es inevitable |
| Tras crear el índice, sin `ANALYZE` | El planificador no tiene estadísticas de la expresión |

El tercero es el que más despista, porque desaparece solo al cabo de un rato cuando el
autovacuum pasa.

## Reversión

El índice y la función se pueden quitar sin tocar nada más, **siempre que antes se vuelva a
la versión anterior de la Lambda** (la que llama a `public.unaccent`):

```sql
DROP INDEX CONCURRENTLY IF EXISTS bible_schema.ix_bible_verses_text_trgm;
DROP FUNCTION IF EXISTS public.f_unaccent(text);
```

Dejar la función y borrar solo el índice también es válido y es la reversión menos arriesgada
si lo que se quiere es descartar el índice sin redesplegar: la consulta sigue siendo correcta,
solo vuelve a ser lenta.

## Lo que este cambio no arregla

- **La búsqueda sigue sin cachearse.** Va con `no-store` porque el resultado depende de `q` y
  de `limit`, y la política de caché de CloudFront no lleva las query strings en la clave.
- **El `LIMIT` sigue haciendo falta.** El índice acelera encontrar los versículos, no reduce
  el tamaño de la respuesta, y el techo de 6 MB de una invocación síncrona de Lambda sigue ahí.
- **Si lo que se quiere es búsqueda por palabras de verdad** —lematización, varias palabras,
  ranking por relevancia—, la herramienta es `tsvector` + `plainto_tsquery`, no trigramas.
  Cambia la semántica actual (`\m` + palabra es «empieza por»), así que sería otro trabajo,
  no una variante de este.

## Deuda que este runbook hace visible

El esquema de esta base de datos **no está en el repositorio**: no hay Flyway ni Liquibase,
`spring.jpa.hibernate.ddl-auto` es `none`, y no existe ningún `.sql` versionado. Esta es la
primera pieza de esquema que queda escrita en el control de versiones, y lo está en un
runbook, que es documentación, no una migración que alguien pueda ejecutar de forma
reproducible. Si va a haber más cambios de esquema, conviene añadir Flyway antes que seguir
acumulando runbooks.
