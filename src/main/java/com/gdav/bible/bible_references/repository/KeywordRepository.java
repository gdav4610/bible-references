package com.gdav.bible.bible_references.repository;

import com.gdav.bible.bible_references.repository.entity.KeywordEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface KeywordRepository extends JpaRepository<KeywordEntity, Long> {

}