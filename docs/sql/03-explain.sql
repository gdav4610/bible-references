-- Solo el EXPLAIN, para repetir la medición cuantas veces haga falta. NO modifica nada.
--
--   psql ... -f docs/sql/03-explain.sql                    -> usa 'elohim'
--   psql ... -v termino=espiritu -f docs/sql/03-explain.sql -> usa otro término

-- OJO con el término que elijas: la búsqueda va contra verse.text, que es el texto en
-- ESPAÑOL, no contra transliteraciones. 'elohim' devuelve 0 filas —verificado contra
-- producción el 2026-09-01— y medir un plan que no encuentra nada dice poco. Por eso el
-- término por defecto es uno que sí aparece en el texto.
\if :{?termino}
\else
  \set termino espiritu
\endif

\echo ''
\echo '=== Término bajo prueba ==='
SELECT :'termino' AS termino;

\echo ''
\echo '=== PLAN con f_unaccent (el que usará el código nuevo) ==='
\echo '(busca: Bitmap Index Scan on ix_bible_verses_text_trgm)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT verse.id_bible, verse.id_book, verse.chapter, verse.verse, verse.text
FROM bible_schema.bible_verses AS verse
WHERE verse.id_bible = 1
  AND public.f_unaccent(verse.text) ~* public.f_unaccent('\m' || :'termino')
ORDER BY verse.id_book ASC, verse.chapter ASC, verse.verse ASC
LIMIT 501;

\echo ''
\echo '=== PLAN con unaccent (lo que corre hoy en produccion, para comparar) ==='
\echo '(este debe seguir saliendo Seq Scan: unaccent no se puede indexar)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT verse.id_bible, verse.id_book, verse.chapter, verse.verse, verse.text
FROM bible_schema.bible_verses AS verse
WHERE verse.id_bible = 1
  AND public.unaccent(verse.text) ~* public.unaccent('\m' || :'termino')
ORDER BY verse.id_book ASC, verse.chapter ASC, verse.verse ASC
LIMIT 501;
