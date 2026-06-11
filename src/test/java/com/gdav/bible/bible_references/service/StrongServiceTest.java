package com.gdav.bible.bible_references.service;

import com.gdav.bible.bible_references.exception.ResourceNotFoundException;
import com.gdav.bible.bible_references.model.SourceWordResponse;
import com.gdav.bible.bible_references.model.SourceWordWithKeywordStatsResponse;
import com.gdav.bible.bible_references.repository.CompoundWordRepository;
import com.gdav.bible.bible_references.repository.OutboxRepository;
import com.gdav.bible.bible_references.repository.SourceWordRepository;
import com.gdav.bible.bible_references.repository.entity.CompoundWordEntity;
import com.gdav.bible.bible_references.repository.entity.OutboxEventEntity;
import com.gdav.bible.bible_references.repository.entity.SourceWordEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StrongServiceTest {

    @Mock
    private SourceWordRepository sourceWordRepository;

    @Mock
    private CompoundWordRepository compoundWordRepository;

    @Mock
    private OutboxRepository outboxRepository;

    @InjectMocks
    private StrongService strongService;

    @Captor
    ArgumentCaptor<OutboxEventEntity> outboxCaptor;

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
        SourceWordWithKeywordStatsResponse res = strongService.getStrongKeywordsGrouped(strongCode, false);

        assertNotNull(res);
        assertEquals(keyUpper, res.idWord());
        // el primer estadístico corresponde al transliterated counts (2)
//        assertEquals(0, res.keywordStats().get(0).count());
        // Persiste evento outbox
        verify(outboxRepository, times(1)).save(outboxCaptor.capture());
        OutboxEventEntity captured = outboxCaptor.getValue();
        assertTrue(captured.getPayload().contains(keyUpper));
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

        when(sourceWordRepository.findByIdWordAndTransliteratedWordWithVerses(eq(keyUpper), any(), any(), anyList()))
                .thenReturn(entity);
/*
        // No keywords -> empty list mapping
        when(sourceWordRepository.findKeywordCountsByIdWord(eq(keyUpper), anyList())).thenReturn(Arrays.asList());
        when(sourceWordRepository.findKeywordTranslatedCountsByIdWord(eq(keyUpper), anyList())).thenReturn(Arrays.asList());
*/
        SourceWordResponse resp = strongService.getStrongDetail(strongCode, null, null, false);
        assertNotNull(resp);
        assertEquals(keyUpper, resp.idWord());
    }

    @Test
    void getStrongKeywordsGrouped_notFound_throws() {
        when(compoundWordRepository.findByIdWord(anyString())).thenReturn(null);
        assertThrows(ResourceNotFoundException.class, () -> strongService.getStrongKeywordsGrouped("A B", false));
    }

}
