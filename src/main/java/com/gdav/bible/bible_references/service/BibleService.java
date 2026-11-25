package com.gdav.bible.bible_references.service;

import com.gdav.bible.bible_references.config.VerseCacheCondition;
import com.gdav.bible.bible_references.mapper.KeywordMapper;
import com.gdav.bible.bible_references.model.BibleBooks;
import com.gdav.bible.bible_references.model.ChapterResponse;
import com.gdav.bible.bible_references.model.Keyword;
import com.gdav.bible.bible_references.model.SearchResponse;
import com.gdav.bible.bible_references.model.SearchResultResponse;
import com.gdav.bible.bible_references.model.Verse;
import com.gdav.bible.bible_references.repository.SearchProjection;
import com.gdav.bible.bible_references.repository.SearchRepository;
import com.gdav.bible.bible_references.repository.VerseRepository;
import com.gdav.bible.bible_references.repository.entity.VerseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class BibleService implements IBibleService {

    private final VerseRepository repository;
    private final SearchRepository searchRepository;
    private static final Logger logger = LoggerFactory.getLogger(BibleService.class);

    public BibleService(VerseRepository repository, SearchRepository searchRepository) {
        this.repository = repository;
        this.searchRepository = searchRepository;
    }

    /**
     * {@inheritDoc}
     *
     * <p>La caché vive aquí y no en {@code VerseRepository}: lo que se guarda es la respuesta
     * ya construida, no las entidades. Tres razones, por orden de importancia:
     *
     * <ol>
     *   <li>Un acierto ya no paga el mapeo entidad → DTO, que antes se repetía en cada
     *       petición aunque la consulta no llegara a la base de datos.</li>
     *   <li>Lo cacheado deja de ser un grafo de entidades JPA desconectadas y mutables
     *       compartido entre invocaciones, y pasa a ser un {@code record} inmutable.</li>
     *   <li>La caché deja de depender de JPA: si algún día la consulta cambia de forma, la
     *       clave sigue siendo la misma —los parámetros de la petición—.</li>
     * </ol>
     *
     * <p>La clave incluye {@code idBible} y no {@code includeLxx} para que {@code null} y
     * {@code false} —que designan la misma biblia— no ocupen dos entradas.
     */
    @Override
    @Cacheable(value = "chapters",
            key = "T(com.gdav.bible.bible_references.config.VerseCacheCondition).idBibleFor(#includeLxx)"
                    + " + '_' + #idBook + '_' + #chapter + '_' + #idVerse",
            condition = "@verseCacheCondition.shouldCacheChapter(#idBook, #chapter, #idVerse, #includeLxx)")
    @Transactional(readOnly = true)
    public ChapterResponse getChapter(int idBook, int chapter, Integer idVerse, Boolean includeLxx) {
        String bookName = BibleBooks.nameOf(idBook);
        // La traducción includeLXX -> idBible vive en VerseCacheCondition para que el
        // controlador pueda decidir la cacheabilidad con el mismo criterio, sin duplicarla.
        int idBible = VerseCacheCondition.idBibleFor(includeLxx);

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

        return new ChapterResponse(bookName, chapter, versesList);
    }

    @Override
    @Transactional(readOnly = true)
    public SearchResultResponse search(String q, Integer limit) {
        int idBible = VerseCacheCondition.idBibleFor(null);
        String qq = "\\m" + q;

        int tope = limitePedido(limit);
        // Se pide un versículo de más: si vuelve, es que había más de los que caben, y así se
        // sabe que el resultado está truncado sin una segunda consulta de conteo, que
        // repetiría el escaneo con la expresión regular sobre toda la Biblia.
        List<SearchProjection> projectionList = searchRepository.findAllByWord(idBible, qq, tope + 1);

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

        boolean truncado = versesList.size() > tope;
        if (truncado) {
            // El versículo sobrante era solo la señal de que hay más; no se devuelve.
            versesList = versesList.subList(0, tope);
            logger.info("Búsqueda truncada a {} versículos (había más)", tope);
        }

        return new SearchResultResponse(versesList, versesList.size(), truncado);
    }

    /**
     * Traduce el límite pedido por el cliente a uno aplicable.
     *
     * <p>Un valor ausente o no positivo cae al de por defecto en lugar de rechazarse: la
     * validación de entrada es cosa del controlador, y el servicio no debe poder devolver
     * una respuesta sin acotar por mucho que le pidan cero o un número negativo.
     */
    private int limitePedido(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_SEARCH_LIMIT;
        }
        return Math.min(limit, MAX_SEARCH_LIMIT);
    }


}

