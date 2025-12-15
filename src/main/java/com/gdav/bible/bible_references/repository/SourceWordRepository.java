package com.gdav.bible.bible_references.repository;

import com.gdav.bible.bible_references.repository.entity.SourceWordEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SourceWordRepository extends JpaRepository<SourceWordEntity, String> {

    @Query("SELECT DISTINCT s FROM SourceWordEntity s " +
            "LEFT JOIN FETCH s.keywords k " +
            "LEFT JOIN FETCH k.verseEntity v " +
            "WHERE s.idWord = :idWord " +
            "ORDER BY v.idBook ASC, v.chapter ASC, v.verse ASC")
    SourceWordEntity findByIdWordWithVerses(@Param("idWord") String idWord);


    @Query("SELECT DISTINCT s FROM SourceWordEntity s " +
            "LEFT JOIN FETCH s.keywords k " +
            "LEFT JOIN FETCH k.verseEntity v " +
            "WHERE s.idWord = :idWord " +
            "AND k.translatedWord = :translatedWord " +
            "ORDER BY v.idBook ASC, v.chapter ASC, v.verse ASC")
    SourceWordEntity findByIdWordAndTranslatedWordWithVerses(@Param("idWord") String idWord, @Param("translatedWord") String translatedWord);


    @Query("SELECT k.translatedWord, COUNT(k) FROM SourceWordEntity s " +
            "JOIN s.keywords k " +
            "WHERE s.idWord = :idWord " +
            "GROUP BY k.translatedWord " +
            "ORDER BY COUNT(k) DESC")
    List<Object[]> findKeywordCountsByIdWord(@Param("idWord") String idWord);
}
