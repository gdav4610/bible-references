package com.gdav.bible.bible_references.model;

import java.util.List;

public record Verse(
        int verseNumber,
        String text,
        List<Keyword> keywords
) {}
