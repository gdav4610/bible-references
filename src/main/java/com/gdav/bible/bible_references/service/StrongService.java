// Crear StrongService que encapsula la lógica actualmente en StrongController
package com.gdav.bible.bible_references.service;

import com.gdav.bible.bible_references.exception.ResourceNotFoundException;
import com.gdav.bible.bible_references.mapper.KeywordMapper;
import com.gdav.bible.bible_references.model.*;
import com.gdav.bible.bible_references.repository.CompoundWordRepository;
import com.gdav.bible.bible_references.repository.OutboxRepository;
import com.gdav.bible.bible_references.repository.SourceWordRepository;
import com.gdav.bible.bible_references.repository.entity.CompoundWordEntity;
import com.gdav.bible.bible_references.repository.entity.OutboxEventEntity;
import com.gdav.bible.bible_references.repository.entity.SourceWordEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class StrongService {

    private final SourceWordRepository sourceWordRepository;
    private final CompoundWordRepository compoundWordRepository;
    private final OutboxRepository outboxRepository;
    private static final Logger logger = LoggerFactory.getLogger(StrongService.class);

    public StrongService(SourceWordRepository sourceWordRepository, CompoundWordRepository compoundWordRepository, OutboxRepository outboxRepository) {
        this.sourceWordRepository = sourceWordRepository;
        this.compoundWordRepository = compoundWordRepository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional(readOnly = true)
    public SourceWordWithKeywordStatsResponse getStrongKeywordsGrouped(String strongCode, Boolean includeLXX) {
        if (strongCode == null || strongCode.trim().isEmpty()) {
            throw new IllegalArgumentException("strongCode is required");
        }

        boolean includeLxx = includeLXX != null && includeLXX;
        List<String> sources = includeLxx ? List.of("HEBREW AT", "TR", "LXX") : List.of("HEBREW AT", "TR");
        String keyUpper = strongCode.toUpperCase();

        String firstKey = strongCode;
        String secondKey = strongCode;
        if (strongCode.contains(" ")) {
            firstKey = firstKey.split(" ")[0];
            secondKey = secondKey.split(" ")[1];
        }
        List<CompoundWordEntity> compoundRelatedList = compoundWordRepository.findLikeIdWord(firstKey, secondKey, strongCode);

        if (strongCode.contains(" ")) {
            String reversedStrongCode = secondKey + " " + firstKey;
            CompoundWordEntity compound = compoundWordRepository.findByIdWord(keyUpper);
            if (compound == null) {
                throw new ResourceNotFoundException("Compound strong code not found: " + keyUpper);
            }

            List<Object[]> rows = compoundWordRepository.findKeywordTransliteratedCountsByIdWord(keyUpper, sources, reversedStrongCode);
            List<KeywordStats> stats = new ArrayList<>();
            if (rows != null && !rows.isEmpty()) {
                stats = rows.stream()
                        .map(r -> new KeywordStats(r[0] == null ? "" : r[0].toString(), null, r[1] == null ? 0 : ((Number) r[1]).intValue()))
                        .sorted(Comparator.comparing(KeywordStats::count).reversed())
                        .collect(Collectors.toList());
            }

            List<Object[]> rowsT = compoundWordRepository.findKeywordTranslatedCountsByIdWord(keyUpper, sources, reversedStrongCode);
            List<KeywordStats> statsT = new ArrayList<>();
            if (rowsT != null && !rowsT.isEmpty()) {
                statsT = rowsT.stream()
                        .map(r -> new KeywordStats(null, r[0] == null ? "" : r[0].toString(), r[1] == null ? 0 : ((Number) r[1]).intValue()))
                        .sorted(Comparator.comparing(KeywordStats::count).reversed())
                        .collect(Collectors.toList());
            }

            SourceWordWithKeywordStatsResponse model = new SourceWordWithKeywordStatsResponse(
                    compound.getIdWord(),
                    compound.getTransliteration(),
                    compound.getInflection(),
                    compound.getMeaning(),
                    compound.getIdParent(),
                    compound.getIdParentSec(),
                    compound.getParentMeaning(),
                    compound.getParentSecMeaning(),
                    includeLxx ? compound.getFirstAppBookLxx() : compound.getFirstAppBook(),
                    includeLxx ? compound.getFirstAppChapterLxx() : compound.getFirstAppChapter(),
                    includeLxx ? compound.getFirstAppVerseLxx() : compound.getFirstAppVerse(),
                    stats,
                    statsT,
                    KeywordMapper.toCompoundWordList(compoundRelatedList)
            );

            // Outbox event
            try {
                String payload = String.format("{\"strongCode\": \"%s\", \"sources\": %s}", keyUpper, sources);
                OutboxEventEntity event = new OutboxEventEntity("CompoundKeywordQueried", payload, LocalDateTime.now());
                outboxRepository.save(event);
            } catch (Exception ex) {
                logger.error("Failed to persist outbox event for StrongKeywordsGrouped (compound)", ex);
            }

            return model;
        }

        SourceWordEntity entity = sourceWordRepository.findByIdWordWithVerses(keyUpper, sources);
        if (entity == null) {
            throw new ResourceNotFoundException("Strong code not found: " + keyUpper);
        }

        List<Object[]> rows = sourceWordRepository.findKeywordCountsByIdWord(keyUpper, sources);
        List<KeywordStats> stats = new ArrayList<>();
        if (rows != null && !rows.isEmpty() && rows.get(0) != null && rows.get(0)[0] != null) {
            stats = rows.stream()
                    .map(r -> {
                        String transliterated = r[0] == null ? "" : r[0].toString();
                        Integer count = r[1] == null ? 0 : ((Number) r[1]).intValue();
                        return new KeywordStats(transliterated, null, count);
                    })
                    .sorted(Comparator.comparing(KeywordStats::count).reversed())
                    .collect(Collectors.toList());
        }

        List<Object[]> rowsT = sourceWordRepository.findKeywordTranslatedCountsByIdWord(keyUpper, sources);
        List<KeywordStats> statsT = new ArrayList<>();
        if (rowsT != null && !rowsT.isEmpty() && rowsT.get(0) != null && rowsT.get(0)[0] != null) {
            statsT = rowsT.stream()
                    .map(r -> {
                        String translated = r[0] == null ? "" : r[0].toString();
                        Integer count = r[1] == null ? 0 : ((Number) r[1]).intValue();
                        return new KeywordStats(null, translated, count);
                    })
                    .sorted(Comparator.comparing(KeywordStats::count).reversed())
                    .collect(Collectors.toList());
        }

        SourceWordWithKeywordStatsResponse model = new SourceWordWithKeywordStatsResponse(
                entity.getIdWord(),
                entity.getTransliteration(),
                entity.getInflection(),
                entity.getMeaning(),
                entity.getIdParent(),
                entity.getIdParentSec(),
                entity.getParentMeaning(),
                entity.getParentSecMeaning(),
                includeLxx ? entity.getFirstAppBookLxx() : entity.getFirstAppBook(),
                includeLxx ? entity.getFirstAppChapterLxx() : entity.getFirstAppChapter(),
                includeLxx ? entity.getFirstAppVerseLxx() : entity.getFirstAppVerse(),
                stats,
                statsT,
                KeywordMapper.toCompoundWordList(compoundRelatedList)
        );

        try {
            String payload = String.format("{\"strongCode\": \"%s\", \"sources\": %s}", keyUpper, sources);
            OutboxEventEntity event = new OutboxEventEntity("KeywordQueried", payload, LocalDateTime.now());
            outboxRepository.save(event);
        } catch (Exception ex) {
            logger.error("Failed to persist outbox event for StrongKeywordsGrouped (source)", ex);
        }

        return model;
    }

    @Transactional(readOnly = true)
    public SourceWordResponse getStrongDetail(String strongCode, String transliteratedWord, String translatedWord, Boolean includeLXX) {
        if (strongCode == null || strongCode.trim().isEmpty()) {
            throw new IllegalArgumentException("strongCode is required");
        }

        boolean include = includeLXX != null && includeLXX;
        List<String> sources = include ? List.of("HEBREW AT", "TR", "LXX") : List.of("HEBREW AT", "TR");
        String keyUpper = strongCode.toUpperCase();

        if (strongCode.contains(" ")) {
            CompoundWordEntity compound = compoundWordRepository.findByIdWordAndTransliteratedWordWithVerses(keyUpper, transliteratedWord, translatedWord, sources);
            if (compound == null) {
                throw new ResourceNotFoundException("Compound strong code not found: " + keyUpper);
            }

            List<KeywordWithVerse> sourceKeywordsWithVerse = KeywordMapper.toKeywordWithVerseList(compound.getKeywords());

            return new SourceWordResponse(
                    compound.getIdWord(),
                    compound.getTransliteration(),
                    compound.getInflection(),
                    compound.getMeaning(),
                    compound.getIdParent(),
                    compound.getIdParentSec(),
                    compound.getParentMeaning(),
                    compound.getParentSecMeaning(),
                    sourceKeywordsWithVerse
            );
        }

        SourceWordEntity entity = sourceWordRepository.findByIdWordAndTransliteratedWordWithVerses(keyUpper, transliteratedWord, translatedWord, sources);
        if (entity == null) {
            throw new ResourceNotFoundException("Strong code not found: " + keyUpper);
        }

        List<KeywordWithVerse> sourceKeywordsWithVerse = KeywordMapper.toKeywordWithVerseList(entity.getKeywords());

        return new SourceWordResponse(
                entity.getIdWord(),
                entity.getTransliteration(),
                entity.getInflection(),
                entity.getMeaning(),
                entity.getIdParent(),
                entity.getIdParentSec(),
                entity.getParentMeaning(),
                entity.getParentSecMeaning(),
                sourceKeywordsWithVerse
        );
    }
}
