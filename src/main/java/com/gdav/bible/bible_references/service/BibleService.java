package com.gdav.bible.bible_references.service;

import com.gdav.bible.bible_references.mapper.KeywordMapper;
import com.gdav.bible.bible_references.model.Keyword;
import com.gdav.bible.bible_references.model.SearchResponse;
import com.gdav.bible.bible_references.model.Verse;
import com.gdav.bible.bible_references.repository.OutboxRepository;
import com.gdav.bible.bible_references.repository.SearchProjection;
import com.gdav.bible.bible_references.repository.SearchRepository;
import com.gdav.bible.bible_references.repository.VerseRepository;
import com.gdav.bible.bible_references.repository.entity.OutboxEventEntity;
import com.gdav.bible.bible_references.repository.entity.VerseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class BibleService {

    private final VerseRepository repository;
    private final OutboxRepository outboxRepository;
    private final SearchRepository searchRepository;
    private static final Logger logger = LoggerFactory.getLogger(BibleService.class);

    public BibleService(VerseRepository repository, OutboxRepository outboxRepository, SearchRepository searchRepository) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
        this.searchRepository = searchRepository;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getChapter(int idBook, int chapter, Integer idVerse, Boolean includeLxx) {
        String bookName = getBookName(idBook);
        int idBible = (includeLxx != null && includeLxx) ? 2 : 1;

        List<VerseEntity> versesEntityList = repository.findAllByIdBookAndChapter(idBible, idBook, chapter, idVerse );

        List<Verse> versesList;
        if (versesEntityList == null || versesEntityList.isEmpty()) {
            versesList = Collections.emptyList();
        } else {
            versesList = versesEntityList.stream().map( verseEntity ->
                    new Verse(
                            verseEntity.getVerse(),
                            verseEntity.getText(),
                            KeywordMapper.toKeywordList(verseEntity.getKeywords())
                    )
            ).toList();
        }

        Map<String, Object> response = new HashMap<>();
        response.put("book", bookName);
        response.put("chapter", chapter);
        response.put("verses", versesList);

        try {
            String payload = String.format("{\"idBible\": %d, \"idBook\": %d, \"chapter\": %d, \"idVerse\": %s}",
                    idBible, idBook, chapter, (idVerse != null ? idVerse.toString() : "null"));

            OutboxEventEntity event = new OutboxEventEntity((idVerse == null ? "ChapterQueried": "VerseQueried"), payload, LocalDateTime.now());
            outboxRepository.save(event);
        } catch (Exception ex) {
            logger.error("Failed to persist outbox event for ChapterQueried", ex);
        }

        return response;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> search(String q, Boolean includeLxx) {
        int idBible = (includeLxx != null && includeLxx) ? 2 : 1;
        String qq = "\\m" + q;
        List<SearchProjection> projectionList = searchRepository.findAllByWord(idBible, qq );

        List<SearchResponse> versesList;
        if (projectionList == null || projectionList.isEmpty()) {
            versesList = Collections.emptyList();
        } else {
            Map<String, List<SearchProjection>> grouped = projectionList.stream()
                    .collect(Collectors.groupingBy(
                            p -> p.getIdBook() + "_" + p.getChapter() + "_" + p.getVerse(),
                            LinkedHashMap::new,
                            Collectors.toList()
                    ));

            versesList = grouped.values().stream().map(list -> {
                SearchProjection first = list.get(0);

                List<Keyword> keywords = list.stream().map(p -> {
                    return new Keyword(p.getInflectionWord(), p.getTranslatedWord(), p.getTransliteratedWord(), p.getStrongNumber(), null, null, null, null, null, null);
                }).collect(Collectors.toList());

                return new SearchResponse(
                        first.getIdBook(),
                        first.getChapter(),
                        first.getVerse(),
                        first.getText(),
                        keywords
                );
            }).collect(Collectors.toList());
        }

        Map<String, Object> response = new HashMap<>();
        response.put("verses", versesList);
        return response;
    }

    // Copiar método getBookName del controller (puede moverse a util común)
    private String getBookName(int id) {
        Map<Integer, String> map = new HashMap();
        map.putAll(Map.of(
                1, "Génesis",
                2, "Éxodo",
                3, "Levítico",
                4, "Números",
                5, "Deuteronomio",
                6, "Josué",
                7, "Jueces",
                8, "Rut",
                9, "1 Samuel",
                10, "2 Samuel"
        ) );

        map.putAll(Map.of(
                11, "1 Reyes",
                12, "2 Reyes",
                13, "1 Crónicas",
                14, "2 Crónicas",
                15, "Esdras",
                16, "Nehemías",
                17, "Ester",
                18, "Job",
                19, "Salmos",
                20, "Proverbios"
        ) );

        map.putAll(Map.of(
                21, "Eclesiastés",
                22, "Cantares",
                23, "Isaías",
                24, "Jeremías",
                25, "Lamentaciones",
                26, "Ezequiel",
                27, "Daniel",
                28, "Oseas",
                29, "Joel",
                30, "Amós"
        ) );

        map.putAll(Map.of(
                31, "Abdías",
                32, "Jonás",
                33, "Miqueas",
                34, "Nahum",
                35, "Habacuc",
                36, "Sofonías",
                37, "Hageo",
                38, "Zacarías",
                39, "Malaquías",
                40, "Mateo"
        ) );

        map.putAll(Map.of(
                41, "Marcos",
                42, "Lucas",
                43, "Juan",
                44, "Hechos",
                45, "Romanos",
                46, "1 Corintios",
                47, "2 Corintios",
                48, "Gálatas",
                49, "Efesios",
                50, "Filipenses"
        ) );

        map.putAll(Map.of(
                51, "Colosenses",
                52, "1 Tesalonicenses",
                53, "2 Tesalonicenses",
                54, "1 Timoteo",
                55, "2 Timoteo",
                56, "Tito",
                57, "Filemón",
                58, "Hebreos",
                59, "Santiago",
                60, "1 Pedro"
        ) );

        map.putAll(Map.of(
                61, "2 Pedro",
                62, "1 Juan",
                63, "2 Juan",
                64, "3 Juan",
                65, "Judas",
                66, "Apocalipsis"
        ) );
        return map.getOrDefault(id, "Desconocido");
    }
}

