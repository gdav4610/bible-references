package com.gdav.bible.bible_references.service;

import com.gdav.bible.bible_references.config.VerseCacheCondition;
import com.gdav.bible.bible_references.config.WordCacheCondition;
import com.gdav.bible.bible_references.repository.CompoundWordRepository;
import com.gdav.bible.bible_references.repository.SearchRepository;
import com.gdav.bible.bible_references.repository.SourceWordRepository;
import com.gdav.bible.bible_references.repository.VerseRepository;
import com.gdav.bible.bible_references.repository.entity.SourceWordEntity;
import com.gdav.bible.bible_references.repository.entity.VerseEntity;
import com.gdav.bible.bible_references.repository.entity.VerseId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Comprueba la caché de la capa de servicio <b>a través de los proxies reales de Spring</b>.
 *
 * <p>Hace falta porque las expresiones SpEL de {@code @Cacheable} —la clave y la condición—
 * no se compilan: se evalúan al invocar el método. {@code BibleServiceTest} y
 * {@code StrongServiceTest} construyen el servicio con Mockito y no pasan por la
 * infraestructura de caché, así que una errata en {@code #idBook} o en el nombre del bean de
 * condición pasaría la suite entera y fallaría en la primera petición de producción.
 *
 * <p>Se lee el {@code application.yaml} real, de modo que si un nombre de caché deja de estar
 * declarado en {@code spring.cache.cache-names} estas pruebas fallan.
 */
class ServiceCachingTest {

    @Configuration(proxyBeanMethods = false)
    @EnableCaching(order = Ordered.HIGHEST_PRECEDENCE)
    static class Contexto {

        @Bean VerseRepository verseRepository() { return mock(VerseRepository.class); }
        @Bean SearchRepository searchRepository() { return mock(SearchRepository.class); }
        @Bean SourceWordRepository sourceWordRepository() { return mock(SourceWordRepository.class); }
        @Bean CompoundWordRepository compoundWordRepository() { return mock(CompoundWordRepository.class); }

        @Bean VerseCacheCondition verseCacheCondition() { return new VerseCacheCondition(); }
        @Bean WordCacheCondition wordCacheCondition() { return new WordCacheCondition(); }

        @Bean IBibleService bibleService(VerseRepository v, SearchRepository s) {
            return new BibleService(v, s);
        }

        @Bean IStrongService strongService(SourceWordRepository s, CompoundWordRepository c) {
            return new StrongService(s, c);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Contexto.class)
            .withConfiguration(AutoConfigurations.of(CacheAutoConfiguration.class));

    /** Génesis 1 está en la lista de VerseCacheCondition: la segunda llamada no toca el repositorio. */
    @Test
    void capituloDeLaListaSeCacheaTrasLaPrimeraLlamada() {
        runner.run(context -> {
            VerseRepository repo = context.getBean(VerseRepository.class);
            when(repo.findAllByIdBookAndChapter(eq(1), eq(1), eq(1), any())).thenReturn(List.of(versiculo()));

            IBibleService servicio = context.getBean(IBibleService.class);
            var primera = servicio.getChapter(1, 1, null, false);
            var segunda = servicio.getChapter(1, 1, null, false);

            assertThat(segunda).isSameAs(primera);
            verify(repo, times(1)).findAllByIdBookAndChapter(eq(1), eq(1), eq(1), any());
        });
    }

    /** El capítulo 7 de Génesis no está en la lista: la condición debe dejarlo fuera. */
    @Test
    void capituloFueraDeLaListaNoSeCachea() {
        runner.run(context -> {
            VerseRepository repo = context.getBean(VerseRepository.class);
            when(repo.findAllByIdBookAndChapter(eq(1), eq(1), eq(7), any())).thenReturn(List.of(versiculo()));

            IBibleService servicio = context.getBean(IBibleService.class);
            servicio.getChapter(1, 7, null, false);
            servicio.getChapter(1, 7, null, false);

            verify(repo, times(2)).findAllByIdBookAndChapter(eq(1), eq(1), eq(7), any());
        });
    }

    /** La misma biblia pedida como null y como false no debe ocupar dos entradas. */
    @Test
    void includeLxxNuloYFalsoCompartenEntrada() {
        runner.run(context -> {
            VerseRepository repo = context.getBean(VerseRepository.class);
            when(repo.findAllByIdBookAndChapter(eq(1), eq(1), eq(1), any())).thenReturn(List.of(versiculo()));

            IBibleService servicio = context.getBean(IBibleService.class);
            servicio.getChapter(1, 1, null, null);
            servicio.getChapter(1, 1, null, false);

            verify(repo, times(1)).findAllByIdBookAndChapter(eq(1), eq(1), eq(1), any());
        });
    }

    /**
     * Regresión del defecto que arrastraba la caché de repositorio: su clave omitía
     * {@code translatedWord}, así que dos peticiones del mismo código Strong con distinta
     * palabra traducida colisionaban y la segunda recibía la respuesta de la primera.
     */
    @Test
    void detalleDistingueLasPeticionesPorTranslatedWord() {
        runner.run(context -> {
            SourceWordRepository repo = context.getBean(SourceWordRepository.class);
            when(repo.findByIdWordAndTransliteratedWordWithVerses(eq("H430"), any(), eq("Dios"), anyList()))
                    .thenReturn(palabra("H430", "dios"));
            when(repo.findByIdWordAndTransliteratedWordWithVerses(eq("H430"), any(), eq("jueces"), anyList()))
                    .thenReturn(palabra("H430", "jueces"));

            IStrongService servicio = context.getBean(IStrongService.class);
            var conDios = servicio.getStrongDetail("H430", null, "Dios");
            var conJueces = servicio.getStrongDetail("H430", null, "jueces");

            assertThat(conDios.meaning()).isEqualTo("dios");
            assertThat(conJueces.meaning()).isEqualTo("jueces");
        });
    }

    private static VerseEntity versiculo() {
        VerseEntity v = new VerseEntity();
        v.setId(new VerseId(1, 1, 1, 1));
        v.setText("En el principio creó Dios los cielos y la tierra.");
        return v;
    }

    private static SourceWordEntity palabra(String idWord, String meaning) {
        SourceWordEntity e = new SourceWordEntity();
        e.setIdWord(idWord);
        e.setMeaning(meaning);
        return e;
    }
}
