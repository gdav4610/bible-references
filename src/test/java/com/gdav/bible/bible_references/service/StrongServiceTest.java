package com.gdav.bible.bible_references.service;

import com.gdav.bible.bible_references.exception.ResourceNotFoundException;
import com.gdav.bible.bible_references.model.KeywordBookStats;
import com.gdav.bible.bible_references.model.KeywordStats;
import com.gdav.bible.bible_references.model.SourceWordResponse;
import com.gdav.bible.bible_references.model.SourceWordWithKeywordStatsResponse;
import com.gdav.bible.bible_references.repository.CompoundWordRepository;
import com.gdav.bible.bible_references.repository.KeywordBookCount;
import com.gdav.bible.bible_references.repository.KeywordWordCount;
import com.gdav.bible.bible_references.repository.SourceWordRepository;
import com.gdav.bible.bible_references.repository.entity.CompoundWordEntity;
import com.gdav.bible.bible_references.repository.entity.KeywordEntity;
import com.gdav.bible.bible_references.repository.entity.SourceWordEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StrongServiceTest {

    @Mock
    private SourceWordRepository sourceWordRepository;

    @Mock
    private CompoundWordRepository compoundWordRepository;

    @InjectMocks
    private StrongService strongService;

    @BeforeEach
    void setUp() {
    }

    @Test
    void getStrongKeywordsGrouped_compoundHappyPath() {
        String strongCode = "ABC DEF";
        String keyUpper = strongCode.toUpperCase();

        CompoundWordEntity compound = new CompoundWordEntity();
        compound.setIdWord(keyUpper);
        compound.setTransliteration("trans");
        compound.setInflection("inf");
        compound.setMeaning("mean");
        compound.setIdParent("p");
        compound.setIdParentSec("ps");
        compound.setParentMeaning("pm");
        compound.setParentSecMeaning("psm");
        compound.setFirstAppBook(1);
        compound.setFirstAppChapter(1);
        compound.setFirstAppVerse(1);

        when(compoundWordRepository.findByIdWord(keyUpper)).thenReturn(compound);
        when(compoundWordRepository.findLikeIdWord(anyString(), anyString(), anyString())).thenReturn(Arrays.asList(compound));
/*
        // Simular rows para transliterated counts
        when(compoundWordRepository.findKeywordTransliteratedCountsByIdWord(eq(keyUpper), anyList(), anyString()))
                .thenReturn((List<Object[]>) Arrays.asList(new CompoundWordEntity("t1", "", "", "", "", "", "", "", 0, 0, 0, 0, 0, 0, new ArrayList<KeywordEntity>())));
        when(compoundWordRepository.findKeywordTranslatedCountsByIdWord(eq(keyUpper), anyList(), anyString()))
                .thenReturn((List<Object[]>) Arrays.asList(new CompoundWordEntity("t2", "", "", "", "", "", "", "", 0, 0, 0, 0, 0, 0, new ArrayList<KeywordEntity>())));
*/
        SourceWordWithKeywordStatsResponse res = strongService.getStrongKeywordsGrouped(strongCode);

        assertNotNull(res);
        assertEquals(keyUpper, res.idWord());
        // el primer estadístico corresponde al transliterated counts (2)
//        assertEquals(0, res.keywordStats().get(0).count());
    }

    @Test
    void getStrongDetail_sourceHappyPath() {
        String strongCode = "ABC";
        String keyUpper = strongCode.toUpperCase();

        SourceWordEntity entity = new SourceWordEntity();
        entity.setIdWord(keyUpper);
        entity.setTransliteration("t");
        entity.setInflection("i");
        entity.setMeaning("m");
        entity.setIdParent("p");
        entity.setIdParentSec("ps");
        entity.setParentMeaning("pm");
        entity.setParentSecMeaning("psm");

        when(sourceWordRepository.findByIdWordAndTransliteratedWordWithVerses(eq(keyUpper), any(), any(), anyList(), isNull()))
                .thenReturn(entity);
/*
        // No keywords -> empty list mapping
        when(sourceWordRepository.findKeywordCountsByIdWord(eq(keyUpper), anyList())).thenReturn(Arrays.asList());
        when(sourceWordRepository.findKeywordTranslatedCountsByIdWord(eq(keyUpper), anyList())).thenReturn(Arrays.asList());
*/
        SourceWordResponse resp = strongService.getStrongDetail(strongCode, null, null, null);
        assertNotNull(resp);
        assertEquals(keyUpper, resp.idWord());
    }

    /** El libro pedido llega tal cual al query, en las dos ramas. */
    @Test
    void getStrongDetail_idBookLlegaAlRepositorio() {
        SourceWordEntity entity = new SourceWordEntity();
        entity.setIdWord("H430");
        when(sourceWordRepository.findByIdWordAndTransliteratedWordWithVerses(eq("H430"), any(), any(), anyList(), eq(5)))
                .thenReturn(entity);
        CompoundWordEntity compound = new CompoundWordEntity();
        compound.setIdWord("H853 H430");
        when(compoundWordRepository.findByIdWordAndTransliteratedWordWithVerses(eq("H853 H430"), any(), any(), anyList(), eq(5)))
                .thenReturn(compound);

        assertEquals("H430", strongService.getStrongDetail("h430", null, null, 5).idWord());
        assertEquals("H853 H430", strongService.getStrongDetail("h853 h430", null, null, 5).idWord());
    }

    /** Sin filtro de palabra se devuelve todo, y por debajo del tope no se marca como truncado. */
    @Test
    void getStrongDetail_sinFiltros_devuelveTodasLasApariciones() {
        SourceWordEntity entity = new SourceWordEntity();
        entity.setIdWord("H430");
        entity.setKeywords(apariciones(3));
        when(sourceWordRepository.findByIdWordAndTransliteratedWordWithVerses(eq("H430"), isNull(), isNull(), anyList(), isNull()))
                .thenReturn(entity);

        SourceWordResponse resp = strongService.getStrongDetail("H430", null, null, null);

        assertEquals(3, resp.keywordsWithVerse().size());
        assertFalse(resp.truncated());
    }

    /** Por encima del tope se recorta, se conservan las primeras y se avisa. */
    @Test
    void getStrongDetail_porEncimaDelTope_trunca() {
        SourceWordEntity entity = new SourceWordEntity();
        entity.setIdWord("G3588");
        entity.setKeywords(apariciones(IStrongService.MAX_DETAIL_KEYWORDS + 1));
        when(sourceWordRepository.findByIdWordAndTransliteratedWordWithVerses(eq("G3588"), any(), any(), anyList(), any()))
                .thenReturn(entity);

        SourceWordResponse resp = strongService.getStrongDetail("G3588", null, null, null);

        assertEquals(IStrongService.MAX_DETAIL_KEYWORDS, resp.keywordsWithVerse().size());
        assertTrue(resp.truncated());
        assertEquals(1, resp.keywordsWithVerse().get(0).appearanceInVerse());
    }

    private static List<KeywordEntity> apariciones(int n) {
        List<KeywordEntity> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            KeywordEntity k = new KeywordEntity();
            k.setAppearanceInVerse(i);
            list.add(k);
        }
        return list;
    }

    /** Un código que existe pero no aparece en el libro pedido se trata como inexistente. */
    @Test
    void getStrongDetail_sinAparicionesEnElLibro_404() {
        when(sourceWordRepository.findByIdWordAndTransliteratedWordWithVerses(eq("H430"), any(), any(), anyList(), eq(40)))
                .thenReturn(null);

        assertThrows(ResourceNotFoundException.class, () -> strongService.getStrongDetail("H430", null, null, 40));
    }

    @Test
    void getStrongKeywordsGrouped_notFound_throws() {
        when(compoundWordRepository.findByIdWord(anyString())).thenReturn(null);
        assertThrows(ResourceNotFoundException.class, () -> strongService.getStrongKeywordsGrouped("A B"));
    }

    @Test
    void getStrongKeywordsGrouped_simple_agrupaPorLibro() {
        String keyUpper = "H430";

        SourceWordEntity entity = new SourceWordEntity();
        entity.setIdWord(keyUpper);
        when(sourceWordRepository.findById(keyUpper)).thenReturn(Optional.of(entity));
        when(sourceWordRepository.findKeywordCountsByBook(eq(keyUpper), anyList()))
                .thenReturn(List.of(new KeywordBookCount(5, 10L), new KeywordBookCount(7, 5L)));

        var res = strongService.getStrongKeywordsGrouped("h430");

        assertEquals(2, res.keywordBookStats().size());
        assertEquals(new KeywordBookStats(5, "Deuteronomio", 10), res.keywordBookStats().get(0));
        assertEquals(new KeywordBookStats(7, "Jueces", 5), res.keywordBookStats().get(1));
    }

    @Test
    void getStrongKeywordsGrouped_compuesto_agrupaPorLibro() {
        String keyUpper = "ABC DEF";

        CompoundWordEntity compound = new CompoundWordEntity();
        compound.setIdWord(keyUpper);
        when(compoundWordRepository.findByIdWord(keyUpper)).thenReturn(compound);
        when(compoundWordRepository.findKeywordCountsByBook(eq(keyUpper), anyList()))
                .thenReturn(List.of(new KeywordBookCount(1, 3L)));

        var res = strongService.getStrongKeywordsGrouped(keyUpper);

        assertEquals(List.of(new KeywordBookStats(1, "Génesis", 3)), res.keywordBookStats());
    }

    /** Sin filas, el nodo existe y va vacío: el cliente no tiene que distinguir null de []. */
    @Test
    void getStrongKeywordsGrouped_sinApariciones_listaVacia() {
        SourceWordEntity entity = new SourceWordEntity();
        entity.setIdWord("H1");
        when(sourceWordRepository.findById("H1")).thenReturn(Optional.of(entity));
        when(sourceWordRepository.findKeywordCountsByBook(eq("H1"), anyList())).thenReturn(List.of());

        assertTrue(strongService.getStrongKeywordsGrouped("H1").keywordBookStats().isEmpty());
    }

    /**
     * H854 está en el léxico pero no tiene keywords etiquetadas. Respondía 404 porque la
     * búsqueda de la cabecera exigía al menos una keyword; ahora son 200 con listas vacías.
     */
    @Test
    void getStrongKeywordsGrouped_enLexicoSinKeywords_listasVacias() {
        SourceWordEntity entity = new SourceWordEntity();
        entity.setIdWord("H854");
        when(sourceWordRepository.findById("H854")).thenReturn(Optional.of(entity));

        var res = strongService.getStrongKeywordsGrouped("H854");

        assertEquals("H854", res.idWord());
        assertTrue(res.keywordStats().isEmpty());
        assertTrue(res.keywordStatsTranslated().isEmpty());
        assertTrue(res.keywordBookStats().isEmpty());
    }

    @Test
    void getStrongKeywordsGrouped_simpleFueraDelLexico_404() {
        when(sourceWordRepository.findById("H99999")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> strongService.getStrongKeywordsGrouped("H99999"));
    }

    /**
     * {@code "H430 "} contiene un espacio, así que se tomaba por compuesto, y
     * {@code split(" ")[1]} reventaba con {@code ArrayIndexOutOfBoundsException}: un 500.
     */
    @Test
    void getStrongKeywordsGrouped_espacioFinal_seNormaliza() {
        SourceWordEntity entity = new SourceWordEntity();
        entity.setIdWord("H430");
        when(sourceWordRepository.findById("H430")).thenReturn(Optional.of(entity));

        assertEquals("H430", strongService.getStrongKeywordsGrouped("H430 ").idWord());
    }

    /** Ídem con espacio doble entre las dos mitades: {@code split(" ")[1]} era la cadena vacía. */
    @Test
    void getStrongKeywordsGrouped_espacioDoble_seNormaliza() {
        CompoundWordEntity compound = new CompoundWordEntity();
        compound.setIdWord("H853 H430");
        when(compoundWordRepository.findByIdWord("H853 H430")).thenReturn(compound);

        assertEquals("H853 H430", strongService.getStrongKeywordsGrouped("H853  H430").idWord());
        verify(compoundWordRepository).findLikeIdWord("H853", "H430", "H853 H430");
    }

    /**
     * Las mitades y el código que se excluye llegaban sin pasar a mayúsculas: el {@code LIKE}
     * de PostgreSQL distingue mayúsculas, y {@code c.idWord <> 'h853 h430'} no excluía nada.
     */
    @Test
    void getStrongKeywordsGrouped_minusculas_buscaRelacionadosEnMayusculas() {
        CompoundWordEntity compound = new CompoundWordEntity();
        compound.setIdWord("H853 H430");
        when(compoundWordRepository.findByIdWord("H853 H430")).thenReturn(compound);

        strongService.getStrongKeywordsGrouped("h853 h430");

        verify(compoundWordRepository).findLikeIdWord("H853", "H430", "H853 H430");
    }

    /** {@code %} y {@code _} son comodines del {@code LIKE}: no pueden llegar al repositorio. */
    @Test
    void getStrongKeywordsGrouped_comodinDeLike_404SinConsultar() {
        assertThrows(ResourceNotFoundException.class, () -> strongService.getStrongKeywordsGrouped("H%"));
        assertThrows(ResourceNotFoundException.class, () -> strongService.getStrongKeywordsGrouped("H_ G_"));
        verifyNoInteractions(sourceWordRepository, compoundWordRepository);
    }

    /** Tres códigos no son un compuesto: antes se ignoraba el tercero en silencio. */
    @Test
    void getStrongKeywordsGrouped_tresCodigos_404SinConsultar() {
        assertThrows(ResourceNotFoundException.class, () -> strongService.getStrongKeywordsGrouped("H1 H2 H3"));
        verifyNoInteractions(sourceWordRepository, compoundWordRepository);
    }

    @Test
    void getStrongDetail_espacioFinal_seNormaliza() {
        SourceWordEntity entity = new SourceWordEntity();
        entity.setIdWord("H430");
        when(sourceWordRepository.findByIdWordAndTransliteratedWordWithVerses(eq("H430"), any(), any(), anyList(), any()))
                .thenReturn(entity);

        assertEquals("H430", strongService.getStrongDetail("H430 ", null, null, null).idWord());
    }

    /** Conteos de una palabra simple: se conservan orden y conteo, y la palabra nula pasa a "". */
    @Test
    void getStrongKeywordsGrouped_simple_conteosPorPalabra() {
        SourceWordEntity entity = new SourceWordEntity();
        entity.setIdWord("H430");
        when(sourceWordRepository.findById("H430")).thenReturn(Optional.of(entity));
        when(sourceWordRepository.findKeywordCountsByIdWord(eq("H430"), anyList()))
                .thenReturn(filas("elohim", 5L, null, 2L));
        when(sourceWordRepository.findKeywordTranslatedCountsByIdWord(eq("H430"), anyList()))
                .thenReturn(filas("Dios", 3L));

        var res = strongService.getStrongKeywordsGrouped("H430");

        assertEquals(List.of(new KeywordStats("elohim", null, 5), new KeywordStats("", null, 2)), res.keywordStats());
        assertEquals(List.of(new KeywordStats(null, "Dios", 3)), res.keywordStatsTranslated());
    }

    /**
     * Hasta el 2026-10-20, en la rama simple, si la fila de cabeza traía la palabra nula se
     * descartaba la lista entera; la compuesta no. Existen keywords sin
     * {@code transliteratedWord} en producción, así que había códigos que respondían
     * {@code keywordStats: []} teniendo conteos. Ahora las dos ramas se comportan igual.
     */
    @Test
    void getStrongKeywordsGrouped_simple_cabezaNula_conservaLaLista() {
        SourceWordEntity entity = new SourceWordEntity();
        entity.setIdWord("H430");
        when(sourceWordRepository.findById("H430")).thenReturn(Optional.of(entity));
        when(sourceWordRepository.findKeywordCountsByIdWord(eq("H430"), anyList()))
                .thenReturn(filas(null, 5L, "elohim", 1L));
        when(sourceWordRepository.findKeywordTranslatedCountsByIdWord(eq("H430"), anyList()))
                .thenReturn(filas(null, 5L, "Dios", 1L));

        var res = strongService.getStrongKeywordsGrouped("H430");

        assertEquals(List.of(new KeywordStats("", null, 5), new KeywordStats("elohim", null, 1)), res.keywordStats());
        assertEquals(List.of(new KeywordStats(null, "", 5), new KeywordStats(null, "Dios", 1)), res.keywordStatsTranslated());
    }

    @Test
    void getStrongKeywordsGrouped_compuesto_conteosPorPalabra() {
        CompoundWordEntity compound = new CompoundWordEntity();
        compound.setIdWord("H853 H430");
        when(compoundWordRepository.findByIdWord("H853 H430")).thenReturn(compound);
        when(compoundWordRepository.findKeywordTransliteratedCountsByIdWord(eq("H853 H430"), anyList()))
                .thenReturn(filas(null, 4L, "et-elohim", 1L));
        when(compoundWordRepository.findKeywordTranslatedCountsByIdWord(eq("H853 H430"), anyList()))
                .thenReturn(filas("a Dios", 2L));

        var res = strongService.getStrongKeywordsGrouped("H853 H430");

        assertEquals(List.of(new KeywordStats("", null, 4), new KeywordStats("et-elohim", null, 1)), res.keywordStats());
        assertEquals(List.of(new KeywordStats(null, "a Dios", 2)), res.keywordStatsTranslated());
    }

    /** Pares palabra, conteo con la forma que devuelve el repositorio. */
    private static List<KeywordWordCount> filas(Object... pares) {
        List<KeywordWordCount> rows = new ArrayList<>();
        for (int i = 0; i < pares.length; i += 2) {
            rows.add(new KeywordWordCount((String) pares[i], (Long) pares[i + 1]));
        }
        return rows;
    }

    @Test
    void getStrongDetail_codigoMalFormado_404SinConsultar() {
        assertThrows(ResourceNotFoundException.class, () -> strongService.getStrongDetail("H%", null, null, null));
        verifyNoInteractions(sourceWordRepository, compoundWordRepository);
    }

}
