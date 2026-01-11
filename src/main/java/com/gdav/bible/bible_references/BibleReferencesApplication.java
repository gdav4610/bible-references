package com.gdav.bible.bible_references;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class BibleReferencesApplication {

	public static void main(String[] args) {
		SpringApplication.run(BibleReferencesApplication.class, args);
	}

}
