// Crear StrongService que encapsula la lógica actualmente en StrongController
package com.gdav.bible.bible_references.service;

import com.gdav.bible.bible_references.exception.ResourceNotFoundException;
import com.gdav.bible.bible_references.mapper.KeywordMapper;
import com.gdav.bible.bible_references.model.*;
import com.gdav.bible.bible_references.repository.CompoundWordRepository;
import com.gdav.bible.bible_references.repository.KeywordBookCount;
import com.gdav.bible.bible_references.repository.KeywordWordCount;
import com.gdav.bible.bible_references.repository.SourceWordRepository;
import com.gdav.bible.bible_references.repository.entity.CompoundWordEntity;
import com.gdav.bible.bible_references.repository.entity.SourceWordEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class StrongService implements IStrongService {

    private static final Logger logger = LoggerFactory.getLogger(StrongService.class);

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
        StrongCode code = parse(strongCode);
        String keyUpper = code.value();

        String secondKey = code.isCompound() ? code.second() : code.first();
        List<CompoundWordEntity> compoundRelatedList = compoundWordRepository.findLikeIdWord(code.first(), secondKey, keyUpper);

        if (code.isCompound()) {
            CompoundWordEntity compound = compoundWordRepository.findByIdWord(keyUpper);
            if (compound == null) {
                throw new ResourceNotFoundException("Compound strong code not found: " + keyUpper);
            }

            List<KeywordStats> stats =
                    toTransliteratedStats(compoundWordRepository.findKeywordTransliteratedCountsByIdWord(keyUpper, sources));
            List<KeywordStats> statsT =
                    toTranslatedStats(compoundWordRepository.findKeywordTranslatedCountsByIdWord(keyUpper, sources));

            List<KeywordBookStats> bookStats =
                    toBookStats(compoundWordRepository.findKeywordCountsByBook(keyUpper, sources));

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
                    bookStats,
                    KeywordMapper.toCompoundWordList(compoundRelatedList)
            );

            return model;
        }

        // Por clave primaria, como la rama compuesta. Hasta el 2026-11-22 se buscaba con un
        // JOIN FETCH filtrado por k.source en el WHERE, que exigía al menos una keyword: un
        // código del léxico sin apariciones (H854) respondía 404 en vez de listas vacías.
        // Además cargaba todas las apariciones con su versículo para no usar ninguna.
        SourceWordEntity entity = sourceWordRepository.findById(keyUpper)
                .orElseThrow(() -> new ResourceNotFoundException("Strong code not found: " + keyUpper));

        // Hasta el 2026-10-20 una fila de cabeza sin palabra descartaba aquí la lista entera,
        // a diferencia de la rama compuesta. Hay keywords sin transliteratedWord en
        // producción, así que había códigos que respondían keywordStats vacío teniendo conteos.
        List<KeywordStats> stats =
                toTransliteratedStats(sourceWordRepository.findKeywordCountsByIdWord(keyUpper, sources));
        List<KeywordStats> statsT =
                toTranslatedStats(sourceWordRepository.findKeywordTranslatedCountsByIdWord(keyUpper, sources));

        List<KeywordBookStats> bookStats =
                toBookStats(sourceWordRepository.findKeywordCountsByBook(keyUpper, sources));

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
                bookStats,
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
     *
     * <p>Por lo mismo, {@code idBook} entra en la clave: sin él, la petición filtrada por
     * libro y la que no lo está compartirían entrada.
     */
    @Override
    @Cacheable(value = "strongDetails",
            key = "#strongCode.toUpperCase() + '_' + #transliteratedWord + '_' + #translatedWord + '_' + #idBook",
            condition = "@wordCacheCondition.shouldCacheWithJustOneParam(#strongCode.toUpperCase())")
    @Transactional(readOnly = true)
    public SourceWordResponse getStrongDetail(String strongCode, String transliteratedWord, String translatedWord, Integer idBook) {
        List<String> sources = List.of("HEBREW AT", "TR");
        StrongCode code = parse(strongCode);
        String keyUpper = code.value();

        if (code.isCompound()) {
            CompoundWordEntity compound = compoundWordRepository.findByIdWordAndTransliteratedWordWithVerses(keyUpper, transliteratedWord, translatedWord, sources, idBook);
            if (compound == null) {
                throw new ResourceNotFoundException("Compound strong code not found: " + keyUpper);
            }

            List<KeywordWithVerse> sourceKeywordsWithVerse = KeywordMapper.toKeywordWithVerseList(compound.getKeywords());
            boolean truncated = sourceKeywordsWithVerse.size() > MAX_DETAIL_KEYWORDS;

            return new SourceWordResponse(
                    compound.getIdWord(),
                    compound.getTransliteration(),
                    compound.getInflection(),
                    compound.getMeaning(),
                    compound.getIdParent(),
                    compound.getIdParentSec(),
                    compound.getParentMeaning(),
                    compound.getParentSecMeaning(),
                    acotar(sourceKeywordsWithVerse, keyUpper),
                    truncated
            );
        }

        SourceWordEntity entity = sourceWordRepository.findByIdWordAndTransliteratedWordWithVerses(keyUpper, transliteratedWord, translatedWord, sources, idBook);
        if (entity == null) {
            throw new ResourceNotFoundException("Strong code not found: " + keyUpper);
        }

        List<KeywordWithVerse> sourceKeywordsWithVerse = KeywordMapper.toKeywordWithVerseList(entity.getKeywords());
        boolean truncated = sourceKeywordsWithVerse.size() > MAX_DETAIL_KEYWORDS;

        return new SourceWordResponse(
                entity.getIdWord(),
                entity.getTransliteration(),
                entity.getInflection(),
                entity.getMeaning(),
                entity.getIdParent(),
                entity.getIdParentSec(),
                entity.getParentMeaning(),
                entity.getParentSecMeaning(),
                acotar(sourceKeywordsWithVerse, keyUpper),
                truncated
        );
    }

    /**
     * Un código mal formado no puede existir en la base de datos, así que se responde como
     * inexistente: es la excepción que declara la interface, y una implementación no puede
     * ampliar el contrato con otra. No se repite la entrada en el mensaje porque puede ser
     * cualquier cosa.
     */
    private static StrongCode parse(String strongCode) {
        try {
            return StrongCode.of(strongCode);
        } catch (IllegalArgumentException e) {
            throw new ResourceNotFoundException("Malformed strong code");
        }
    }

    /**
     * Recorta a {@link #MAX_DETAIL_KEYWORDS}. Se conservan las primeras en orden canónico
     * —libro, capítulo, versículo—, que es como las devuelve el query.
     */
    private static List<KeywordWithVerse> acotar(List<KeywordWithVerse> keywords, String keyUpper) {
        if (keywords.size() <= MAX_DETAIL_KEYWORDS) {
            return keywords;
        }
        logger.info("Detalle de {} truncado a {} apariciones (había {})", keyUpper, MAX_DETAIL_KEYWORDS, keywords.size());
        return keywords.subList(0, MAX_DETAIL_KEYWORDS);
    }

    /*
     * Los conteos por palabra ya llegan ordenados por el query —COUNT(k) DESC—. Antes se
     * volvían a ordenar aquí con un sort estable por el mismo criterio, que no cambiaba nada.
     */
    private static List<KeywordStats> toTransliteratedStats(List<KeywordWordCount> rows) {
        if (rows == null) {
            return List.of();
        }
        return rows.stream()
                .map(r -> new KeywordStats(r.wordOrEmpty(), null, r.countAsInt()))
                .toList();
    }

    private static List<KeywordStats> toTranslatedStats(List<KeywordWordCount> rows) {
        if (rows == null) {
            return List.of();
        }
        return rows.stream()
                .map(r -> new KeywordStats(null, r.wordOrEmpty(), r.countAsInt()))
                .toList();
    }

    /**
     * Resuelve el nombre del libro y el tipo del conteo. El orden lo fija el query
     * —apariciones descendentes, y el id del libro como desempate—, así que aquí no se
     * vuelve a ordenar.
     */
    private static List<KeywordBookStats> toBookStats(List<KeywordBookCount> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.stream()
                .filter(r -> r.idBook() != null)
                .map(r -> new KeywordBookStats(r.idBook(), BibleBooks.nameOf(r.idBook()), r.countAsInt()))
                .toList();
    }
}
