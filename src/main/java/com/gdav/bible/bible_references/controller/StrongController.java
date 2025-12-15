package com.gdav.bible.bible_references.controller;

import com.gdav.bible.bible_references.mapper.KeywordMapper;
import com.gdav.bible.bible_references.model.KeywordStats;
import com.gdav.bible.bible_references.model.KeywordWithVerse;
import com.gdav.bible.bible_references.model.SourceWord;
import com.gdav.bible.bible_references.model.SourceWordWithKeywordStats;
import com.gdav.bible.bible_references.repository.SourceWordRepository;
import com.gdav.bible.bible_references.repository.entity.SourceWordEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/strongs")
@CrossOrigin(origins = "*")
public class StrongController {

    private final SourceWordRepository sourceWordRepository;

    @Autowired
    public StrongController(SourceWordRepository sourceWordRepository) {
        this.sourceWordRepository = sourceWordRepository;
    }

//    @GetMapping("/{strongCode}")
//    public Object getStrongDetail(@PathVariable String strongCode) {
//        // Intentamos obtener la entidad con versos asociados
//        SourceWordEntity entity = sourceWordRepository.findByIdWordWithVerses(strongCode.toUpperCase());
//        if (entity == null) {
//            return Map.of("error", "Strong code not found");
//        }
//
//        // Mapear keywords del source word a DTOs
//        List<KeywordWithVerse> sourceKeywordsWithVerse = KeywordMapper.toKeywordWithVerseList(entity.getKeywords());
//
//        SourceWord model = new SourceWord(
//                entity.getIdWord(),
//                entity.getTransliteration(),
//                entity.getInflection(),
//                entity.getMeaning(),
//                entity.getIdParent(),
//                sourceKeywordsWithVerse
//        );
//
//        return model;
//    }


    @GetMapping(value="/{strongCode}/details", params = "translatedWord")
    public Object getStrongFiltered(@PathVariable String strongCode, @RequestParam(name = "translatedWord", required = true) String translatedWord) {
        // Intentamos obtener la entidad con versos asociados
        SourceWordEntity entity = sourceWordRepository.findByIdWordAndTranslatedWordWithVerses(strongCode.toUpperCase(), translatedWord);
        if (entity == null) {
            return Map.of("error", "Strong code not found");
        }

        // Mapear keywords del source word a DTOs
        List<KeywordWithVerse> sourceKeywordsWithVerse = KeywordMapper.toKeywordWithVerseList(entity.getKeywords());

        SourceWord model = new SourceWord(
                entity.getIdWord(),
                entity.getTransliteration(),
                entity.getInflection(),
                entity.getMeaning(),
                entity.getIdParent(),
                sourceKeywordsWithVerse
        );

        return model;
    }


    @GetMapping("/{strongCode}/stats")
    public Object getStrongKeywordsGrouped(@PathVariable String strongCode) {

        // Intentamos obtener la entidad con versos asociados
        SourceWordEntity entity = sourceWordRepository.findByIdWordWithVerses(strongCode.toUpperCase());
        if (entity == null) {
            return Map.of("error", "Strong code not found");
        }


        // Usamos la consulta en la base de datos para agrupar por translatedWord
        List<Object[]> rows = sourceWordRepository.findKeywordCountsByIdWord(strongCode.toUpperCase());
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        List<KeywordStats> stats = rows.stream()
                .map(r -> {
                    String translated = r[0] == null ? null : r[0].toString();
                    Integer count = r[1] == null ? 0 : ((Number) r[1]).intValue();
                    return new KeywordStats(translated, count);
                })
                .sorted(Comparator.comparing(KeywordStats::getCount).reversed())
                .collect(Collectors.toList());



        SourceWordWithKeywordStats model = new SourceWordWithKeywordStats(
                entity.getIdWord(),
                entity.getTransliteration(),
                entity.getInflection(),
                entity.getMeaning(),
                entity.getIdParent(),
                stats
        );



        return model;
    }
}
