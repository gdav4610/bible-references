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
     * <p>El resultado va <b>siempre acotado</b>. No es una preferencia de presentación: sin
     * límite, una búsqueda de una palabra frecuente devuelve varios MB y la respuesta supera
     * el máximo de 6 MB que admite una invocación síncrona de Lambda, con lo que la petición
     * falla con 502 y además se lleva por delante el entorno de ejecución.
     *
     * @param q          término de búsqueda
     * @param limit      máximo de versículos a devolver; {@code null} aplica
     *                   {@link #DEFAULT_SEARCH_LIMIT} y cualquier valor mayor se recorta a
     *                   {@link #MAX_SEARCH_LIMIT}
     * @return resultados de búsqueda (posiblemente vacíos), indicando si se truncaron
     */
    SearchResultResponse search(String q, Integer limit);

    /** Versículos que se devuelven cuando el cliente no pide un límite. */
    int DEFAULT_SEARCH_LIMIT = 500;

    /**
     * Tope absoluto, aunque el cliente pida más.
     *
     * <p>Con ~1,4 KB por versículo medidos en producción, 2.000 versículos son unos 2,8 MB:
     * queda margen suficiente por debajo de los 6 MB de Lambda incluso si los versículos
     * fueran bastante más largos que la media.
     */
    int MAX_SEARCH_LIMIT = 2000;
}