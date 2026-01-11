package com.gdav.bible.bible_references.repository;

import com.gdav.bible.bible_references.repository.entity.OutboxEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OutboxRepository extends JpaRepository<OutboxEventEntity, Long> {
}

