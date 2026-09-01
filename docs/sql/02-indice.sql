-- Paso 2: el índice GIN de trigramas, ANALYZE y verificación del plan.
--
-- IMPORTANTE: ejecutar con psql -f, SIN la opción -1 / --single-transaction.
-- CREATE INDEX CONCURRENTLY no puede correr dentro de una transacción; psql en
-- autocommit (el modo por defecto) va bien.
--
-- Sigue siendo compatible hacia atrás: la versión desplegada usa public.unaccent
-- y simplemente no aprovecha el índice. Nada se rompe si te paras aquí.

\echo ''
\echo '=== 1. Restos de un intento anterior (deberia salir 0 filas) ==='
SELECT c.relname, i.indisvalid
FROM pg_class c
JOIN pg_index i ON i.indexrelid = c.oid
WHERE c.relname = 'ix_bible_verses_text_trgm' AND NOT i.indisvalid;

\echo ''
\echo '=== 2. Creando el indice (tarda segundos con ~31k filas) ==='
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_bible_verses_text_trgm
    ON bible_schema.bible_verses
    USING gin (public.f_unaccent(text) gin_trgm_ops);

\echo ''
\echo '=== 3. ANALYZE: sin esto el planificador puede seguir eligiendo Seq Scan ==='
ANALYZE bible_schema.bible_verses;

\echo ''
\echo '=== 4. El indice existe y es VALIDO (indisvalid debe ser t) ==='
SELECT c.relname AS indice,
       i.indisvalid AS valido,
       pg_size_pretty(pg_relation_size(c.oid)) AS tamano
FROM pg_class c
JOIN pg_index i ON i.indexrelid = c.oid
WHERE c.relname = 'ix_bible_verses_text_trgm';

\echo ''
\echo '=== 5. PLAN DESPUES — comparalo con el del paso 0 ==='
\echo '(busca Bitmap Index Scan on ix_bible_verses_text_trgm)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT verse.id_bible, verse.id_book, verse.chapter, verse.verse, verse.text
FROM bible_schema.bible_verses AS verse
WHERE verse.id_bible = 1
  AND public.f_unaccent(verse.text) ~* public.f_unaccent('\melohim')
ORDER BY verse.id_book ASC, verse.chapter ASC, verse.verse ASC
LIMIT 501;

\echo ''
\echo '=== 6. Mismos resultados que antes del indice (deben coincidir) ==='
SELECT count(*) FILTER (WHERE public.unaccent(text)   ~* public.unaccent('\melohim'))   AS con_unaccent,
       count(*) FILTER (WHERE public.f_unaccent(text) ~* public.f_unaccent('\melohim')) AS con_f_unaccent
FROM bible_schema.bible_verses
WHERE id_bible = 1;

\echo ''
\echo '=== 7. Otros terminos, incluido uno con acento y uno corto ==='
SELECT termino,
       (SELECT count(*)
        FROM bible_schema.bible_verses v
        WHERE v.id_bible = 1
          AND public.f_unaccent(v.text) ~* public.f_unaccent('\m' || termino)) AS coincidencias
FROM (VALUES ('elohim'), ('Dios'), ('espiritu'), ('espíritu'), ('luz'), ('fe')) AS t(termino);
