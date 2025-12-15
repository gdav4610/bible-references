package com.gdav.bible.bible_references.repository;

import com.gdav.bible.bible_references.repository.entity.VerseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface VerseRepository extends JpaRepository<VerseEntity, Integer> {

    @Query("SELECT DISTINCT verse FROM VerseEntity AS verse " +
            "LEFT JOIN FETCH verse.keywords AS keywords " +
            "LEFT JOIN FETCH keywords.sourceWordEntity AS sourceWord " +
            "WHERE verse.idBible = 1 AND verse.idBook = :id_book AND verse.chapter = :chapter " +
            "ORDER BY verse.chapter ASC, verse.verse ASC")
    List<VerseEntity> findAllByIdBookAndChapter(@Param("id_book") Integer idBook, @Param("chapter") Integer chapter);
}