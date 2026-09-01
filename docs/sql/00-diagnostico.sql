-- Paso 0: diagnóstico y línea base. NO modifica nada.
-- Ejecutar antes de tocar la base de datos: el plan "antes" no se puede recuperar después.

\echo ''
\echo '=== 1. Identidad y versión ==='
SELECT current_user, session_user, version();

\echo ''
\echo '=== 2. Roles de los que soy miembro (busco rds_superuser) ==='
SELECT r.rolname
FROM pg_roles r
WHERE pg_has_role(current_user, r.oid, 'member')
ORDER BY 1;

\echo ''
\echo '=== 3. Extensiones instaladas (busco pg_trgm y unaccent) ==='
SELECT extname, extversion, nspname AS esquema
FROM pg_extension e
JOIN pg_namespace n ON n.oid = e.extnamespace
ORDER BY extname;

\echo ''
\echo '=== 4. Tamaño de la tabla ==='
SELECT count(*) AS versiculos,
       pg_size_pretty(pg_total_relation_size('bible_schema.bible_verses')) AS tamano_total
FROM bible_schema.bible_verses;

\echo ''
\echo '=== 5. Índices que ya existen sobre bible_verses ==='
SELECT indexname, indexdef
FROM pg_indexes
WHERE schemaname = 'bible_schema' AND tablename = 'bible_verses'
ORDER BY indexname;

\echo ''
\echo '=== 6. PLAN ANTES — esto es la línea base, guárdala ==='
\echo '(usa public.unaccent, que es lo que hay desplegado hoy)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT verse.id_bible, verse.id_book, verse.chapter, verse.verse, verse.text
FROM bible_schema.bible_verses AS verse
WHERE verse.id_bible = 1
  AND public.unaccent(verse.text) ~* public.unaccent('\melohim')
ORDER BY verse.id_book ASC, verse.chapter ASC, verse.verse ASC
LIMIT 501;

\echo ''
\echo '=== 7. Cuántos versículos devuelve de verdad (para comparar después) ==='
SELECT count(*) AS coincidencias
FROM bible_schema.bible_verses
WHERE id_bible = 1
  AND public.unaccent(text) ~* public.unaccent('\melohim');
