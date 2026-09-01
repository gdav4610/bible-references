package com.gdav.bible.bible_references.repository;

import com.gdav.bible.bible_references.repository.entity.VerseEntity;
import com.gdav.bible.bible_references.repository.entity.VerseId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SearchRepository extends JpaRepository<VerseEntity, VerseId> {

    /**
     * Busca versículos por texto y devuelve una fila por cada keyword de cada versículo.
     *
     * <p><b>El límite se aplica a versículos, no a filas.</b> Ese es el motivo del CTE: un
     * {@code LIMIT} sobre el resultado del join cortaría un versículo por la mitad y lo
     * devolvería con solo parte de sus keywords, que es peor que no devolverlo. Acotando
     * primero los versículos y uniendo después, cada versículo que sale, sale completo.
     *
     * <p>Acotar aquí y no en Java no es solo cuestión de tamaño de respuesta: el join contra
     * {@code bible_word_references} es la parte cara, y así solo se hace sobre los
     * versículos que se van a devolver.
     *
     * <p><b>{@code public.f_unaccent} no es un capricho de nombre.</b> El índice GIN de
     * trigramas que hace rápida esta búsqueda es un índice <i>de expresión</i>, y PostgreSQL
     * solo lo usa si la consulta escribe la expresión exactamente igual que el índice.
     * {@code unaccent()} no se puede indexar —es {@code STABLE}, porque depende de un
     * diccionario—, así que el índice está creado sobre un envoltorio {@code IMMUTABLE}
     * llamado {@code f_unaccent} y la consulta tiene que llamar a ese mismo envoltorio.
     *
     * <p><b>Esta consulta no funciona sin la función.</b> Antes de desplegar este código hay
     * que crear {@code public.f_unaccent} y el índice en la base de datos; el procedimiento,
     * la verificación y la reversión están en {@code docs/runbook-indice-trigram-search.md}.
     *
     * @param maxVerses tope de versículos. Conviene pedir uno más de los que se quieren
     *                  mostrar: si vuelven {@code maxVerses}, es que había más, y eso permite
     *                  informar de que el resultado está truncado sin una segunda consulta
     *                  de conteo —que repetiría el escaneo con la expresión regular—.
     */
    @Query(value = "WITH encontrados AS ( " +
            "  SELECT verse.id_bible, verse.id_book, verse.chapter, verse.verse, verse.text " +
            "  FROM bible_schema.bible_verses AS verse " +
            "  WHERE verse.id_bible = :id_bible AND public.f_unaccent(verse.text) ~* public.f_unaccent(:translatedWord) " +
            "  ORDER BY verse.id_book ASC, verse.chapter ASC, verse.verse ASC " +
            "  LIMIT :maxVerses " +
            ") " +
            "SELECT DISTINCT " +
            "encontrados.id_bible    AS idBible, " +
            "encontrados.id_book     AS idBook, " +
            "encontrados.chapter     AS chapter, " +
            "encontrados.verse       AS verse, " +
            "encontrados.text        AS text, " +
            "keywords.strong_number        AS strongNumber, " +
            "keywords.inflection_word      AS inflectionWord, " +
            "keywords.transliterated_word  AS transliteratedWord, " +
            "keywords.translated_word      AS translatedWord, " +
            "keywords.appearance_in_verse  AS appearanceInVerse " +
            "FROM encontrados " +
            "LEFT JOIN bible_schema.bible_word_references AS keywords ON encontrados.id_bible = keywords.id_bible AND encontrados.id_book = keywords.id_book  AND encontrados.chapter = keywords.chapter  AND encontrados.verse = keywords.verse " +
            "ORDER BY encontrados.id_book ASC, encontrados.chapter ASC, encontrados.verse ASC, keywords.appearance_in_verse ASC", nativeQuery = true)
    List<SearchProjection> findAllByWord(@Param("id_bible") Integer idBible,
                                         @Param("translatedWord") String translatedWord,
                                         @Param("maxVerses") int maxVerses);

}