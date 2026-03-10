package com.gdav.bible.bible_references.config;

import org.springframework.stereotype.Component;

import java.util.*;

@Component("wordCacheCondition")
public class WordCacheCondition {

    public static class Condition {
        private final String idWord;
        private final Set<String> sources;

        public Condition(String idWord, List<String> sources) {
            this.idWord = idWord;
            this.sources = sources == null ? new HashSet<>() : new HashSet<>(sources);
        }

        public boolean matches(String idWord, List<String> sources) {
            if (!Objects.equals(this.idWord, idWord)) return false;
            if (sources == null) return false;
            // comparar como Sets para mejor rendimiento y evitar containsAll repetido
            return this.sources.equals(new HashSet<>(sources));
        }
    }


    public static class ConditionWithJustOneParam {
        private final String idWord;

        public ConditionWithJustOneParam(String idWord) {
            this.idWord = idWord;
        }

        public boolean matches(String idWord) {
            return Objects.equals(this.idWord, idWord);
        }
    }

    private final List<Condition> conditions = new ArrayList<>();

    private final List<ConditionWithJustOneParam> conditionsWitJustOneParam = new ArrayList<>();

    public WordCacheCondition() {
//        conditions.add(new Condition("G5547", Arrays.asList("HEBREW AT", "TR")));
//        conditions.add(new Condition("G5547", Arrays.asList("HEBREW AT", "TR", "LXX")));
//        conditions.add(new Condition("G4151 G2316", Arrays.asList("HEBREW AT", "TR")));
//        conditions.add(new Condition("G4151 G2316", Arrays.asList("HEBREW AT", "TR", "LXX")));
        // aqui agregar condiciones en hebreo, y de source words, y compound words
        conditionsWitJustOneParam.add(new ConditionWithJustOneParam("H430"));
        conditionsWitJustOneParam.add(new ConditionWithJustOneParam("G5547"));
        conditionsWitJustOneParam.add(new ConditionWithJustOneParam("G4151 G2316"));
    }

    public boolean shouldCache(String idWord, List<String> sources) {
        if (idWord == null) return false;
        for (Condition c : conditions) {
            if (c.matches(idWord, sources)) return true;
        }
        return false;
    }

    public boolean shouldCacheWithJustOneParam(String idWord) {
        if (idWord == null) return false;
        for (ConditionWithJustOneParam c : conditionsWitJustOneParam) {
            if (c.matches(idWord)) return true;
        }
        return false;
    }
    // utilidades para gestión dinámica de condiciones
    public void addCondition(String idWord, List<String> sources) {
        conditions.add(new Condition(idWord, sources));
    }

    public List<Condition> getConditions() {
        return new ArrayList<>(conditions);
    }

    // utilidades para la variante con un solo parámetro
    public void addConditionWithJustOneParam(String idWord) {
        conditionsWitJustOneParam.add(new ConditionWithJustOneParam(idWord));
    }

    public List<ConditionWithJustOneParam> getConditionsWithJustOneParam() {
        return new ArrayList<>(conditionsWitJustOneParam);
    }
}
