package com.gdav.bible.bible_references.service;

import com.gdav.bible.bible_references.model.SourceWordResponse;
import com.gdav.bible.bible_references.model.SourceWordWithKeywordStatsResponse;

/** Contrato de la lógica de negocio relacionada con códigos Strong y sus keywords. */
public interface IStrongService {

    /**
     * Devuelve la palabra fuente asociada a un código Strong junto con estadísticas de keywords.
     *
     * <p>Incluye {@code keywordBookStats}: las apariciones de la keyword agrupadas por libro,
     * ordenadas por conteo descendente.
     *
     * @param strongCode código Strong (simple o compuesto separado por espacio)
     * @return palabra fuente con estadísticas de keywords
     * @throws com.gdav.bible.bible_references.exception.ResourceNotFoundException si el código no existe o está mal formado
     */
    SourceWordWithKeywordStatsResponse getStrongKeywordsGrouped(String strongCode);

    /**
     * Devuelve el detalle de una palabra fuente con sus versículos asociados.
     *
     * <p>Sin {@code transliteratedWord} ni {@code translatedWord} no se filtra por palabra y se
     * devuelven todas las apariciones. Si se pasa al menos uno, basta con que coincida uno de
     * los dos. La lista va acotada a {@link #MAX_DETAIL_KEYWORDS}; {@code truncated} indica si
     * había más.
     *
     * @param strongCode         código Strong (simple o compuesto)
     * @param transliteratedWord filtro opcional por palabra transliterada
     * @param translatedWord     filtro opcional por palabra traducida
     * @param idBook             filtro opcional por libro (1–66); {@code null} no filtra
     * @return detalle de la palabra fuente
     * @throws com.gdav.bible.bible_references.exception.ResourceNotFoundException si el código no
     *         existe, está mal formado o no tiene apariciones que cumplan los filtros
     */
    SourceWordResponse getStrongDetail(String strongCode, String transliteratedWord, String translatedWord, Integer idBook);

    /**
     * Tope de apariciones en el detalle.
     *
     * <p>Con ~330 bytes por aparición medidos en producción, 2.000 son unos 0,7 MB. Sin tope,
     * los códigos más frecuentes —el artículo griego, {@code H853}— superarían los 6 MB de una
     * invocación síncrona de Lambda: 502, y el entorno de ejecución perdido.
     */
    int MAX_DETAIL_KEYWORDS = 2_000;
}