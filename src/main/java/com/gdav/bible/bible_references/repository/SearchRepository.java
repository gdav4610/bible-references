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
     * @param maxVerses tope de versículos. Conviene pedir uno más de los que se quieren
     *                  mostrar: si vuelven {@code maxVerses}, es que había más, y eso permite
     *                  informar de que el resultado está truncado sin una segunda consulta
     *                  de conteo —que repetiría el escaneo con la expresión regular—.
     */
    @Query(value = "WITH encontrados AS ( " +
            "  SELECT verse.id_bible, verse.id_book, verse.chapter, verse.verse, verse.text " +
            "  FROM bible_schema.bible_verses AS verse " +
            "  WHERE verse.id_bible = :id_bible AND public.unaccent(verse.text) ~* public.unaccent(:translatedWord) " +
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