-- Paso 1: extensiones y envoltorio inmutable de unaccent.
-- Requiere el usuario MAESTRO: en RDS solo él puede CREATE EXTENSION.
-- Compatible hacia atrás: la versión desplegada hoy sigue usando public.unaccent
-- y no se ve afectada por nada de esto.

CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS unaccent WITH SCHEMA public;

-- La forma de DOS argumentos —con el diccionario nombrado explícitamente— es lo que
-- permite declararla IMMUTABLE: ya no depende de cuál sea el diccionario por defecto,
-- y por tanto se puede indexar.
CREATE OR REPLACE FUNCTION public.f_unaccent(text)
RETURNS text
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
STRICT
AS $function$ SELECT public.unaccent('public.unaccent'::regdictionary, $1) $function$;

\echo ''
\echo '=== Comprobación 1: f_unaccent hace lo mismo que unaccent ==='
\echo '(las tres columnas deben salir en t)'
SELECT public.unaccent('Éxodo José') = public.f_unaccent('Éxodo José') AS acentos_ok,
       public.unaccent('elohim')     = public.f_unaccent('elohim')     AS sin_acentos_ok,
       public.f_unaccent(NULL) IS NULL                                 AS nulos_ok;

\echo ''
\echo '=== Comprobación 2: la función está marcada IMMUTABLE ==='
\echo '(provolatile debe ser i; si sale s, el índice del paso 2 fallará)'
SELECT proname, provolatile, proparallel, proisstrict
FROM pg_proc
WHERE proname = 'f_unaccent';

\echo ''
\echo '=== Comprobación 3: mismos resultados que la consulta actual ==='
\echo '(las dos cuentas deben coincidir, y con la del paso 0)'
SELECT count(*) FILTER (WHERE public.unaccent(text)   ~* public.unaccent('\melohim'))   AS con_unaccent,
       count(*) FILTER (WHERE public.f_unaccent(text) ~* public.f_unaccent('\melohim')) AS con_f_unaccent
FROM bible_schema.bible_verses
WHERE id_bible = 1;
