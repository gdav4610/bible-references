package com.gdav.bible.bible_references.config;

import com.hazelcast.config.Config;
import com.hazelcast.config.YamlConfigBuilder;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.spring.cache.HazelcastCacheManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;

@Configuration
public class HazelcastConfig {

    /**
     * Crea explícitamente la instancia de Hazelcast a partir de {@code hazelcast.yaml}.
     * Declararla aquí evita depender de la autoconfiguración de Spring Boot, cuyo comportamiento
     * de detección de Hazelcast cambió entre versiones.
     */
    @Bean
    @ConditionalOnMissingBean(HazelcastInstance.class)
    public HazelcastInstance hazelcastInstance() throws IOException {
        try (InputStream input = new ClassPathResource("hazelcast.yaml").getInputStream()) {
            Config config = new YamlConfigBuilder(input).build();
            // Alinea el classloader de Hazelcast con el que carga las entidades de la app.
            // Bajo DevTools ese es el RestartClassLoader; sin él, es el classloader de la app.
            // Evita ClassCastException al (de)serializar entidades cacheadas.
            config.setClassLoader(getClass().getClassLoader());
            return Hazelcast.newHazelcastInstance(config);
        }
    }

    @Bean
    @ConditionalOnMissingBean(CacheManager.class)
    public CacheManager cacheManager(HazelcastInstance hazelcastInstance) {
        return new HazelcastCacheManager(hazelcastInstance);
    }

}