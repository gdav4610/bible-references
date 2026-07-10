package com.gdav.bible.bible_references.model;

import java.util.List;

/** Respuesta del endpoint GET /api/bible/search. */
public record SearchResultResponse(
        List<SearchResponse> verses
) {
    public SearchResultResponse {
        verses = verses == null ? List.of() : List.copyOf(verses);
    }
}