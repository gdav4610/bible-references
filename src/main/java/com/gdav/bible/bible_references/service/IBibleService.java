package com.gdav.bible.bible_references.service;

import com.gdav.bible.bible_references.model.ChapterResponse;
import com.gdav.bible.bible_references.model.SearchResultResponse;

/** Contrato de la lógica de negocio relacionada con capítulos y búsqueda de versículos. */
public interface IBibleService {

    /**
     * Devuelve un capítulo (o un versículo puntual) con sus keywords.
     *
     * @param idBook    id del libro
     * @param chapter   número de capítulo
     * @param idVerse   número de versículo opcional; si es {@code null} devuelve el capítulo completo
     * @param includeLxx si se debe usar la fuente LXX (idBible 2) en lugar de la estándar (idBible 1)
     * @return capítulo con su lista de versículos (posiblemente vacía)
     */
    ChapterResponse getChapter(int idBook, int chapter, Integer idVerse, Boolean includeLxx);

    /**
     * Busca versículos que contengan la palabra indicada, agrupados por versículo.
     *
     * @param q          término de búsqueda
     * @param includeLxx si se debe buscar sobre la fuente LXX
     * @return resultados de búsqueda (posiblemente vacíos)
     */
    SearchResultResponse search(String q, Boolean includeLxx);
}