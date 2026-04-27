package com.gdav.bible.bible_references.controller;

import com.gdav.bible.bible_references.mapper.KeywordMapper;
import com.gdav.bible.bible_references.model.Keyword;
import com.gdav.bible.bible_references.model.SearchResponse;
import com.gdav.bible.bible_references.model.Verse;
import com.gdav.bible.bible_references.repository.*;
import com.gdav.bible.bible_references.repository.entity.OutboxEventEntity;
import com.gdav.bible.bible_references.repository.entity.VerseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/bible")
public class BibleController {

    private final VerseRepository repository;
    private final OutboxRepository outboxRepository;
    private final SearchRepository searchRepository;

    // Logger
    private static final Logger logger = LoggerFactory.getLogger(BibleController.class);

    @Autowired
    BibleController(VerseRepository repository, OutboxRepository outboxRepository, SearchRepository searchRepository) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
        this.searchRepository = searchRepository;
    }


    @GetMapping("/chapter/{idBook}/{chapter}")
    public Map<String, Object> getChapter(@PathVariable int idBook, @PathVariable int chapter,
                                          @RequestParam(required = false) Integer idVerse,
                                          @RequestParam(name = "includeLXX", required = false) Boolean includeLxx) {

        String bookName = getBookName(idBook);

        // determinar idBible según includeLxx (true => 2, false/absent => 1)
        int idBible = (includeLxx != null && includeLxx) ? 2 : 1;

        //Consulta repositorio por id de la entidad
        List<VerseEntity> versesEntityList = repository.findAllByIdBookAndChapter(idBible, idBook, chapter, idVerse );

        // Si no hay resultados, devolver lista vacía de versos
        List<Verse> versesList;
        if (versesEntityList == null || versesEntityList.isEmpty()) {
            versesList = Collections.emptyList();
        } else {
            // Mapear entidades a modelos incluyendo keywords (KeywordMapper ahora incluye compoundWordEntity)
            versesList = versesEntityList.stream().map( verseEntity ->
                    new Verse(
                            verseEntity.getVerse(),
                            verseEntity.getText(),
                            KeywordMapper.toKeywordList(verseEntity.getKeywords())
                    )
            ).toList();
        }

        // 📘 Respuesta
        Map<String, Object> response = new HashMap<>();
        response.put("book", bookName);
        response.put("chapter", chapter);
        response.put("verses", versesList);

        // Outbox event: registrar el evento 'ChapterQueried'
        try {
            String payload = String.format("{\"idBible\": %d, \"idBook\": %d, \"chapter\": %d, \"idVerse\": %s}",
                    idBible, idBook, chapter, (idVerse != null ? idVerse.toString() : "null"));

            OutboxEventEntity event = new OutboxEventEntity((idVerse == null ? "ChapterQueried": "VerseQueried"), payload, LocalDateTime.now());
            outboxRepository.save(event);
        } catch (Exception ex) {
            // No bloquear la respuesta por errores del outbox; loggeamos si es necesario
            logger.error("Failed to persist outbox event for ChapterQueried", ex);
        }

        return response;

    }




    @GetMapping(value="/search")
    public Object getStrongDetail(
                                  @RequestParam(name = "q", required = false) String q,
                                  @RequestParam(name = "includeLXX", required = false) Boolean includeLxx) {

        // determinar idBible según includeLxx (true => 2, false/absent => 1)
        int idBible = (includeLxx != null && includeLxx) ? 2 : 1;

        String qq = "\\m"+q;
        //Consulta repositorio por id de la entidad
        List<SearchProjection> projectionList = searchRepository.findAllByWord(idBible, qq );

        // Si no hay resultados, devolver lista vacía de versos
        List<SearchResponse> versesList;
        if (projectionList == null || projectionList.isEmpty()) {
            versesList = Collections.emptyList();
        } else {
            // Agrupar por verso (idBook, chapter, verse) y construir SearchResponse
            // Usar LinkedHashMap para preservar el orden de aparición
            Map<String, List<SearchProjection>> grouped = projectionList.stream()
                    .collect(Collectors.groupingBy(
                            p -> p.getIdBook() + "_" + p.getChapter() + "_" + p.getVerse(),
                            LinkedHashMap::new,
                            Collectors.toList()
                    ));

            versesList = grouped.values().stream().map(list -> {
                SearchProjection first = list.get(0);

                List<com.gdav.bible.bible_references.model.Keyword> keywords = list.stream().map(p -> {
                    Keyword k = new Keyword();
                    k.setInflectionWord(p.getInflectionWord());
                    k.setTranslatedWord(p.getTranslatedWord());
                    k.setTransliteratedWord(p.getTransliteratedWord());
                    k.setStrongNumber(p.getStrongNumber());
                    return k;
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

        // 📘 Respuesta
        Map<String, Object> response = new HashMap<>();
        response.put("verses", versesList);
/*
        // Outbox event: registrar el evento 'ChapterQueried'
        try {
            String payload = String.format("{\"idBible\": %d, \"idBook\": %d, \"chapter\": %d, \"idVerse\": %s}",
                    idBible, idBook, chapter, (idVerse != null ? idVerse.toString() : "null"));

            OutboxEventEntity event = new OutboxEventEntity((idVerse == null ? "ChapterQueried": "VerseQueried"), payload, LocalDateTime.now());
            outboxRepository.save(event);
        } catch (Exception ex) {
            // No bloquear la respuesta por errores del outbox; loggeamos si es necesario
            logger.error("Failed to persist outbox event for ChapterQueried", ex);
        }
*/
        return response;

    }



    // 🔤 Mapeo simple de ID → Nombre del libro
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
                )
        );

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
                )
        );

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
                )
        );

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
                )
        );

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
                )
        );

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
                )
        );

        map.putAll(Map.of(
                61, "2 Pedro",
                62, "1 Juan",
                63, "2 Juan",
                64, "3 Juan",
                65, "Judas",
                66, "Apocalipsis"
                )
        );
        return map.getOrDefault(id, "Desconocido");
    }
}
