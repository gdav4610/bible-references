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
                    Keyword k = new Keyword(
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
                    );

                    if (e.getSourceWordEntity() != null) {
                        k.setSourceTransliteration(e.getSourceWordEntity().getTransliteration());
                        k.setSourceInflection(e.getSourceWordEntity().getInflection());
                        k.setSourceMeaning(e.getSourceWordEntity().getMeaning());
                    }

                    if (e.getCompoundWordEntity() != null) {
                        k.setCompoundTransliteration(e.getCompoundWordEntity().getTransliteration());
                        k.setCompoundInflection(e.getCompoundWordEntity().getInflection());
                        k.setCompoundMeaning(e.getCompoundWordEntity().getMeaning());
                    }

                    return k;
                })
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
