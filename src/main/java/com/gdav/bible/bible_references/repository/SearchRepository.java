package com.gdav.bible.bible_references.repository;

import com.gdav.bible.bible_references.repository.entity.VerseEntity;
import com.gdav.bible.bible_references.repository.entity.VerseId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SearchRepository extends JpaRepository<VerseEntity, VerseId> {

    @Query(value = "SELECT DISTINCT " +
            "verse.id_bible    AS idBible, " +
            "verse.id_book     AS idBook, " +
            "verse.chapter     AS chapter, " +
            "verse.verse       AS verse, " +
            "verse.text        AS text, " +
            "keywords.strong_number        AS strongNumber, " +
            "keywords.inflection_word      AS inflectionWord, " +
            "keywords.transliterated_word  AS transliteratedWord, " +
            "keywords.translated_word      AS translatedWord, " +
            "keywords.appearance_in_verse  AS appearanceInVerse " +
            "FROM bible_schema.bible_verses AS verse " +
            "LEFT JOIN bible_schema.bible_word_references AS keywords ON verse.id_bible = keywords.id_bible AND verse.id_book = keywords.id_book  AND verse.chapter = keywords.chapter  AND verse.verse = keywords.verse " +
            "WHERE verse.id_bible = :id_bible AND public.unaccent(verse.text) ~* public.unaccent(:translatedWord) " +
//            "AND (keywords.translated_word IS NULL OR public.unaccent(keywords.translated_word) ~* public.unaccent(:translatedWord)  ) " +
            "ORDER BY verse.id_book ASC, verse.chapter ASC, verse.verse ASC, keywords.appearance_in_verse ASC", nativeQuery = true)
    List<SearchProjection> findAllByWord(@Param("id_bible") Integer idBible, @Param("translatedWord") String translatedWord);

}