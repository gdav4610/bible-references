package com.gdav.bible.bible_references.model;

import java.util.List;

public record SearchResponse(
        int idBook,
        int chapter,
        int verseNumber,
        String text,
        List<Keyword> keywords
) {}
