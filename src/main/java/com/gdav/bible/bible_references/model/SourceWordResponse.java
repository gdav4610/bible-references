package com.gdav.bible.bible_references.model;

import java.util.List;

/**
 * Respuesta de GET /api/strongs/{code}/details.
 *
 * <p>{@code truncated} se añadió el 2026-10-20, al dejar de exigir un filtro de palabra: sin
 * él, un código frecuente devuelve miles de apariciones y la lista se acota. Es aditivo —el
 * cliente que solo lea {@code keywordsWithVerse} no cambia— y existe por la misma razón que en
 * {@link SearchResultResponse}: un resultado recortado sin avisar no se distingue de uno
 * completo.
 */
public record SourceWordResponse (
    String idWord,
    String transliteration,
    String inflection,
    String meaning,

    // nuevo campo idParent
    String idParent,
    // nuevo campo idParentSec
    String idParentSec,
    // nuevo campo parentMeaning
    String parentMeaning,
    // nuevo campo parentSecMeaning
    String parentSecMeaning,

    List<KeywordWithVerse> keywordsWithVerse,

    boolean truncated
) {
    public SourceWordResponse {
        keywordsWithVerse = keywordsWithVerse == null ? List.of() : List.copyOf(keywordsWithVerse);
    }
}
