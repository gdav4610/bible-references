# Índices de expresión en PostgreSQL

Estado a **2026-09-01**, tras indexar la búsqueda de `/api/bible/search` con un índice GIN de
trigramas. El procedimiento concreto, con sus scripts, está en
`docs/runbook-indice-trigram-search.md`; esta página recoge lo que generaliza.

## El problema de partida

```sql
WHERE verse.id_bible = :id_bible
  AND public.unaccent(verse.text) ~* public.unaccent(:patron)
```

Ninguna parte de eso puede usar un índice: PostgreSQL recorre las ~31.000 filas, llama a
`unaccent()` en cada una y evalúa la expresión regular sobre el resultado. Como el endpoint
va con `no-store`, **cada búsqueda paga el escaneo completo**; no hay caché que lo tape.

## `unaccent` no se puede indexar, y la salida cambia la consulta

```
ERROR:  functions in index expression must be marked IMMUTABLE
```

`unaccent()` es `STABLE`, no `IMMUTABLE`, porque depende de un diccionario que puede
recargarse. La salida estándar es un envoltorio que **fija el diccionario** y por tanto sí
puede declararse inmutable con honestidad:

```sql
CREATE OR REPLACE FUNCTION public.f_unaccent(text)
RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
AS $function$ SELECT public.unaccent('public.unaccent'::regdictionary, $1) $function$;

CREATE INDEX CONCURRENTLY ix_bible_verses_text_trgm
    ON bible_schema.bible_verses
    USING gin (public.f_unaccent(text) gin_trgm_ops);
```

Y aquí está lo que hay que retener: **un índice de expresión solo se usa si la consulta
escribe la expresión exactamente igual**. No basta con que sea equivalente. Por eso el
repositorio pasó a llamar a `f_unaccent` en los dos lados de la comparación.

> El cuerpo va entre `$function$` y no entre `$$`: `$$` escrito dentro de comillas dobles en
> Git Bash o PowerShell se expande al PID del proceso, y la función queda corrupta **sin dar
> error**. Ejecutar siempre desde archivo con `psql -f`, nunca copiando a `-c`.

## Consecuencia de despliegue: el código pasa a depender de un objeto de la base de datos

Este es el efecto de segundo orden que conviene anticipar. Antes, la consulta funcionaba
contra cualquier base de datos con `unaccent`; ahora exige que exista `f_unaccent`. Si se
despliega el código primero, la búsqueda responde 500 con `function public.f_unaccent(text)
does not exist`.

**Orden obligatorio:**

1. Crear extensión y función.
2. Crear el índice y `ANALYZE`.
3. Verificar el plan.
4. **Solo entonces** publicar el código.

Lo que hace este orden cómodo es que los pasos 1–3 son **compatibles hacia atrás**: la versión
desplegada sigue llamando a `public.unaccent` y no se entera de nada. Se pueden aplicar con
calma y esperar.

Y tiene una propiedad que conviene notar porque no siempre se da: **la vuelta atrás sigue
siendo segura**. La base de datos entiende las dos expresiones, así que volver a la versión
anterior de la Lambda funciona sin tocar nada del esquema. Cuando un cambio de esquema y uno
de código se acoplan, merece la pena buscar deliberadamente esta forma —aditiva, no
sustitutiva—, porque es la que deja el rollback intacto.

## Trampas al verificar

**`ANALYZE` después de crear el índice.** Sin estadísticas de la expresión, el planificador
puede seguir eligiendo `Seq Scan`. Es la causa más engañosa porque desaparece sola cuando pasa
el autovacuum, así que "a veces funciona".

**Medir con un término que encuentre algo.** Aquí se midió primero con `elohim`, que devuelve
**cero filas**: la búsqueda va contra `verse.text`, el texto en español, no contra
transliteraciones. La comparación de planes seguía siendo válida —seq scan frente a índice—
pero los tiempos no decían nada. Un plan sobre un conjunto vacío es una medición de mentira.

**Los trigramas necesitan tres caracteres.** Con términos de una o dos letras no hay nada que
extraer y el escaneo es inevitable. No es un fallo del índice; conviene saberlo antes de
perseguirlo.

**Comprobar que el resultado no cambia.** Contar las coincidencias con la expresión vieja y
con la nueva en la misma consulta, y exigir que coincidan. Un índice que acelera y altera el
resultado es peor que ninguno.

## Qué no arregla un índice

- **No reduce el tamaño de la respuesta.** El `LIMIT` sigue haciendo falta: el techo de 6 MB
  de una invocación síncrona de Lambda no se mueve.
- **No hace cacheable lo que no lo era.** `/search` sigue con `no-store` porque su respuesta
  depende de las query strings y la política del CDN no las lleva en la clave.
- **No es búsqueda de texto completo.** Si algún día se quieren varias palabras, lematización
  o ranking por relevancia, la herramienta es `tsvector` + `plainto_tsquery`, y eso cambia la
  semántica actual (`\m` + palabra es «empieza por»). Sería otro trabajo, no una variante de
  este.

## Deuda que esto dejó a la vista

El esquema de esta base de datos **no está en el repositorio**: no hay Flyway ni Liquibase,
`spring.jpa.hibernate.ddl-auto` es `none`, y hasta ahora no existía ningún `.sql` versionado.
Los scripts de `docs/sql/` son la primera pieza de esquema en control de versiones, y siguen
siendo documentación ejecutable a mano, no migraciones reproducibles.

Si va a haber más cambios de esquema, añadir Flyway sale más barato que seguir acumulando
runbooks. El síntoma de que hace falta es este mismo: un cambio de código que **solo funciona**
si alguien se acordó de ejecutar un SQL a mano en el orden correcto.
