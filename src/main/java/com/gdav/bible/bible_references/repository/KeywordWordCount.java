package com.gdav.bible.bible_references.repository;

/**
 * Proyección del conteo de keywords agrupado por palabra, transliterada o traducida según el
 * query que la produzca.
 *
 * <p>Sustituye al {@code Object[]} de los cuatro queries de conteo, por la misma razón que
 * {@link KeywordBookCount}: el orden de las columnas y el {@code ((Number) r[1]).intValue()}
 * se quedan en esta capa.
 */
public record KeywordWordCount(String word, Long count) {

    /** La palabra, o {@code ""} si la keyword no la tiene: así se exponía antes. */
    public String wordOrEmpty() {
        return word == null ? "" : word;
    }

    /** El {@code COUNT(…)} de JPQL es un {@code Long}; la respuesta expone un entero. */
    public int countAsInt() {
        return count == null ? 0 : count.intValue();
    }
}
