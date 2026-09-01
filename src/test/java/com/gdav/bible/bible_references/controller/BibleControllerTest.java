package com.gdav.bible.bible_references.controller;

import com.gdav.bible.bible_references.config.VerseCacheCondition;
import com.gdav.bible.bible_references.model.SearchResultResponse;
import com.gdav.bible.bible_references.service.IBibleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Cubre las cabeceras de caché que emite el controlador.
 *
 * <h2>Por qué esta prueba vive aquí y no en {@code StreamLambdaHandlerTest}</h2>
 * Las de capítulo se prueban de extremo a extremo en el handler, pero
 * {@code /api/bible/search} <b>no se puede ejercitar así</b>: su consulta nativa usa
 * {@code unaccent} y el operador {@code ~*} de PostgreSQL, y aquellas pruebas corren sobre
 * H2. Con el servicio mockeado se prueba lo único que aquí decide el controlador —qué
 * {@code Cache-Control} sale— sin depender del motor de base de datos.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BibleController — cabeceras de caché")
class BibleControllerTest {

    @Mock
    private IBibleService bibleService;

    private final VerseCacheCondition verseCacheCondition = new VerseCacheCondition();

    private BibleController controlador() {
        return new BibleController(bibleService, verseCacheCondition);
    }

    @Test
    @DisplayName("la búsqueda sale con no-store explícito, no sin cabecera")
    void busquedaNoEsCacheable() {
        when(bibleService.search(anyString(), any()))
                .thenReturn(new SearchResultResponse(List.of(), 0, false));

        ResponseEntity<SearchResultResponse> respuesta = controlador().search("Dios", null);

        // La distinción importa: hasta el 2026-08-14 la búsqueda no emitía ninguna cabecera y
        // dependía de que la política de CloudFront tuviera DefaultTTL=0. Ausencia de cabecera
        // deja la decisión al borde; `no-store` la cierra desde el repositorio.
        assertThat(respuesta.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
                .as("sin esto, una política que ignore las query strings serviría el resultado "
                        + "de una búsqueda a otra distinta")
                .isEqualTo("no-store");
    }

    @Test
    @DisplayName("la búsqueda no es cacheable tampoco con límite explícito")
    void busquedaConLimiteTampocoEsCacheable() {
        when(bibleService.search(anyString(), any()))
                .thenReturn(new SearchResultResponse(List.of(), 0, true));

        // `limit` va en la clave de la política propia, pero eso no debe abrir la puerta a
        // cachear: el resultado depende además de `q`, y el truncado lo hace variable.
        ResponseEntity<SearchResultResponse> respuesta = controlador().search("Dios", 2000);

        assertThat(respuesta.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    }
}
