package com.gdav.bible.bible_references.repository;

import com.gdav.bible.bible_references.repository.entity.VerseEntity;
import com.gdav.bible.bible_references.repository.entity.VerseId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface VerseRepository extends JpaRepository<VerseEntity, VerseId> {

    @Query("SELECT DISTINCT verse FROM VerseEntity AS verse " +
            "LEFT JOIN FETCH verse.keywords AS keywords " +
            "LEFT JOIN FETCH keywords.sourceWordEntity AS sourceWord " +
            "LEFT JOIN FETCH keywords.compoundWordEntity AS compoundWord " +
            "WHERE verse.id.idBible = :id_bible AND verse.id.idBook = :id_book AND verse.id.chapter = :chapter " +
            "AND (:id_verse IS NULL OR verse.id.verse = :id_verse) " +
            "ORDER BY verse.id.chapter ASC, verse.id.verse ASC")
    List<VerseEntity> findAllByIdBookAndChapter(@Param("id_bible") Integer idBible,
                                                @Param("id_book") Integer idBook,
                                                @Param("chapter") Integer chapter,
                                                @Param("id_verse") Integer idVerse);
}