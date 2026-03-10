package com.gdav.bible.bible_references.repository;

import com.gdav.bible.bible_references.repository.entity.CompoundWordEntity;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CompoundWordRepository extends JpaRepository<CompoundWordEntity, String> {

    @Cacheable(value = "compoundWords", key = "#root.methodName + '_' + #p0",
            condition = "@wordCacheCondition.shouldCacheWithJustOneParam(#p0)")
    @Query("SELECT c FROM CompoundWordEntity c WHERE c.idWord = :idWord")
    CompoundWordEntity findByIdWord(@Param("idWord") String idWord);

    @Cacheable(value = "compoundWordCounts", key = "#root.methodName + '_' + #p0 + '_' + T(java.util.Objects).hash(#p1)",
            condition = "@wordCacheCondition.shouldCacheWithJustOneParam(#p0)")
    @Query("SELECT k.translatedWord, COUNT(k) FROM CompoundWordEntity c " +
            "LEFT JOIN KeywordEntity k ON c.idWord = k.strongNumber " +
            "WHERE c.idWord = :idWord " +
            "AND k.source IN :sources " +
            "GROUP BY k.translatedWord " +
            "ORDER BY COUNT(k) DESC")
    List<Object[]> findKeywordCountsByIdWord(@Param("idWord") String idWord, @Param("sources") List<String> sources);

    @Query("SELECT c FROM CompoundWordEntity c " +
            "LEFT JOIN FETCH c.keywords k " +
            "LEFT JOIN FETCH k.verseEntity v " +
            "WHERE c.idWord = :idWord " +
            "AND k.translatedWord = :translatedWord " +
            "AND k.source IN :sources " +
            "ORDER BY v.id.idBook ASC, v.id.chapter ASC, v.id.verse ASC")
    CompoundWordEntity findByIdWordAndTranslatedWordWithVerses(@Param("idWord") String idWord, @Param("translatedWord") String translatedWord, @Param("sources") List<String> sources);


}
