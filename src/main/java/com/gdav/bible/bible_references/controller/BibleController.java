package com.gdav.bible.bible_references.controller;

import com.gdav.bible.bible_references.model.ChapterResponse;
import com.gdav.bible.bible_references.model.SearchResultResponse;
import com.gdav.bible.bible_references.service.IBibleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bible")
@Validated
@Tag(name = "Bible", description = "Lectura de capítulos y búsqueda de versículos")
public class BibleController {

    private final IBibleService bibleService;

    public BibleController(IBibleService bibleService) {
        this.bibleService = bibleService;
    }

    @Operation(
            summary = "Obtiene un capítulo (o un versículo puntual)",
            description = """
                    Devuelve los versículos de un capítulo con sus keywords. Si se envía `idVerse`
                    solo se devuelve ese versículo. `idBook` va de 1 (Génesis) a 66 (Apocalipsis).""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Capítulo con sus versículos",
                    content = @Content(schema = @Schema(implementation = ChapterResponse.class),
                            examples = @ExampleObject(name = "Génesis 1", value = """
                                    {
                                      "book": "Génesis",
                                      "chapter": 1,
                                      "verses": [
                                        {
                                          "verseNumber": 1,
                                          "text": "En el principio creó Dios los cielos y la tierra.",
                                          "keywords": [
                                            {
                                              "inflectionWord": "אֱלֹהִים",
                                              "translatedWord": "Dios",
                                              "transliteratedWord": "elohim",
                                              "strongNumber": "H430",
                                              "sourceTransliteration": "elohim",
                                              "sourceInflection": "אֱלֹהִים",
                                              "sourceMeaning": "Dios, dioses",
                                              "compoundTransliteration": null,
                                              "compoundInflection": null,
                                              "compoundMeaning": null
                                            }
                                          ]
                                        }
                                      ]
                                    }"""))),
            @ApiResponse(responseCode = "400", description = "Parámetros de ruta inválidos",
                    content = @Content(examples = @ExampleObject(value = """
                            {
                              "errorCode": "VALIDATION_ERROR",
                              "message": "Validation failed for one or more parameters",
                              "path": "/api/bible/chapter/0/1",
                              "timestamp": "2026-07-07T12:00:00Z",
                              "fieldErrors": [ { "field": "getChapter.idBook", "message": "must be greater than 0" } ]
                            }""")))
    })
    @GetMapping("/chapter/{idBook}/{chapter}")
    public ResponseEntity<ChapterResponse> getChapter(
            @Parameter(description = "Id del libro (1=Génesis … 66=Apocalipsis)", example = "1")
            @PathVariable @Positive int idBook,
            @Parameter(description = "Número de capítulo", example = "1")
            @PathVariable @Positive int chapter,
            @Parameter(description = "Versículo puntual opcional; si se omite, devuelve el capítulo completo", example = "1")
            @RequestParam(required = false) Integer idVerse,
            @Parameter(description = "Usar fuente LXX (Septuaginta)", example = "false")
            @RequestParam(name = "includeLXX", required = false) Boolean includeLxx) {
        return ResponseEntity.ok(bibleService.getChapter(idBook, chapter, idVerse, includeLxx));
    }

    @Operation(
            summary = "Busca versículos por palabra",
            description = "Busca versículos que contengan la palabra `q`, agrupando por versículo. `q` es obligatorio.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Resultados de búsqueda",
                    content = @Content(schema = @Schema(implementation = SearchResultResponse.class),
                            examples = @ExampleObject(name = "Búsqueda 'elohim'", value = """
                                    {
                                      "verses": [
                                        {
                                          "idBook": 1,
                                          "chapter": 1,
                                          "verseNumber": 1,
                                          "text": "En el principio creó Dios los cielos y la tierra.",
                                          "keywords": [
                                            {
                                              "inflectionWord": "אֱלֹהִים",
                                              "translatedWord": "Dios",
                                              "transliteratedWord": "elohim",
                                              "strongNumber": "H430",
                                              "sourceTransliteration": null,
                                              "sourceInflection": null,
                                              "sourceMeaning": null,
                                              "compoundTransliteration": null,
                                              "compoundInflection": null,
                                              "compoundMeaning": null
                                            }
                                          ]
                                        }
                                      ]
                                    }"""))),
            @ApiResponse(responseCode = "400", description = "Parámetro 'q' ausente o en blanco",
                    content = @Content(examples = @ExampleObject(value = """
                            {
                              "errorCode": "VALIDATION_ERROR",
                              "message": "Validation failed for one or more parameters",
                              "path": "/api/bible/search",
                              "timestamp": "2026-07-07T12:00:00Z",
                              "fieldErrors": [ { "field": "search.q", "message": "must not be blank" } ]
                            }""")))
    })
    @GetMapping(value = "/search")
    public ResponseEntity<SearchResultResponse> search(
            @Parameter(description = "Palabra a buscar", example = "elohim")
            @RequestParam(name = "q") @NotBlank String q,
            @Parameter(description = "Usar fuente LXX (Septuaginta)", example = "false")
            @RequestParam(name = "includeLXX", required = false) Boolean includeLxx) {
        return ResponseEntity.ok(bibleService.search(q, includeLxx));
    }

}