package com.gdav.bible.bible_references.model;

import java.util.List;

/** Respuesta del endpoint GET /api/bible/chapter/{idBook}/{chapter}. */
public record ChapterResponse(
        String book,
        int chapter,
        List<Verse> verses
) {
    public ChapterResponse {
        verses = verses == null ? List.of() : List.copyOf(verses);
    }
}