package com.gdav.bible.bible_references.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.autoconfigure.application.DiskSpaceHealthContributorAutoConfiguration;
import org.springframework.boot.health.autoconfigure.application.DiskSpaceHealthIndicatorProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica el cableado de {@code /actuator/health} tal como queda en {@code application.yaml}.
 *
 * <p>Hermana de {@link DataSourceConfigurationTest} y con el mismo motivo de existir: lee el
 * YAML <b>real</b> mediante {@link ConfigDataApplicationContextInitializer}, de modo que una
 * errata en el nombre de una propiedad haga fallar la prueba en vez de ignorarse en silencio.
 *
 * <p><b>El fallo que cubre.</b> Hasta el 2026-08-15, {@code /actuator/health} devolvía
 * <b>503 en Lambda y 200 en ECS con el mismo código</b>. No era una caída —todos los endpoints
 * de la API respondían 200— sino {@code DiskSpaceHealthIndicator} midiendo el directorio de
 * trabajo, que en Lambda es {@code /var/task}: un montaje de solo lectura cuyo espacio
 * utilizable queda por debajo del umbral de 10 MB. Apuntarlo a {@code /tmp} —el único
 * directorio escribible de Lambda, y presente también en el contenedor de ECS— devuelve al
 * indicador algo real que medir.
 *
 * <p>Se comprueba aquí y no contra la función porque el valor <b>no se observa desde ninguna
 * parte en producción</b>: si la clave estuviera mal escrita, Spring la ignoraría, el
 * indicador volvería a {@code user.dir} y el síntoma sería otra vez un 503 sin explicación.
 */
@DisplayName("Health check (application.yaml)")
class HealthConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(DiskSpaceHealthContributorAutoConfiguration.class));

    @Test
    @DisplayName("diskSpace mide /tmp, no el directorio de trabajo")
    void diskSpaceApuntaATmp() {
        runner.run(contexto -> {
            DiskSpaceHealthIndicatorProperties propiedades =
                    contexto.getBean(DiskSpaceHealthIndicatorProperties.class);

            // En Lambda el directorio de trabajo es /var/task, de solo lectura: el indicador
            // daría DOWN siempre y con él todo el endpoint, que es lo que ocurría.
            assertThat(propiedades.getPath())
                    .as("si sale el directorio del proyecto, la propiedad no se aplicó")
                    .isEqualTo(new File("/tmp"));
        });
    }
}
