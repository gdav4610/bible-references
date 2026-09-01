package com.gdav.bible.bible_references.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica el cableado del pool de conexiones tal como queda en {@code application.yaml}.
 *
 * <p>Hermana de {@link CacheConfigurationTest} y con el mismo motivo de existir: usa
 * {@link ConfigDataApplicationContextInitializer} para leer el YAML <b>real</b>, de modo que
 * una errata en el nombre de una propiedad haga fallar la prueba.
 *
 * <p>Eso importa aquí más que en otros sitios porque <b>una clave mal escrita en la
 * configuración de Hikari no da ningún error</b>: Spring la ignora en silencio, el pool
 * arranca con los valores por defecto y todo parece correcto hasta que falla en producción a
 * las tres de la mañana. Es exactamente lo que ocurrió con
 * {@code SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE} en la función Lambda, cuyo valor no se
 * puede observar desde ninguna parte.
 *
 * <p>Construir el {@link DataSource} <b>no abre ninguna conexión</b> —Hikari es perezoso—, así
 * que esta prueba no necesita base de datos.
 */
@DisplayName("Pool de conexiones (application.yaml)")
class DataSourceConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class));

    @Test
    @DisplayName("connectionTimeout es de 5 s, no los 30 s por defecto")
    void connectionTimeoutEsCorto() {
        runner.run(contexto -> {
            HikariDataSource ds = contexto.getBean(DataSource.class).unwrap(HikariDataSource.class);

            // Con los 30 s por defecto, una petición durante la parada nocturna de RDS se
            // cuelga hasta que expira la integración de API Gateway y el visitante recibe un
            // 504 tras medio minuto. Con 5 s recibe un error enseguida.
            assertThat(ds.getConnectionTimeout())
                    .as("un valor de 30000 significa que la propiedad no se aplicó")
                    .isEqualTo(5_000L);
        });
    }

    @Test
    @DisplayName("connectionTimeout deja margen sobre el arranque en frío medido")
    void connectionTimeoutDejaMargenSobreElArranqueEnFrio() {
        runner.run(contexto -> {
            HikariDataSource ds = contexto.getBean(DataSource.class).unwrap(HikariDataSource.class);

            // La primera invocación tras restaurar SnapStart tardó hasta 3.716 ms en las
            // medidas del 2026-08-14. El timeout tiene que quedar por encima de ese peor
            // caso, o un arranque en frío lento se convertiría en un fallo.
            assertThat(ds.getConnectionTimeout())
                    .as("no bajar de 3.716 ms, el peor arranque en frío medido en producción")
                    .isGreaterThan(3_716L);
        });
    }

    @Test
    @DisplayName("allow-pool-suspension sigue activo: sin él SnapStart no puede vaciar el pool")
    void permiteSuspenderElPool() {
        runner.run(contexto -> {
            HikariDataSource ds = contexto.getBean(DataSource.class).unwrap(HikariDataSource.class);

            // HikariCheckpointRestoreLifecycle llama a suspendPool() antes del snapshot; sin
            // esta bandera lanza excepción y la función publica versiones en estado Failed.
            assertThat(ds.isAllowPoolSuspension())
                    .as("quitarlo rompe SnapStart, no las pruebas: por eso se comprueba aquí")
                    .isTrue();
        });
    }
}
