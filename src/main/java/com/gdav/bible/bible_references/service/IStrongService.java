package com.gdav.bible.bible_references.service;

import com.gdav.bible.bible_references.model.SourceWordResponse;
import com.gdav.bible.bible_references.model.SourceWordWithKeywordStatsResponse;

/** Contrato de la lógica de negocio relacionada con códigos Strong y sus keywords. */
public interface IStrongService {

    /**
     * Devuelve la palabra fuente asociada a un código Strong junto con estadísticas de keywords.
     *
     * @param strongCode código Strong (simple o compuesto separado por espacio)
     * @return palabra fuente con estadísticas de keywords
     * @throws com.gdav.bible.bible_references.exception.ResourceNotFoundException si el código no existe
     */
    SourceWordWithKeywordStatsResponse getStrongKeywordsGrouped(String strongCode);

    /**
     * Devuelve el detalle de una palabra fuente con sus versículos asociados.
     *
     * @param strongCode         código Strong (simple o compuesto)
     * @param transliteratedWord filtro opcional por palabra transliterada
     * @param translatedWord     filtro opcional por palabra traducida
     * @return detalle de la palabra fuente
     * @throws com.gdav.bible.bible_references.exception.ResourceNotFoundException si el código no existe
     */
    SourceWordResponse getStrongDetail(String strongCode, String transliteratedWord, String translatedWord);
}