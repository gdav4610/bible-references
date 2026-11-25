package com.gdav.bible.bible_references.controller;

import com.gdav.bible.bible_references.config.WordCacheCondition;
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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/strongs")
@Validated
@Tag(name = "Strong", description = "Consulta de códigos Strong: estadísticas de keywords y detalle con versículos")
public class StrongController {

    /**
     * Cuánto puede guardar CloudFront un código Strong marcado como cacheable.
     *
     * <p>Mismo valor que los capítulos en {@code BibleController}, y por la misma razón: el
     * dato no cambia, el límite es cuánto se tarda en propagar una corrección.
     */
    private static final long CACHE_STRONG_SECONDS = 86_400L;

    private final IStrongService strongService;
    private final WordCacheCondition wordCacheCondition;

    public StrongController(IStrongService strongService, WordCacheCondition wordCacheCondition) {
        this.strongService = strongService;
        this.wordCacheCondition = wordCacheCondition;
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
                                      "keywordBookStats": [
                                        { "idBook": 5, "bookName": "Deuteronomio", "count": 10 },
                                        { "idBook": 7, "bookName": "Jueces", "count": 5 }
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
            @PathVariable @NotBlank String strongCode) {
        SourceWordWithKeywordStatsResponse response = strongService.getStrongKeywordsGrouped(strongCode);

        // Misma convención que BibleController: la lista de códigos populares vive en el
        // código —WordCacheCondition— y el CDN solo obedece. El defecto es no cachear, de
        // modo que ampliar la caché sea siempre una decisión explícita.
        //
        // Este endpoint es el mejor candidato de la API: desde que se quitó `includeLXX` no
        // tiene ningún parámetro de consulta, así que la ruta determina la respuesta por
        // completo y no hay forma de que dos peticiones distintas colisionen en la clave.
        CacheControl cacheControl = wordCacheCondition.shouldCacheWithJustOneParam(strongCode)
                ? CacheControl.maxAge(CACHE_STRONG_SECONDS, TimeUnit.SECONDS).cachePublic()
                : CacheControl.noStore();

        return ResponseEntity.ok().cacheControl(cacheControl).body(response);
    }

    @Operation(
            summary = "Detalle de un código Strong con sus versículos",
            description = """
                    Devuelve la palabra fuente y la lista de apariciones (keywords con su versículo).
                    Los filtros `transliteratedWord`, `translatedWord` e `idBook` son opcionales y acotan las apariciones.
                    Sin filtro de palabra se devuelven todas, hasta un máximo de 2.000; `truncated` indica si había más.""")
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
                                      ],
                                      "truncated": false
                                    }"""))),
            @ApiResponse(responseCode = "400", description = "`idBook` fuera del rango 1–66"),
            @ApiResponse(responseCode = "404", description = "Código Strong inexistente o sin apariciones en el libro pedido",
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
            @Parameter(description = "Filtro opcional por libro, del 1 (Génesis) al 66 (Apocalipsis)", example = "1")
            @RequestParam(name = "idBook", required = false) @Min(1) @Max(66) Integer idBook) {
        SourceWordResponse response = strongService.getStrongDetail(strongCode, transliteratedWord, translatedWord, idBook);

        // `no-store` siempre, aunque el código esté en la lista de populares. A diferencia de
        // /stats, aquí la respuesta depende de `transliteratedWord`, `translatedWord` e `idBook`, y la
        // política de caché de CloudFront **no las lleva en la clave**: cachear esto haría que
        // una petición filtrada recibiera la respuesta sin filtrar, o la de otro filtro.
        //
        // Se deja explícito en vez de arreglar solo la política porque la corrección tiene que
        // sobrevivir a que alguien cambie la política, que es configuración remota. Si algún
        // día se quiere cachear el detalle, el orden es: primero las query strings en la
        // clave, después esta línea.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }

}