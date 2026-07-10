package com.gdav.bible.bible_references.controller;

import com.gdav.bible.bible_references.model.SourceWordResponse;
import com.gdav.bible.bible_references.model.SourceWordWithKeywordStatsResponse;
import com.gdav.bible.bible_references.service.IStrongService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/strongs")
@Validated
@Tag(name = "Strong", description = "Consulta de códigos Strong: estadísticas de keywords y detalle con versículos")
public class StrongController {

    private final IStrongService strongService;

    public StrongController(IStrongService strongService) {
        this.strongService = strongService;
    }

    @Operation(
            summary = "Estadísticas de keywords de un código Strong",
            description = """
                    Devuelve la palabra fuente asociada al código Strong con el conteo de sus keywords
                    (transliteradas y traducidas). Acepta códigos simples (`H430`) o compuestos
                    separados por espacio (`H853 H430`).""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Palabra fuente con estadísticas",
                    content = @Content(schema = @Schema(implementation = SourceWordWithKeywordStatsResponse.class),
                            examples = @ExampleObject(name = "H430 (Elohim)", value = """
                                    {
                                      "idWord": "H430",
                                      "transliteration": "elohim",
                                      "inflection": "אֱלֹהִים",
                                      "meaning": "Dios, dioses",
                                      "idParent": "H433",
                                      "idParentSec": null,
                                      "parentMeaning": "deidad",
                                      "parentSecMeaning": null,
                                      "firstAppBook": 1,
                                      "firstAppChapter": 1,
                                      "firstAppVerse": 1,
                                      "keywordStats": [
                                        { "transliteratedWord": "elohim", "translatedWord": null, "count": 2346 }
                                      ],
                                      "keywordStatsTranslated": [
                                        { "transliteratedWord": null, "translatedWord": "Dios", "count": 2601 }
                                      ],
                                      "compoundRelatedList": []
                                    }"""))),
            @ApiResponse(responseCode = "404", description = "Código Strong inexistente",
                    content = @Content(examples = @ExampleObject(value = """
                            {
                              "errorCode": "RESOURCE_NOT_FOUND",
                              "message": "Strong code not found: H99999",
                              "path": "/api/strongs/H99999/stats",
                              "timestamp": "2026-07-07T12:00:00Z",
                              "fieldErrors": []
                            }""")))
    })
    @GetMapping("/{strongCode}/stats")
    public ResponseEntity<SourceWordWithKeywordStatsResponse> getStrongKeywordsGrouped(
            @Parameter(description = "Código Strong (simple o compuesto)", example = "H430")
            @PathVariable @NotBlank String strongCode,
            @Parameter(description = "Incluir fuentes de la Septuaginta (LXX)", example = "false")
            @RequestParam(name = "includeLXX", required = false) Boolean includeLXX) {
        SourceWordWithKeywordStatsResponse response = strongService.getStrongKeywordsGrouped(strongCode, includeLXX);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "Detalle de un código Strong con sus versículos",
            description = """
                    Devuelve la palabra fuente y la lista de apariciones (keywords con su versículo).
                    Los filtros `transliteratedWord` y `translatedWord` son opcionales y acotan las apariciones.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Detalle de la palabra fuente",
                    content = @Content(schema = @Schema(implementation = SourceWordResponse.class),
                            examples = @ExampleObject(name = "H430 (Elohim)", value = """
                                    {
                                      "idWord": "H430",
                                      "transliteration": "elohim",
                                      "inflection": "אֱלֹהִים",
                                      "meaning": "Dios, dioses",
                                      "idParent": "H433",
                                      "idParentSec": null,
                                      "parentMeaning": "deidad",
                                      "parentSecMeaning": null,
                                      "keywordsWithVerse": [
                                        {
                                          "inflectionWord": "אֱלֹהִים",
                                          "translatedWord": "Dios",
                                          "transliteratedWord": "elohim",
                                          "idBook": 1,
                                          "chapter": 1,
                                          "verseNumber": 1,
                                          "verseText": "En el principio creó Dios los cielos y la tierra.",
                                          "appearanceInVerse": 1
                                        }
                                      ]
                                    }"""))),
            @ApiResponse(responseCode = "404", description = "Código Strong inexistente",
                    content = @Content(examples = @ExampleObject(value = """
                            {
                              "errorCode": "RESOURCE_NOT_FOUND",
                              "message": "Strong code not found: H99999",
                              "path": "/api/strongs/H99999/details",
                              "timestamp": "2026-07-07T12:00:00Z",
                              "fieldErrors": []
                            }""")))
    })
    @GetMapping(value = "/{strongCode}/details")
    public ResponseEntity<SourceWordResponse> getStrongDetail(
            @Parameter(description = "Código Strong (simple o compuesto)", example = "H430")
            @PathVariable @NotBlank String strongCode,
            @Parameter(description = "Filtro opcional por palabra transliterada", example = "elohim")
            @RequestParam(name = "transliteratedWord", required = false) String transliteratedWord,
            @Parameter(description = "Filtro opcional por palabra traducida", example = "Dios")
            @RequestParam(name = "translatedWord", required = false) String translatedWord,
            @Parameter(description = "Incluir fuentes de la Septuaginta (LXX)", example = "false")
            @RequestParam(name = "includeLXX", required = false) Boolean includeLXX) {
        SourceWordResponse response = strongService.getStrongDetail(strongCode, transliteratedWord, translatedWord, includeLXX);
        return ResponseEntity.ok(response);
    }

}