package com.gdav.bible.bible_references.mapper;

import com.gdav.bible.bible_references.model.Keyword;
import com.gdav.bible.bible_references.model.KeywordWithVerse;
import com.gdav.bible.bible_references.model.RelatedCompoundWord;
import com.gdav.bible.bible_references.repository.entity.CompoundWordEntity;
import com.gdav.bible.bible_references.repository.entity.KeywordEntity;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class KeywordMapper {

    private KeywordMapper() {}

    /**
     * Convierte una lista de entidades a la lista de modelos de dominio.
     * Devuelve una lista vacía si la entrada es null o está vacía.
     */
    public static List<Keyword> toKeywordList(List<KeywordEntity> entities) {
        if (Objects.isNull(entities) || entities.isEmpty()) {
            return Collections.emptyList();
        }

        return entities.stream()
                .map(e -> {
                    String sourceTransliteration = null;
                    String sourceInflection = null;
                    String sourceMeaning = null;

                    if (e.getSourceWordEntity() != null && e.getSourceWordEntity().getTransliteration() != null) {
                        sourceTransliteration = e.getSourceWordEntity().getTransliteration();
                        sourceInflection = e.getSourceWordEntity().getInflection();
                        sourceMeaning = e.getSourceWordEntity().getMeaning();
                    }

                    String compoundTransliteration = null;
                    String compoundInflection = null;
                    String compoundMeaning = null;

                    if (e.getCompoundWordEntity() != null && e.getCompoundWordEntity().getTransliteration() != null) {
                        compoundTransliteration = e.getCompoundWordEntity().getTransliteration();
                        compoundInflection = e.getCompoundWordEntity().getInflection();
                        compoundMeaning = e.getCompoundWordEntity().getMeaning();
                    }

                    return new Keyword(
                            e.getInflectionWord(),
                            e.getTranslatedWord(),
                            e.getTransliteratedWord(),
                            e.getStrongNumber(),
                            sourceTransliteration,
                            sourceInflection,
                            sourceMeaning,
                            compoundTransliteration,
                            compoundInflection,
                            compoundMeaning
                    );
                })
                .collect(Collectors.toList());
    }

    /**
     * Convierte una lista de entidades a la lista de modelos de dominio.
     * Devuelve una lista vacía si la entrada es null o está vacía.
     */
    public static List<Keyword> toSimpleKeywordList(List<KeywordEntity> entities) {
        if (Objects.isNull(entities) || entities.isEmpty()) {
            return Collections.emptyList();
        }

        return entities.stream()
                .map(e -> new Keyword(
                        e.getInflectionWord(),
                        e.getTranslatedWord(),
                        e.getTransliteratedWord(),
                        e.getStrongNumber(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                ))
                .collect(Collectors.toList());
    }

    /**
     * Convierte una lista de entidades a la lista de modelos de dominio.
     * Devuelve una lista vacía si la entrada es null o está vacía.
     */
    public static List<KeywordWithVerse> toKeywordWithVerseList(List<KeywordEntity> entities) {
        if (Objects.isNull(entities) || entities.isEmpty()) {
            return Collections.emptyList();
        }

        return entities.stream()
                .map(e -> {
                    KeywordWithVerse k = new KeywordWithVerse(
                            e.getInflectionWord(),
                            e.getTranslatedWord() == null ? "" : e.getTranslatedWord(),
                            e.getTransliteratedWord(),
                            // idBook
                            e.getVerseEntity() == null ? null : e.getVerseEntity().getIdBook(),
                            // chapter
                            e.getVerseEntity() == null ? null : e.getVerseEntity().getChapter(),
                            // verseNumber
                            e.getVerseEntity() == null ? null : e.getVerseEntity().getVerse(),
                            // verseText
                            e.getVerseEntity() == null ? null : e.getVerseEntity().getText(),
                            // appearanceInVerse
                            e.getAppearanceInVerse() == null ? null : e.getAppearanceInVerse()
                    );

                    return k;
                })
                .collect(Collectors.toList());
    }



    /**
     * Convierte una lista de entidades a la lista de modelos de dominio.
     * Devuelve una lista vacía si la entrada es null o está vacía.
     */
    public static List<RelatedCompoundWord> toCompoundWordList(List<CompoundWordEntity> entities) {
        if (Objects.isNull(entities) || entities.isEmpty()) {
            return Collections.emptyList();
        }

        return entities.stream()
                .map(e -> {
                    RelatedCompoundWord k = new RelatedCompoundWord(
                            e.getIdWord(),
                            e.getTransliteration(),
                            e.getMeaning()
                    );

                    return k;
                })
                .collect(Collectors.toList());
    }

}
