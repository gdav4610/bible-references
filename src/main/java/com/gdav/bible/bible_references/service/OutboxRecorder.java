package com.gdav.bible.bible_references.service;

import com.gdav.bible.bible_references.repository.OutboxRepository;
import com.gdav.bible.bible_references.repository.entity.OutboxEventEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Persiste eventos en la tabla de outbox en una transacción independiente
 * ({@link Propagation#REQUIRES_NEW}). Así el registro del evento no interfiere con la
 * transacción de solo lectura de las consultas (un {@code INSERT} en una transacción
 * {@code readOnly} sería rechazado por la base de datos).
 */
@Component
public class OutboxRecorder {

    private final OutboxRepository outboxRepository;

    public OutboxRecorder(OutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String eventType, String payload) {
        outboxRepository.save(new OutboxEventEntity(eventType, payload, LocalDateTime.now()));
    }
}