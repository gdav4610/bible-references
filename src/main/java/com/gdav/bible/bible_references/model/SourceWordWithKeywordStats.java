package com.gdav.bible.bible_references.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SourceWordWithKeywordStats {
    private String idWord;
    private String transliteration;
    private String inflection;
    private String meaning;

    // nuevo campo idParent
    private String idParent;

    private List<KeywordStats> keywordStats;
}
