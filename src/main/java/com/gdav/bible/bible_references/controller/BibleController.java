package com.gdav.bible.bible_references.controller;

import com.gdav.bible.bible_references.mapper.KeywordMapper;
import com.gdav.bible.bible_references.model.Verse;
import com.gdav.bible.bible_references.repository.VerseRepository;
import com.gdav.bible.bible_references.repository.entity.VerseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

@RestController
@RequestMapping("/api/bible")
@CrossOrigin(origins = "*")
public class BibleController {

    private final VerseRepository repository;

    @Autowired
    BibleController(VerseRepository repository) {
        this.repository = repository;
    }


    @GetMapping("/chapter/{idBook}/{chapter}")
    public Map<String, Object> getChapter(@PathVariable int idBook, @PathVariable int chapter,
                                          @RequestParam(required = false) Integer idVerse,
                                          @RequestParam(name = "includeLXX", required = false) Boolean includeLxx) {

        String bookName = getBookName(idBook);

        // determinar idBible según includeLxx (true => 2, false/absent => 1)
        int idBible = (includeLxx != null && includeLxx) ? 2 : 1;

        //Consulta repositorio por id de la entidad
        List<VerseEntity> versesEntityList = repository.findAllByIdBookAndChapter(idBible, idBook, chapter, idVerse );

        // Si no hay resultados, devolver lista vacía de versos
        List<Verse> versesList;
        if (versesEntityList == null || versesEntityList.isEmpty()) {
            versesList = Collections.emptyList();
        } else {
            // Mapear entidades a modelos incluyendo keywords (KeywordMapper ahora incluye compoundWordEntity)
            versesList = versesEntityList.stream().map( verseEntity ->
                    new Verse(
                            verseEntity.getVerse(),
                            verseEntity.getText(),
                            KeywordMapper.toKeywordList(verseEntity.getKeywords())
                    )
            ).toList();
        }

        // 📘 Respuesta
        Map<String, Object> response = new HashMap<>();
        response.put("book", bookName);
        response.put("chapter", chapter);
        response.put("verses", versesList);

        return response;

    }


    // 🔤 Mapeo simple de ID → Nombre del libro
    private String getBookName(int id) {
        Map<Integer, String> map = new HashMap();
        map.putAll(Map.of(
                1, "Génesis",
                2, "Éxodo",
                3, "Levítico",
                4, "Números",
                5, "Deuteronomio",
                6, "Josué",
                7, "Jueces",
                8, "Rut",
                9, "1 Samuel",
                10, "2 Samuel"
                )
        );

        map.putAll(Map.of(
                11, "1 Reyes",
                12, "2 Reyes",
                13, "1 Crónicas",
                14, "2 Crónicas",
                15, "Esdras",
                16, "Nehemías",
                17, "Ester",
                18, "Job",
                19, "Salmos",
                20, "Proverbios"
                )
        );

        map.putAll(Map.of(
                21, "Eclesiastés",
                22, "Cantares",
                23, "Isaías",
                24, "Jeremías",
                25, "Lamentaciones",
                26, "Ezequiel",
                27, "Daniel",
                28, "Oseas",
                29, "Joel",
                30, "Amós"
                )
        );

        map.putAll(Map.of(
                31, "Abdías",
                32, "Jonás",
                33, "Miqueas",
                34, "Nahum",
                35, "Habacuc",
                36, "Sofonías",
                37, "Hageo",
                38, "Zacarías",
                39, "Malaquías",
                40, "Mateo"
                )
        );

        map.putAll(Map.of(
                41, "Marcos",
                42, "Lucas",
                43, "Juan",
                44, "Hechos",
                45, "Romanos",
                46, "1 Corintios",
                47, "2 Corintios",
                48, "Gálatas",
                49, "Efesios",
                50, "Filipenses"
                )
        );

        map.putAll(Map.of(
                51, "Colosenses",
                52, "1 Tesalonicenses",
                53, "2 Tesalonicenses",
                54, "1 Timoteo",
                55, "2 Timoteo",
                56, "Tito",
                57, "Filemón",
                58, "Hebreos",
                59, "Santiago",
                60, "1 Pedro"
                )
        );

        map.putAll(Map.of(
                61, "2 Pedro",
                62, "1 Juan",
                63, "2 Juan",
                64, "3 Juan",
                65, "Judas",
                66, "Apocalipsis"
                )
        );
        return map.getOrDefault(id, "Desconocido");
    }
}
