package com.gdav.bible.bible_references;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.core.Ordered;

/**
 * <h2>Por qué la caché lleva orden explícito</h2>
 * Los métodos cacheados de la capa de servicio son además {@code @Transactional}, y ambos
 * aspectos traen {@code LOWEST_PRECEDENCE} por defecto: cuál envuelve a cuál queda sin
 * definir. Si el aspecto transaccional quedara por fuera, <b>un acierto de caché seguiría
 * pidiendo una conexión al pool y abriendo y cerrando una transacción</b> contra RDS, que es
 * justo el viaje que la caché existe para evitar.
 *
 * <p>Con {@code HIGHEST_PRECEDENCE} el aspecto de caché envuelve al transaccional: en un
 * acierto no se toca la base de datos, y en un fallo la transacción se abre por dentro, como
 * antes, de modo que las varias consultas de {@code StrongService} siguen compartiendo una.
 */
@SpringBootApplication
@EnableCaching(order = Ordered.HIGHEST_PRECEDENCE)
public class BibleReferencesApplication {

	public static void main(String[] args) {
		SpringApplication.run(BibleReferencesApplication.class, args);
	}

}
