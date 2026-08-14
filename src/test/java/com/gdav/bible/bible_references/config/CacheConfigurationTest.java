package com.gdav.bible.bible_references.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica el cableado de la caché tal como queda configurado en {@code application.yaml}.
 *
 * <p>Existe porque el 2026-08-13 se sustituyó Hazelcast por Caffeine (hallazgo 4: Hazelcast
 * se descubría por multicast, que no funciona dentro de una VPC) y el resto de la suite no
 * habría detectado un fallo: {@code BibleReferencesApplicationTests} tiene su
 * {@code contextLoads} comentado, así que el contexto de Spring nunca llega a levantarse, y
 * las pruebas de servicio son unitarias con Mockito y no pasan por la infraestructura de
 * caché.
 *
 * <p>Se usa {@link ConfigDataApplicationContextInitializer} para que el contexto lea el
 * {@code application.yaml} REAL en vez de propiedades repetidas aquí. Es la diferencia entre
 * comprobar que Caffeine funciona y comprobar que <em>esta aplicación</em> está bien
 * configurada: una errata en el YAML tiene que hacer fallar esta prueba.
 *
 * <p>Solo se carga {@link CacheAutoConfiguration}, de modo que no hace falta base de datos.
 */
class CacheConfigurationTest {

    /** Los mismos nombres declarados en spring.cache.cache-names. */
    private static final String[] CACHES = {
            "verses", "sourceWords", "compoundWords", "keywordCounts", "compoundWordCounts",
            "keywordTranslatedCounts", "compoundTranslatedCounts", "keywordTransliteratedWord",
            "compoundTransliteratedWord"
    };

    /**
     * {@code CacheAutoConfiguration} es {@code @ConditionalOnBean(CacheAspectSupport.class)}
     * y no se activa por sí sola: necesita {@code @EnableCaching}, que en la aplicación vive
     * en {@code BibleReferencesApplication} y este runner no carga. Se aporta aquí.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    static class CacheHabilitada {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(CacheHabilitada.class)
            .withConfiguration(AutoConfigurations.of(CacheAutoConfiguration.class));

    @Test
    void elCacheManagerEsCaffeine() {
        runner.run(context -> assertThat(context)
                .getBean(CacheManager.class)
                .isInstanceOf(CaffeineCacheManager.class));
    }

    @Test
    void seCreanTodasLasCachesDeclaradas() {
        runner.run(context -> {
            CacheManager manager = context.getBean(CacheManager.class);
            assertThat(manager.getCacheNames()).containsExactlyInAnyOrder(CACHES);
            for (String nombre : CACHES) {
                assertThat(manager.getCache(nombre))
                        .as("la caché '%s' debe existir", nombre)
                        .isNotNull();
            }
        });
    }

    /**
     * El {@code spec} de Caffeine se valida al parsearse, no al usarse: una errata como
     * {@code expireAfterWrites} haría fallar el arranque de la aplicación en producción.
     * Guardar y leer una entrada obliga a construir la caché de verdad.
     */
    @Test
    void elSpecDeCaffeineEsValidoYLaCacheFunciona() {
        runner.run(context -> {
            var cache = context.getBean(CacheManager.class).getCache("verses");
            assertThat(cache).isNotNull();
            cache.put("clave", "valor");
            assertThat(cache.get("clave", String.class)).isEqualTo("valor");
        });
    }
}
