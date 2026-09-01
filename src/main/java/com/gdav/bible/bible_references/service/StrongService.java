// Crear StrongService que encapsula la lógica actualmente en StrongController
package com.gdav.bible.bible_references.service;

import com.gdav.bible.bible_references.exception.ResourceNotFoundException;
import com.gdav.bible.bible_references.mapper.KeywordMapper;
import com.gdav.bible.bible_references.model.*;
import com.gdav.bible.bible_references.repository.CompoundWordRepository;
import com.gdav.bible.bible_references.repository.SourceWordRepository;
import com.gdav.bible.bible_references.repository.entity.CompoundWordEntity;
import com.gdav.bible.bible_references.repository.entity.SourceWordEntity;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class StrongService implements IStrongService {

    private final SourceWordRepository sourceWordRepository;
    private final CompoundWordRepository compoundWordRepository;

    public StrongService(SourceWordRepository sourceWordRepository, CompoundWordRepository compoundWordRepository) {
        this.sourceWordRepository = sourceWordRepository;
        this.compoundWordRepository = compoundWordRepository;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Una sola caché en lugar de las cuatro que había repartidas por los repositorios. Un
     * acierto se ahorra ahora <b>las tres o cuatro consultas</b> del método —no solo una cada
     * vez— y todo el armado de la respuesta.
     *
     * <p>La clave se normaliza a mayúsculas porque el resto del método trabaja con
     * {@code keyUpper}: sin eso, {@code h430} y {@code H430} ocuparían dos entradas con el
     * mismo contenido.
     */
    @Override
    @Cacheable(value = "strongStats", key = "#strongCode.toUpperCase()",
            condition = "@wordCacheCondition.shouldCacheWithJustOneParam(#strongCode.toUpperCase())")
    @Transactional(readOnly = true)
    public SourceWordWithKeywordStatsResponse getStrongKeywordsGrouped(String strongCode) {
        List<String> sources = List.of("HEBREW AT", "TR");
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
                    compound.getFirstAppBook(),
                    compound.getFirstAppChapter(),
                    compound.getFirstAppVerse(),
                    stats,
                    statsT,
                    KeywordMapper.toCompoundWordList(compoundRelatedList)
            );

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
                entity.getFirstAppBook(),
                entity.getFirstAppChapter(),
                entity.getFirstAppVerse(),
                stats,
                statsT,
                KeywordMapper.toCompoundWordList(compoundRelatedList)
        );

        return model;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>La clave lleva los tres parámetros, y antes no.</b> La caché de repositorio que
     * esto sustituye se indexaba por {@code idWord} y {@code transliteratedWord} pero
     * <b>omitía {@code translatedWord}</b>, de modo que dos peticiones del mismo código con
     * distinta palabra traducida colisionaban y la segunda recibía la respuesta de la
     * primera. Cachear a la altura del caso de uso hace que la clave sea, por construcción,
     * lo que distingue una petición de otra.
     *
     * <p>Solo afectaba a los códigos de la lista de {@code WordCacheCondition} —los únicos
     * que se cacheaban— y solo a la caché en memoria de cada instancia: el controlador
     * responde {@code no-store} en este endpoint, así que CloudFront nunca lo propagó.
     */
    @Override
    @Cacheable(value = "strongDetails",
            key = "#strongCode.toUpperCase() + '_' + #transliteratedWord + '_' + #translatedWord",
            condition = "@wordCacheCondition.shouldCacheWithJustOneParam(#strongCode.toUpperCase())")
    @Transactional(readOnly = true)
    public SourceWordResponse getStrongDetail(String strongCode, String transliteratedWord, String translatedWord) {
        List<String> sources = List.of("HEBREW AT", "TR");
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
