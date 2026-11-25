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

    @Query("SELECT c FROM CompoundWordEntity c WHERE (c.idWord LIKE %:firstKey%  OR c.idWord LIKE %:secondKey%) AND c.idWord <> :idWord ")
    List<CompoundWordEntity> findLikeIdWord(@Param("firstKey") String firstKey,@Param("secondKey") String secondKey, @Param("idWord") String idWord);

    @Query("SELECT new com.gdav.bible.bible_references.repository.KeywordWordCount(k.transliteratedWord, COUNT(k)) " +
            "FROM CompoundWordEntity c " +
            "LEFT JOIN KeywordEntity k ON c.idWord = k.strongNumber " +
            "WHERE (c.idWord = :idWord ) " +
            "AND k.source IN :sources " +
            "GROUP BY k.transliteratedWord " +
            "ORDER BY COUNT(k) DESC")
    List<KeywordWordCount> findKeywordTransliteratedCountsByIdWord(@Param("idWord") String idWord, @Param("sources") List<String> sources);


    @Query("SELECT new com.gdav.bible.bible_references.repository.KeywordWordCount(k.translatedWord, COUNT(k)) " +
            "FROM CompoundWordEntity c " +
            "LEFT JOIN KeywordEntity k ON c.idWord = k.strongNumber " +
            "WHERE (c.idWord = :idWord ) " +
            "AND k.source IN :sources " +
            "GROUP BY k.translatedWord " +
            "ORDER BY COUNT(k) DESC " +
            "LIMIT 25")
    List<KeywordWordCount> findKeywordTranslatedCountsByIdWord(@Param("idWord") String idWord, @Param("sources") List<String> sources);

    /** Apariciones por libro. */
    @Query("SELECT new com.gdav.bible.bible_references.repository.KeywordBookCount(k.idBook, COUNT(k)) " +
            "FROM CompoundWordEntity c " +
            "JOIN KeywordEntity k ON c.idWord = k.strongNumber " +
            "WHERE c.idWord = :idWord " +
            "AND k.source IN :sources " +
            "AND k.idBook IS NOT NULL " +
            "GROUP BY k.idBook " +
            "ORDER BY COUNT(k) DESC, k.idBook ASC")
    List<KeywordBookCount> findKeywordCountsByBook(@Param("idWord") String idWord, @Param("sources") List<String> sources);


    @Query("SELECT c FROM CompoundWordEntity c " +
            "LEFT JOIN FETCH c.keywords k " +
            "LEFT JOIN FETCH k.verseEntity v " +
            "WHERE c.idWord = :idWord " +
            "AND ((:transliteratedWord IS NULL AND :translatedWord IS NULL) " +
            "     OR k.transliteratedWord = :transliteratedWord OR k.translatedWord = :translatedWord) " +
            "AND k.source IN :sources " +
            "AND (:idBook IS NULL OR k.idBook = :idBook) " +
            "ORDER BY v.id.idBook ASC, v.id.chapter ASC, v.id.verse ASC")
    CompoundWordEntity findByIdWordAndTransliteratedWordWithVerses(@Param("idWord") String idWord, @Param("transliteratedWord") String transliteratedWord, @Param("translatedWord") String translatedWord, @Param("sources") List<String> sources, @Param("idBook") Integer idBook);



    @Query("SELECT c FROM CompoundWordEntity c " +
            "LEFT JOIN FETCH c.keywords k " +
            "LEFT JOIN FETCH k.verseEntity v " +
            "WHERE k.translatedWord = :translatedWord " +
            "AND k.source IN :sources " +
            "ORDER BY v.id.idBook ASC, v.id.chapter ASC, v.id.verse ASC")
    CompoundWordEntity findByTranslatedWordWithVerses( @Param("translatedWord") String translatedWord, @Param("sources") List<String> sources);

}
