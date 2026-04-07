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
    // nuevo campo idParentSec
    private String idParentSec;
    // nuevo campo parentMeaning
    private String parentMeaning;
    // nuevo campo parentSecMeaning
    private String parentSecMeaning;
    // nuevo campo firstAppBook
    private Integer firstAppBook;
    // nuevo campo firstAppChapter
    private Integer firstAppChapter;
    // nuevo campo firstAppVerse
    private Integer firstAppVerse;

    private List<KeywordStats> keywordStats;

    private List<KeywordStats> keywordStatsTranslated;

    private List<RelatedCompoundWord> compoundRelatedList;
}
