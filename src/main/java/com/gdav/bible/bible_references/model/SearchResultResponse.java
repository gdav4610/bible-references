package com.gdav.bible.bible_references.model;

import java.util.List;

/**
 * Respuesta del endpoint GET /api/bible/search.
 *
 * <p>Los campos {@code returnedVerses} y {@code truncated} se añadieron el 2026-08-14 al
 * acotar la búsqueda. Son <b>aditivos</b>: {@code verses} sigue siendo el primer campo y
 * conserva su forma, de modo que un cliente que solo lea esa lista no necesita cambiar.
 *
 * <p>{@code truncated} existe porque un resultado recortado sin avisar es indistinguible de
 * un resultado completo, y el usuario concluiría que la Biblia contiene menos apariciones de
 * las que contiene.
 *
 * @param verses         versículos encontrados, ya acotados al límite pedido
 * @param returnedVerses cuántos se devuelven. <b>Se deriva siempre de {@code verses}</b>; el
 *                       valor que se pase al constructor se ignora, para que no pueda
 *                       quedar descuadrado de la lista que acompaña
 * @param truncated      {@code true} si había más resultados de los que se devuelven
 */
public record SearchResultResponse(
        List<SearchResponse> verses,
        int returnedVerses,
        boolean truncated
) {
    public SearchResultResponse {
        verses = verses == null ? List.of() : List.copyOf(verses);
        returnedVerses = verses.size();
    }
}