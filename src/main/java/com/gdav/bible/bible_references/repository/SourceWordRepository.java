package com.gdav.bible.bible_references.repository;

import com.gdav.bible.bible_references.repository.entity.SourceWordEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SourceWordRepository extends JpaRepository<SourceWordEntity, String> {

    @Query("SELECT new com.gdav.bible.bible_references.repository.KeywordWordCount(k.transliteratedWord, COUNT(k)) " +
            "FROM SourceWordEntity s " +
            "LEFT JOIN s.keywords k " +
            "WHERE s.idWord = :idWord " +
            "AND k.source IN :sources " +
            "GROUP BY k.transliteratedWord " +
            "ORDER BY COUNT(k) DESC")
    List<KeywordWordCount> findKeywordCountsByIdWord(@Param("idWord") String idWord, @Param("sources") List<String> sources);

    @Query("SELECT new com.gdav.bible.bible_references.repository.KeywordWordCount(k.translatedWord, COUNT(k)) " +
            "FROM SourceWordEntity s " +
            "LEFT JOIN s.keywords k " +
            "WHERE s.idWord = :idWord " +
            "AND k.source IN :sources " +
            "GROUP BY k.translatedWord " +
            "ORDER BY COUNT(k) DESC " +
            "LIMIT 25")
    List<KeywordWordCount> findKeywordTranslatedCountsByIdWord(@Param("idWord") String idWord, @Param("sources") List<String> sources);

    /**
     * Apariciones por libro. {@code JOIN} interno y no {@code LEFT JOIN} como sus hermanos: el
     * filtro por {@code k.source} ya los convierte en internos de hecho, y así no hay filas con
     * {@code k} nulo que el servicio tenga que esquivar.
     */
    @Query("SELECT new com.gdav.bible.bible_references.repository.KeywordBookCount(k.idBook, COUNT(k)) " +
            "FROM SourceWordEntity s " +
            "JOIN s.keywords k " +
            "WHERE s.idWord = :idWord " +
            "AND k.source IN :sources " +
            "AND k.idBook IS NOT NULL " +
            "GROUP BY k.idBook " +
            "ORDER BY COUNT(k) DESC, k.idBook ASC")
    List<KeywordBookCount> findKeywordCountsByBook(@Param("idWord") String idWord, @Param("sources") List<String> sources);


    @Query("SELECT DISTINCT s FROM SourceWordEntity s " +
            "LEFT JOIN FETCH s.keywords k " +
            "LEFT JOIN FETCH k.verseEntity v " +
            "WHERE s.idWord = :idWord " +
            "AND ((:transliteratedWord IS NULL AND :translatedWord IS NULL) " +
            "     OR k.transliteratedWord = :transliteratedWord OR k.translatedWord = :translatedWord) " +
            "AND k.source IN :sources " +
            "AND (:idBook IS NULL OR k.idBook = :idBook) " +
            "ORDER BY v.id.idBook ASC, v.id.chapter ASC, v.id.verse ASC")
    SourceWordEntity findByIdWordAndTransliteratedWordWithVerses(@Param("idWord") String idWord, @Param("transliteratedWord") String transliteratedWord, @Param("translatedWord") String translatedWord, @Param("sources") List<String> sources, @Param("idBook") Integer idBook);


}
