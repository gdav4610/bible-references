package com.gdav.bible.bible_references.model;

import java.util.List;

public record SourceWordWithKeywordStatsResponse(
        String idWord,
        String transliteration,
        String inflection,
        String meaning,
        String idParent,
        String idParentSec,
        String parentMeaning,
        String parentSecMeaning,
        Integer firstAppBook,
        Integer firstAppChapter,
        Integer firstAppVerse,
        List<KeywordStats> keywordStats,
        List<KeywordStats> keywordStatsTranslated,
        List<RelatedCompoundWord> compoundRelatedList
) {
    public SourceWordWithKeywordStatsResponse {
        keywordStats = keywordStats == null ? List.of() : List.copyOf(keywordStats);
        keywordStatsTranslated = keywordStatsTranslated == null ? List.of() : List.copyOf(keywordStatsTranslated);
        compoundRelatedList = compoundRelatedList == null ? List.of() : List.copyOf(compoundRelatedList);
    }
}