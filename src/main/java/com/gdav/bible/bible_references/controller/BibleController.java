package com.gdav.bible.bible_references.controller;

import com.gdav.bible.bible_references.config.VerseCacheCondition;
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
@RequestMapping("/api/bible")
@Validated
@Tag(name = "Bible", description = "Lectura de capítulos y búsqueda de versículos")
public class BibleController {

    /**
     * Cuánto puede guardar CloudFront un capítulo marcado como cacheable.
     *
     * <p>24 horas es conservador tratándose de texto bíblico, que no cambia: el límite real
     * no es que el dato caduque, sino cuánto se tarda en propagar una corrección de erratas
     * o de keywords. Si hiciera falta antes, una invalidación en CloudFront lo resuelve.
     */
    private static final long CACHE_CHAPTER_SECONDS = 86_400L;

    private final IBibleService bibleService;
    private final VerseCacheCondition verseCacheCondition;

    public BibleController(IBibleService bibleService, VerseCacheCondition verseCacheCondition) {
        this.bibleService = bibleService;
        this.verseCacheCondition = verseCacheCondition;
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

        ChapterResponse respuesta = bibleService.getChapter(idBook, chapter, idVerse, includeLxx);

        // Solo los capítulos de la lista de VerseCacheCondition viajan con Cache-Control, y
        // por tanto solo esos los guarda CloudFront en el borde. El resto salen con
        // `no-store`, de modo que el comportamiento por defecto sigue siendo no cachear:
        // añadir un capítulo es una decisión explícita, no un olvido.
        //
        // Marcarlo aquí y no en CloudFront es deliberado: la lista de capítulos populares
        // vive en el código, así que ampliarla no exige tocar la configuración del CDN ni
        // crear un comportamiento de caché por cada ruta.
        CacheControl cacheControl = verseCacheCondition.shouldCacheChapter(idBook, chapter, idVerse, includeLxx)
                ? CacheControl.maxAge(CACHE_CHAPTER_SECONDS, TimeUnit.SECONDS).cachePublic()
                : CacheControl.noStore();

        return ResponseEntity.ok().cacheControl(cacheControl).body(respuesta);
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
            @Parameter(description = "Máximo de versículos a devolver. Por defecto "
                    + IBibleService.DEFAULT_SEARCH_LIMIT + ", máximo "
                    + IBibleService.MAX_SEARCH_LIMIT + ". La respuesta indica en "
                    + "'truncated' si había más resultados.", example = "500")
            @RequestParam(name = "limit", required = false) @Positive Integer limit) {

        // `no-store` explícito, no ausencia de cabecera. Hasta ahora la búsqueda no emitía
        // ninguna y dependía de que la política de caché de CloudFront tuviera DefaultTTL=0
        // para no guardarse; es decir, la seguridad de este endpoint vivía en un valor de
        // configuración remoto, fuera del repositorio y editable sin pasar por revisión.
        //
        // Importa porque el resultado depende de `q` y de `limit`: una política que ignore
        // las query strings —cualquiera de las gestionadas de CloudFront— serviría la
        // respuesta de una búsqueda a otra distinta. Con `no-store` no hay política que
        // pueda cachear esto, se elija la que se elija en el borde.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(bibleService.search(q, limit));
    }

}