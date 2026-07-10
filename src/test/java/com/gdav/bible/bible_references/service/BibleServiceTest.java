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
        when(searchRepository.findAllByWord(anyInt(), anyString())).thenReturn(Arrays.asList());
        var resp = bibleService.search("term", false);
        assertNotNull(resp);
        assertTrue(resp.verses().isEmpty());
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

        when(searchRepository.findAllByWord(anyInt(), anyString())).thenReturn(Arrays.asList(p));

        var resp = bibleService.search("term", false);
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
    }
}
