package com.gdav.bible.bible_references.repository;

import com.gdav.bible.bible_references.repository.entity.CompoundWordEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CompoundWordRepository extends JpaRepository<CompoundWordEntity, String> {

    @Query("SELECT c FROM CompoundWordEntity c WHERE c.idWord = :idWord")
    CompoundWordEntity findByIdWord(@Param("idWord") String idWord);

    @Query("SELECT c FROM CompoundWordEntity c " +
            "LEFT JOIN FETCH c.keywords k " +
            "LEFT JOIN FETCH k.verseEntity v " +
            "WHERE c.idWord = :idWord " +
            "AND k.translatedWord = :translatedWord " +
            "ORDER BY v.idBook ASC, v.chapter ASC, v.verse ASC")
    CompoundWordEntity findByIdWordAndTranslatedWordWithVerses(@Param("idWord") String idWord, @Param("translatedWord") String translatedWord);


    @Query("SELECT k.translatedWord, COUNT(k) FROM CompoundWordEntity c " +
            "LEFT JOIN KeywordEntity k ON c.idWord = k.strongNumber " +
            "WHERE c.idWord = :idWord " +
            "GROUP BY k.translatedWord " +
            "ORDER BY COUNT(k) DESC")
    List<Object[]> findKeywordCountsByIdWord(@Param("idWord") String idWord);
}
