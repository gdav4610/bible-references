package com.gdav.bible.bible_references.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Keyword {
    private String inflectionWord;
    private String translatedWord;
    private String transliteratedWord;
    private String strongNumber;

    // Campos provenientes de bible_source_words_catalog
    private String sourceTransliteration;
    private String sourceInflection;
    private String sourceMeaning;

    // Campos provenientes de bible_compound_words_catalog
    private String compoundTransliteration;
    private String compoundInflection;
    private String compoundMeaning;
}
