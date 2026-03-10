package com.gdav.bible.bible_references.repository;

import com.gdav.bible.bible_references.repository.entity.SourceWordEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.cache.annotation.Cacheable;

import java.util.List;

@Repository
public interface SourceWordRepository extends JpaRepository<SourceWordEntity, String> {

    @Cacheable(value = "sourceWords", key = "#root.methodName + '_' + #p0 + '_' + T(java.util.Objects).hash(#p1)",
            condition = "@wordCacheCondition.shouldCacheWithJustOneParam(#p0)")
    @Query("SELECT DISTINCT s FROM SourceWordEntity s " +
            "LEFT JOIN FETCH s.keywords k " +
            "LEFT JOIN FETCH k.verseEntity v " +
            "WHERE s.idWord = :idWord " +
            "AND k.source IN :sources " +
            "ORDER BY v.id.idBook ASC, v.id.chapter ASC, v.id.verse ASC")
    SourceWordEntity findByIdWordWithVerses(@Param("idWord") String idWord, @Param("sources") List<String> sources);


    @Cacheable(value = "keywordCounts", key = "#root.methodName + '_' + #p0 + '_' + T(java.util.Objects).hash(#p1)",
            condition = "@wordCacheCondition.shouldCacheWithJustOneParam(#p0)")
    @Query("SELECT k.translatedWord, COUNT(k) FROM SourceWordEntity s " +
            "LEFT JOIN s.keywords k " +
            "WHERE s.idWord = :idWord " +
            "AND k.source IN :sources " +
            "GROUP BY k.translatedWord " +
            "ORDER BY COUNT(k) DESC")
    List<Object[]> findKeywordCountsByIdWord(@Param("idWord") String idWord, @Param("sources") List<String> sources);


    @Query("SELECT DISTINCT s FROM SourceWordEntity s " +
            "LEFT JOIN FETCH s.keywords k " +
            "LEFT JOIN FETCH k.verseEntity v " +
            "WHERE s.idWord = :idWord " +
            "AND k.translatedWord = :translatedWord " +
            "AND k.source IN :sources " +
            "ORDER BY v.id.idBook ASC, v.id.chapter ASC, v.id.verse ASC")
    SourceWordEntity findByIdWordAndTranslatedWordWithVerses(@Param("idWord") String idWord, @Param("translatedWord") String translatedWord, @Param("sources") List<String> sources);


}
