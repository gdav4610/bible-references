package com.gdav.bible.bible_references.repository;

/**
 * Proyección de solo lectura para el query nativo usado en VerseRepository.findAllByWord
 * Los nombres de los getters deben coincidir con los alias de la consulta nativa.
 */
public interface SearchProjection {

    Integer getIdBible();

    Integer getIdBook();

    Integer getChapter();

    Integer getVerse();

    String getText();

    String getStrongNumber();

    String getInflectionWord();

    String getTransliteratedWord();

    String getTranslatedWord();

    Integer getAppearanceInVerse();

}

