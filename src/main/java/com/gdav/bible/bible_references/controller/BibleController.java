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
    public Map<String, Object> getChapter(@PathVariable int idBook, @PathVariable int chapter) {

        String bookName = getBookName(idBook);

        //Consulta repositorio por id de la entidad
        List<VerseEntity> versesEntityList = repository.findAllByIdBookAndChapter( idBook, chapter );

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
        Map<Integer, String> map = Map.of(
                1, "Génesis",
                2, "Éxodo",
                3, "Levítico"
                // ... puedes agregar más
        );
        return map.getOrDefault(id, "Desconocido");
    }
}
