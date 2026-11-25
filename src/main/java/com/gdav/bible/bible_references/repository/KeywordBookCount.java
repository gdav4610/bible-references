package com.gdav.bible.bible_references.repository;

/**
 * Proyección del conteo de keywords agrupado por libro.
 *
 * <p>Es un {@code record} y no {@code Object[]} a propósito: el orden de las columnas y la
 * conversión del {@code Long} que devuelve {@code COUNT(…)} se quedan en esta capa, en vez
 * de aparecer como un {@code ((Number) r[1]).intValue()} en el servicio.
 */
public record KeywordBookCount(Integer idBook, Long count) {

    /** El {@code COUNT(…)} de JPQL es un {@code Long}; la respuesta expone un entero. */
    public int countAsInt() {
        return count == null ? 0 : count.intValue();
    }
}
