package com.gdav.bible.bible_references.config;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Encapsula la lógica que decide si una llamada a VerseRepository debe cachearse.
 * Mantener esta clase facilita agregar nuevas reglas de cache sin tocar las firmas de los repositorios.
 */
@Component("verseCacheCondition")
public class VerseCacheCondition {

    public static class Condition {
        private final Integer idBible;
        private final Integer idBook;
        private final Integer chapter;
        private final Integer idVerse;

        public Condition(Integer idBible, Integer idBook, Integer chapter, Integer idVerse) {
            this.idBible = idBible;
            this.idBook = idBook;
            this.chapter = chapter;
            this.idVerse = idVerse;
        }

        public boolean matches(Integer idBible, Integer idBook, Integer chapter, Integer idVerse) {
            return Objects.equals(this.idBible, idBible)
                    && Objects.equals(this.idBook, idBook)
                    && Objects.equals(this.chapter, chapter)
                    && Objects.equals(this.idVerse, idVerse);
        }
    }

    private final List<Condition> conditions = new ArrayList<>();

    public VerseCacheCondition() {
        conditions.add(new Condition(1, 1, 1, null));
        conditions.add(new Condition(1, 1, 2, null));
        conditions.add(new Condition(1, 1, 3, null));
        conditions.add(new Condition(1, 40, 1, null));
        conditions.add(new Condition(1, 43, 1, null));
        conditions.add(new Condition(1, 43, 3, null));
        conditions.add(new Condition(1, 66, 1, null));
    }

    /**
     * Método invocado desde SpEL en @Cacheable: devuelve true si alguna condición configurada coincide.
     */
    public boolean shouldCacheVerse(Integer idBible, Integer idBook, Integer chapter, Integer idVerse) {
        for (Condition c : conditions) {
            if (c.matches(idBible, idBook, chapter, idVerse)) {
                return true;
            }
        }
        return false;
    }

    // utilidades para tests o future config: permitir agregar condiciones programáticamente
    public void addCondition(Integer idBible, Integer idBook, Integer chapter, Integer idVerse) {
        conditions.add(new Condition(idBible, idBook, chapter, idVerse));
    }

    public List<Condition> getConditions() {
        return new ArrayList<>(conditions);
    }
}

