package com.gdav.bible.bible_references.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.servlet.http.HttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas de {@link OriginVerifyFilter}.
 *
 * <p>Este filtro es lo único que impide que la URL de ejecución de API Gateway se use para
 * saltarse CloudFront, así que las dos propiedades que importan son simétricas y ninguna es
 * evidente: <b>que rechace cuando hay secreto</b> y <b>que no estorbe cuando no lo hay</b>
 * —si esto último fallara, romperia ECS y el arranque local sin que nada lo anticipe—.
 */
@DisplayName("Filtro de verificación de origen")
class OriginVerifyFilterTest {

    private static final String SECRETO = "un-secreto-cualquiera";

    @Nested
    @DisplayName("con secreto configurado")
    class ConSecreto {

        @Test
        @DisplayName("deja pasar la petición que trae la cabecera correcta")
        void cabeceraCorrectaPasa() throws Exception {
            var chain = new MockFilterChain();
            var respuesta = new MockHttpServletResponse();

            filtro(SECRETO).doFilter(peticion("/api/bible/chapter/1/1", SECRETO), respuesta, chain);

            assertThat(chain.getRequest()).as("la petición debe llegar al siguiente filtro").isNotNull();
            assertThat(respuesta.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        }

        @Test
        @DisplayName("rechaza con 403 la petición sin cabecera")
        void sinCabeceraEs403() throws Exception {
            var chain = new MockFilterChain();
            var respuesta = new MockHttpServletResponse();

            filtro(SECRETO).doFilter(peticion("/api/bible/chapter/1/1", null), respuesta, chain);

            assertThat(chain.getRequest()).as("la cadena debe cortarse aquí").isNull();
            assertThat(respuesta.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        }

        @Test
        @DisplayName("rechaza con 403 la petición con un secreto distinto")
        void secretoIncorrectoEs403() throws Exception {
            var chain = new MockFilterChain();
            var respuesta = new MockHttpServletResponse();

            filtro(SECRETO).doFilter(peticion("/api/bible/chapter/1/1", "otro-valor"), respuesta, chain);

            assertThat(chain.getRequest()).isNull();
            assertThat(respuesta.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        }

        @Test
        @DisplayName("el cuerpo del rechazo no revela por qué se rechazó")
        void elRechazoNoFiltraInformacion() throws Exception {
            var respuesta = new MockHttpServletResponse();

            filtro(SECRETO).doFilter(peticion("/api/bible/chapter/1/1", "otro-valor"), respuesta, new MockFilterChain());

            assertThat(respuesta.getContentAsString())
                    .as("no debe distinguirse de un 403 del filtro de origen ni nombrar la cabecera")
                    .doesNotContain(OriginVerifyFilter.HEADER)
                    .doesNotContain(SECRETO);
        }

        @Test
        @DisplayName("el health check queda exento aunque no traiga la cabecera")
        void healthCheckExento() throws Exception {
            var chain = new MockFilterChain();
            var respuesta = new MockHttpServletResponse();

            // El ALB no envía cabeceras propias en el health check del target group: si
            // este caso fallara, activar el secreto en ECS mataría las tasks en bucle.
            filtro(SECRETO).doFilter(peticion("/actuator/health", null), respuesta, chain);

            assertThat(chain.getRequest()).isNotNull();
            assertThat(respuesta.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        }
    }

    @Nested
    @DisplayName("sin secreto configurado")
    class SinSecreto {

        @Test
        @DisplayName("deja pasar todo: en ECS la comprobación la hace el ALB")
        void filtroInactivoDejaPasar() throws Exception {
            var chain = new MockFilterChain();
            var respuesta = new MockHttpServletResponse();

            filtro("").doFilter(peticion("/api/bible/chapter/1/1", null), respuesta, chain);

            assertThat(chain.getRequest()).isNotNull();
            assertThat(respuesta.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        }

        @Test
        @DisplayName("un secreto en blanco cuenta como ausente, no como secreto vacío")
        void secretoEnBlancoNoActivaElFiltro() throws Exception {
            var chain = new MockFilterChain();

            // Una variable de entorno mal puesta puede llegar como espacios. Tratarlo como
            // secreto válido dejaría el filtro exigiendo una cabecera imposible de acertar.
            filtro("   ").doFilter(peticion("/api/bible/chapter/1/1", null), new MockHttpServletResponse(), chain);

            assertThat(chain.getRequest()).isNotNull();
        }
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Construye el filtro ya inicializado, como haría Spring al llamar a {@code @PostConstruct}. */
    private OriginVerifyFilter filtro(String secreto) {
        var filtro = new OriginVerifyFilter();
        ReflectionTestUtils.setField(filtro, "secret", secreto);
        filtro.init();
        return filtro;
    }

    private MockHttpServletRequest peticion(String uri, String cabecera) {
        var peticion = new MockHttpServletRequest("GET", uri);
        if (cabecera != null) {
            peticion.addHeader(OriginVerifyFilter.HEADER, cabecera);
        }
        return peticion;
    }
}