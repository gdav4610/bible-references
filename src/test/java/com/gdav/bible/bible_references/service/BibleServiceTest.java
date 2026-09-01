package com.gdav.bible.bible_references.service;

import com.gdav.bible.bible_references.model.Keyword;
import com.gdav.bible.bible_references.model.SearchResponse;
import com.gdav.bible.bible_references.model.Verse;
import com.gdav.bible.bible_references.repository.SearchProjection;
import com.gdav.bible.bible_references.repository.SearchRepository;
import com.gdav.bible.bible_references.repository.VerseRepository;
import com.gdav.bible.bible_references.repository.entity.VerseEntity;
import com.gdav.bible.bible_references.repository.entity.VerseId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BibleServiceTest {

    @Mock
    private VerseRepository verseRepository;

    @Mock
    private OutboxRecorder outboxRecorder;

    @Mock
    private SearchRepository searchRepository;

    @InjectMocks
    private BibleService bibleService;

    @Test
    void getChapter_emptyReturnsEmptyVerses() {
        when(verseRepository.findAllByIdBookAndChapter(eq(1), eq(1), eq(1), any())).thenReturn(Arrays.asList());

        var result = bibleService.getChapter(1,1,null,false);
        assertNotNull(result);
        assertTrue(result.verses().isEmpty());
    }

    @Test
    void getChapter_returnsVersesAndSavesOutbox() {
        VerseEntity v = new VerseEntity();
        // asignar id usando VerseId
        v.setId(new VerseId(1, 1, 1, 1));
        v.setText("hello");
        // keywords empty
        when(verseRepository.findAllByIdBookAndChapter(eq(1), eq(1), eq(1), any())).thenReturn(Arrays.asList(v));

        var result = bibleService.getChapter(1,1,null,false);
        assertNotNull(result);
        List<Verse> verses = result.verses();
        assertEquals(1, verses.size());

        verify(outboxRecorder, times(1)).record(anyString(), anyString());
    }

    @Test
    void search_noResults_returnsEmpty() {
        when(searchRepository.findAllByWord(anyInt(), anyString(), anyInt())).thenReturn(Arrays.asList());
        var resp = bibleService.search("term", null);
        assertNotNull(resp);
        assertTrue(resp.verses().isEmpty());
        assertFalse(resp.truncated());
    }

    @Test
    void search_withResults_groupsAndMaps() {
        SearchProjection p = mock(SearchProjection.class);
        when(p.getIdBook()).thenReturn(1);
        when(p.getChapter()).thenReturn(1);
        when(p.getVerse()).thenReturn(1);
        when(p.getText()).thenReturn("text");
        when(p.getInflectionWord()).thenReturn("inf");
        when(p.getTranslatedWord()).thenReturn("tr");
        when(p.getTransliteratedWord()).thenReturn("ts");
        when(p.getStrongNumber()).thenReturn("S1");

        when(searchRepository.findAllByWord(anyInt(), anyString(), anyInt())).thenReturn(Arrays.asList(p));

        var resp = bibleService.search("term", null);
        assertNotNull(resp);
        List<SearchResponse> verses = resp.verses();
        assertEquals(1, verses.size());
        SearchResponse sr = verses.get(0);
        assertEquals(1, sr.idBook());
        // SearchResponse record uses 'verseNumber' as field name
        assertEquals(1, sr.verseNumber());
        assertEquals(1, sr.keywords().size());
        Keyword k = sr.keywords().get(0);
        assertEquals("inf", k.inflectionWord());
        assertEquals(1, resp.returnedVerses());
        assertFalse(resp.truncated());
    }

    // ------------------------------------------------------------------
    // Acotado de la búsqueda
    //
    // Sin límite, una palabra frecuente devuelve varios MB y la respuesta supera los 6 MB
    // que admite una invocación síncrona de Lambda: la petición falla con 502 y además se
    // lleva por delante el entorno de ejecución. Estas pruebas cubren esa frontera.
    // ------------------------------------------------------------------

    @Test
    void search_sinLimite_pideElPorDefectoMasUno() {
        when(searchRepository.findAllByWord(anyInt(), anyString(), anyInt())).thenReturn(Arrays.asList());

        bibleService.search("term", null);

        // El +1 es la señal de truncado: sin él haría falta una segunda consulta de conteo.
        verify(searchRepository).findAllByWord(anyInt(), anyString(),
                eq(IBibleService.DEFAULT_SEARCH_LIMIT + 1));
    }

    @Test
    void search_limitePorEncimaDelMaximo_seRecorta() {
        when(searchRepository.findAllByWord(anyInt(), anyString(), anyInt())).thenReturn(Arrays.asList());

        bibleService.search("term", 999_999);

        verify(searchRepository).findAllByWord(anyInt(), anyString(),
                eq(IBibleService.MAX_SEARCH_LIMIT + 1));
    }

    @Test
    void search_limiteNoPositivo_caeAlPorDefecto() {
        when(searchRepository.findAllByWord(anyInt(), anyString(), anyInt())).thenReturn(Arrays.asList());

        // El controlador ya rechaza los no positivos con @Positive, pero el servicio no debe
        // poder devolver una respuesta sin acotar aunque le llamen desde otro sitio.
        bibleService.search("term", 0);

        verify(searchRepository).findAllByWord(anyInt(), anyString(),
                eq(IBibleService.DEFAULT_SEARCH_LIMIT + 1));
    }

    @Test
    void search_cuandoHayDeMas_truncaYAvisa() {
        int limite = 2;
        // Tres versículos distintos para un límite de dos: el tercero es la señal de que
        // había más, y no debe aparecer en la respuesta. Las proyecciones se construyen
        // antes del when(): crear mocks dentro del thenReturn deja el stubbing a medias.
        List<SearchProjection> tres = Arrays.asList(proyeccion(1, 1, 1), proyeccion(1, 1, 2), proyeccion(1, 1, 3));
        when(searchRepository.findAllByWord(anyInt(), anyString(), anyInt())).thenReturn(tres);

        var resp = bibleService.search("term", limite);

        assertEquals(limite, resp.verses().size());
        assertEquals(limite, resp.returnedVerses());
        assertTrue(resp.truncated(), "un recorte silencioso es indistinguible de un resultado completo");
    }

    @Test
    void search_cuandoCabeJusto_noSeMarcaTruncado() {
        List<SearchProjection> dos = Arrays.asList(proyeccion(1, 1, 1), proyeccion(1, 1, 2));
        when(searchRepository.findAllByWord(anyInt(), anyString(), anyInt())).thenReturn(dos);

        var resp = bibleService.search("term", 2);

        assertEquals(2, resp.verses().size());
        assertFalse(resp.truncated(), "exactamente el límite no es truncar");
    }

    private SearchProjection proyeccion(int idBook, int chapter, int verse) {
        SearchProjection p = mock(SearchProjection.class);
        when(p.getIdBook()).thenReturn(idBook);
        when(p.getChapter()).thenReturn(chapter);
        when(p.getVerse()).thenReturn(verse);
        return p;
    }
}
