package com.gdav.bible.bible_references.model;

public record KeywordWithVerse(
        String inflectionWord,
        String translatedWord,
        String transliteratedWord,
        Integer idBook,
        Integer chapter,
        Integer verseNumber,
        String verseText,
        Integer appearanceInVerse
) {}
