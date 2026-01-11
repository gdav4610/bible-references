package com.gdav.bible.bible_references.controller;

import com.gdav.bible.bible_references.mapper.KeywordMapper;
import com.gdav.bible.bible_references.model.KeywordStats;
import com.gdav.bible.bible_references.model.KeywordWithVerse;
import com.gdav.bible.bible_references.model.SourceWord;
import com.gdav.bible.bible_references.model.SourceWordWithKeywordStats;
import com.gdav.bible.bible_references.repository.CompoundWordRepository;
import com.gdav.bible.bible_references.repository.OutboxRepository;
import com.gdav.bible.bible_references.repository.SourceWordRepository;
import com.gdav.bible.bible_references.repository.entity.CompoundWordEntity;
import com.gdav.bible.bible_references.repository.entity.OutboxEventEntity;
import com.gdav.bible.bible_references.repository.entity.SourceWordEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/strongs")
@CrossOrigin(origins = "*")
public class StrongController {

    private final SourceWordRepository sourceWordRepository;
    private final CompoundWordRepository compoundWordRepository;
    private final OutboxRepository outboxRepository;

    // Logger
    private static final Logger logger = LoggerFactory.getLogger(StrongController.class);

    @Autowired
    public StrongController(SourceWordRepository sourceWordRepository, CompoundWordRepository compoundWordRepository, OutboxRepository outboxRepository) {
        this.sourceWordRepository = sourceWordRepository;
        this.compoundWordRepository = compoundWordRepository;
        this.outboxRepository = outboxRepository;
    }


    @GetMapping("/{strongCode}/stats")
    public Object getStrongKeywordsGrouped(@PathVariable String strongCode,
                                           @RequestParam(name = "includeLXX", required = false) Boolean includeLXX) {

        // Validaciones tempranas
        if (strongCode == null || strongCode.trim().isEmpty()) {
            return Map.of("error", "strongCode is required");
        }

        boolean includeLxx = includeLXX != null && includeLXX;

        // construir lista de fuentes acorde al flag includeLXX
        List<String> sources = includeLxx ? List.of("HEBREW AT", "TR", "LXX") : List.of("HEBREW AT", "TR");

        String keyUpper = strongCode.toUpperCase();

        // Si el codigo contiene espacio, usamos CompoundWordRepository
        if (strongCode.contains(" ")) {
            CompoundWordEntity compound = compoundWordRepository.findByIdWord(keyUpper);
            if (compound == null) {
                return Map.of("error", "Compound strong code not found");
            }

            List<Object[]> rows = compoundWordRepository.findKeywordCountsByIdWord(keyUpper, sources);
            List<KeywordStats> stats = new ArrayList<>();
            if (rows != null && !rows.isEmpty()) {
                stats = rows.stream()
                        .map(r -> new KeywordStats(r[0] == null ? "" : r[0].toString(), r[1] == null ? 0 : ((Number) r[1]).intValue()))
                        .sorted(Comparator.comparing(KeywordStats::getCount).reversed())
                        .collect(Collectors.toList());
            }

            SourceWordWithKeywordStats model = new SourceWordWithKeywordStats(
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
                    stats
            );

            // Outbox event: registrar el evento 'CompoundKeywordQueried'
            try {
                String payload = String.format("{\"strongCode\": \"%s\", \"sources\": %s}",
                        keyUpper, sources);
                OutboxEventEntity event = new OutboxEventEntity("CompoundKeywordQueried", payload, LocalDateTime.now());
                outboxRepository.save(event);
            } catch (Exception ex) {
                logger.error("Failed to persist outbox event for StrongKeywordsGrouped (compound)", ex);
            }

            return model;
        }

        // Intentamos obtener la entidad con versos asociados
        SourceWordEntity entity = sourceWordRepository.findByIdWordWithVerses(keyUpper, sources);
        if (entity == null) {
            return Map.of("error", "Strong code not found");
        }


        // Usamos la consulta en la base de datos para agrupar por translatedWord
        List<Object[]> rows = sourceWordRepository.findKeywordCountsByIdWord(keyUpper, sources);
        List<KeywordStats> stats = new ArrayList<>();

        if (rows != null && !rows.isEmpty() && rows.get(0) != null && rows.get(0)[0] != null) {

            stats = rows.stream()
                    .map(r -> {
                        String translated = r[0] == null ? "" : r[0].toString();
                        Integer count = r[1] == null ? 0 : ((Number) r[1]).intValue();
                        return new KeywordStats(translated, count);
                    })
                    .sorted(Comparator.comparing(KeywordStats::getCount).reversed())
                    .collect(Collectors.toList());

        }


        SourceWordWithKeywordStats model = new SourceWordWithKeywordStats(
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
                stats
        );

        // Outbox event: registrar el evento 'KeywordQueried' para source word
        try {
            String payload = String.format("{\"strongCode\": \"%s\", \"sources\": %s}",
                    keyUpper, sources);
            OutboxEventEntity event = new OutboxEventEntity("KeywordQueried", payload, LocalDateTime.now());
            outboxRepository.save(event);
        } catch (Exception ex) {
            logger.error("Failed to persist outbox event for StrongKeywordsGrouped (source)", ex);
        }

        return model;
    }


    @GetMapping(value="/{strongCode}/details", params = "translatedWord")
    public Object getStrongDetail(@PathVariable String strongCode,
                                  @RequestParam(name = "translatedWord", required = false) String translatedWord,
                                  @RequestParam(name = "includeLXX", required = false) Boolean includeLXX) {

        // Validaciones tempranas
        if (strongCode == null || strongCode.trim().isEmpty()) {
            return Map.of("error", "strongCode is required");
        }

        if (translatedWord == null || translatedWord.trim().isEmpty()) {
            return Map.of("error", "translatedWord is required");
        }

        boolean include = includeLXX != null && includeLXX;
        List<String> sources = include ? List.of("HEBREW AT", "TR", "LXX") : List.of("HEBREW AT", "TR");

        String keyUpper = strongCode.toUpperCase();

        // Si el codigo contiene espacio, usamos CompoundWordRepository con JOINs a keywords y verses
        if (strongCode.contains(" ")) {
            CompoundWordEntity compound = compoundWordRepository.findByIdWordAndTranslatedWordWithVerses(keyUpper, translatedWord, sources);
            if (compound == null) {
                return Map.of("error", "Compound strong code not found");
            }

            // Mapear keywords del compound word a DTOs
            List<KeywordWithVerse> sourceKeywordsWithVerse = KeywordMapper.toKeywordWithVerseList(compound.getKeywords());

            SourceWord model = new SourceWord(
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

            return model;
        }

        // Intentamos obtener la entidad con versos asociados (source words) y filtrando por translatedWord
        SourceWordEntity entity = sourceWordRepository.findByIdWordAndTranslatedWordWithVerses(keyUpper, translatedWord, sources);

        if (entity == null) {
            return Map.of("error", "Not found");
        }

        // Mapear keywords del source word a DTOs
        List<KeywordWithVerse> sourceKeywordsWithVerse = KeywordMapper.toKeywordWithVerseList(entity.getKeywords());

        SourceWord model = new SourceWord(
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

        return model;
    }


}
