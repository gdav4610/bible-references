package com.gdav.bible.bible_references.model;

import java.util.List;

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

    List<KeywordWithVerse> keywordsWithVerse
) {
    public SourceWordResponse {
        keywordsWithVerse = keywordsWithVerse == null ? List.of() : List.copyOf(keywordsWithVerse);
    }
}
