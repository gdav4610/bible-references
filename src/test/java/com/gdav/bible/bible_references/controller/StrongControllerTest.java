package com.gdav.bible.bible_references.controller;

import com.gdav.bible.bible_references.config.WordCacheCondition;
import com.gdav.bible.bible_references.service.IStrongService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Cubre las cabeceras de caché de {@code /api/strongs}.
 *
 * <p>Hermana de {@code BibleControllerTest}: la lista de códigos populares vive en
 * {@link WordCacheCondition} y el CDN solo obedece, así que lo que decide qué se cachea es el
 * {@code Cache-Control} que sale de aquí.
 *
 * <p>El servicio devuelve {@code null} a propósito. El controlador se limita a pasar el cuerpo
 * tal cual, así que construir los dos records —de 14 y 9 componentes— solo añadiría ruido sin
 * ejercitar nada: lo que se afirma aquí son cabeceras, no contenido.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StrongController — cabeceras de caché")
class StrongControllerTest {

    @Mock
    private IStrongService strongService;

    /** Instancia real, no mock: la lista de códigos populares es justo lo que se prueba. */
    private final WordCacheCondition wordCacheCondition = new WordCacheCondition();

    private StrongController controlador() {
        return new StrongController(strongService, wordCacheCondition);
    }

    private String cacheControlDeStats(String strongCode) {
        when(strongService.getStrongKeywordsGrouped(anyString())).thenReturn(null);
        return controlador().getStrongKeywordsGrouped(strongCode)
                .getHeaders().getFirst(HttpHeaders.CACHE_CONTROL);
    }

    private String cacheControlDeDetalle(String strongCode, String translit, String traducida) {
        when(strongService.getStrongDetail(anyString(), any(), any(), any())).thenReturn(null);
        return controlador().getStrongDetail(strongCode, translit, traducida, null)
                .getHeaders().getFirst(HttpHeaders.CACHE_CONTROL);
    }

    @Test
    @DisplayName("un código de la lista de populares viaja con Cache-Control")
    void codigoPopularEsCacheable() {
        // H430 (Elohim) está en WordCacheCondition.
        assertThat(cacheControlDeStats("H430"))
                .as("sin esta cabecera CloudFront no guarda nada, por muy alto que esté el TTL")
                .contains("max-age=86400")
                .contains("public");
    }

    @Test
    @DisplayName("un código fuera de la lista sale con no-store")
    void codigoNoPopularNoEsCacheable() {
        // H7225 no está en la lista: el defecto es no cachear, de modo que ampliar la caché
        // sea siempre una decisión explícita y no un olvido.
        assertThat(cacheControlDeStats("H7225")).isEqualTo("no-store");
    }

    @Test
    @DisplayName("el detalle sale con no-store incluso para un código popular")
    void detalleNuncaEsCacheable() {
        // H430 sí es popular, pero /details depende de `transliteratedWord` y
        // `translatedWord`, que no van en la clave de caché de CloudFront. Cachearlo serviría
        // una respuesta filtrada a quien pidió otra cosa.
        assertThat(cacheControlDeDetalle("H430", null, null))
                .as("la lista de populares no debe abrir la puerta a cachear el detalle")
                .isEqualTo("no-store");
    }

    @Test
    @DisplayName("el detalle con filtros tampoco es cacheable")
    void detalleConFiltrosNoEsCacheable() {
        assertThat(cacheControlDeDetalle("H430", "elohim", "Dios")).isEqualTo("no-store");
    }
}
