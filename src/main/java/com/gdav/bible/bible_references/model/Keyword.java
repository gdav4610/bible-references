package com.gdav.bible.bible_references.model;

public record Keyword(
        String inflectionWord,
        String translatedWord,
        String transliteratedWord,
        String strongNumber,

        // Campos provenientes de bible_source_words_catalog
        String sourceTransliteration,
        String sourceInflection,
        String sourceMeaning,

        // Campos provenientes de bible_compound_words_catalog
        String compoundTransliteration,
        String compoundInflection,
        String compoundMeaning
) {
}
