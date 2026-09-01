package com.gdav.bible.bible_references.service;

import com.gdav.bible.bible_references.config.VerseCacheCondition;
import com.gdav.bible.bible_references.mapper.KeywordMapper;
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
        String bookName = getBookName(idBook);
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

    /**
     * Nombre del libro por su id.
     *
     * <p>El mapa es una constante, no una variable local: antes se construía uno nuevo de 66
     * entradas —siete {@code Map.of} y siete {@code putAll}— en cada petición de capítulo.
     * No depende de nada de la instancia, así que construirlo una vez al cargar la clase es
     * además lo que interesa con SnapStart: ese coste queda dentro del snapshot.
     */
    private static String getBookName(int id) {
        return BOOK_NAMES.getOrDefault(id, "Desconocido");
    }

    private static final Map<Integer, String> BOOK_NAMES = Map.ofEntries(
            Map.entry(1, "Génesis"), Map.entry(2, "Éxodo"), Map.entry(3, "Levítico"),
            Map.entry(4, "Números"), Map.entry(5, "Deuteronomio"), Map.entry(6, "Josué"),
            Map.entry(7, "Jueces"), Map.entry(8, "Rut"), Map.entry(9, "1 Samuel"),
            Map.entry(10, "2 Samuel"), Map.entry(11, "1 Reyes"), Map.entry(12, "2 Reyes"),
            Map.entry(13, "1 Crónicas"), Map.entry(14, "2 Crónicas"), Map.entry(15, "Esdras"),
            Map.entry(16, "Nehemías"), Map.entry(17, "Ester"), Map.entry(18, "Job"),
            Map.entry(19, "Salmos"), Map.entry(20, "Proverbios"), Map.entry(21, "Eclesiastés"),
            Map.entry(22, "Cantares"), Map.entry(23, "Isaías"), Map.entry(24, "Jeremías"),
            Map.entry(25, "Lamentaciones"), Map.entry(26, "Ezequiel"), Map.entry(27, "Daniel"),
            Map.entry(28, "Oseas"), Map.entry(29, "Joel"), Map.entry(30, "Amós"),
            Map.entry(31, "Abdías"), Map.entry(32, "Jonás"), Map.entry(33, "Miqueas"),
            Map.entry(34, "Nahum"), Map.entry(35, "Habacuc"), Map.entry(36, "Sofonías"),
            Map.entry(37, "Hageo"), Map.entry(38, "Zacarías"), Map.entry(39, "Malaquías"),
            Map.entry(40, "Mateo"), Map.entry(41, "Marcos"), Map.entry(42, "Lucas"),
            Map.entry(43, "Juan"), Map.entry(44, "Hechos"), Map.entry(45, "Romanos"),
            Map.entry(46, "1 Corintios"), Map.entry(47, "2 Corintios"), Map.entry(48, "Gálatas"),
            Map.entry(49, "Efesios"), Map.entry(50, "Filipenses"), Map.entry(51, "Colosenses"),
            Map.entry(52, "1 Tesalonicenses"), Map.entry(53, "2 Tesalonicenses"),
            Map.entry(54, "1 Timoteo"), Map.entry(55, "2 Timoteo"), Map.entry(56, "Tito"),
            Map.entry(57, "Filemón"), Map.entry(58, "Hebreos"), Map.entry(59, "Santiago"),
            Map.entry(60, "1 Pedro"), Map.entry(61, "2 Pedro"), Map.entry(62, "1 Juan"),
            Map.entry(63, "2 Juan"), Map.entry(64, "3 Juan"), Map.entry(65, "Judas"),
            Map.entry(66, "Apocalipsis"));

}

