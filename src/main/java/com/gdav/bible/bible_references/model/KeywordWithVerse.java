package com.gdav.bible.bible_references.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class KeywordWithVerse {
    private String inflectionWord;
    private String translatedWord;
    private String transliteratedWord;

    // Campos del versículo
    private Integer idBook;
    private Integer chapter;
    private Integer verseNumber;
    private String verseText;
    private Integer appearanceInVerse;
}
