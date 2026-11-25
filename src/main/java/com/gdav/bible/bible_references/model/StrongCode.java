package com.gdav.bible.bible_references.model;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Código Strong, simple ({@code H430}) o compuesto de dos ({@code H853 H430}).
 *
 * <p>Antes era un {@code String} que {@code StrongService} pasaba a mayúsculas, comprobaba con
 * {@code contains(" ")} y partía con {@code split(" ")} en varios sitios. Con un espacio al
 * final ({@code "H430 "}) se tomaba por compuesto y {@code split(" ")[1]} lanzaba
 * {@code ArrayIndexOutOfBoundsException}: un 500 al alcance de cualquier escáner.
 *
 * <p>Cada mitad se limita a letras y dígitos. Además de ser el formato real, impide que
 * {@code %} o {@code _} lleguen a {@code CompoundWordRepository.findLikeIdWord}, donde son
 * comodines del {@code LIKE}.
 *
 * @param first  primer código, siempre presente
 * @param second segundo código, {@code null} si el código es simple
 */
public record StrongCode(String first, String second) {

    private static final Pattern PART = Pattern.compile("[A-Z0-9]+");

    public StrongCode {
        if (first == null || !PART.matcher(first).matches()) {
            throw new IllegalArgumentException("Código Strong mal formado");
        }
        if (second != null && !PART.matcher(second).matches()) {
            throw new IllegalArgumentException("Código Strong mal formado");
        }
    }

    /**
     * Normaliza —recorta, pasa a mayúsculas y colapsa los espacios— y valida.
     *
     * @throws IllegalArgumentException si es nulo, vacío, tiene más de dos partes o alguna
     *         contiene algo que no sea letra o dígito
     */
    public static StrongCode of(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Código Strong mal formado");
        }
        String[] parts = raw.trim().toUpperCase(Locale.ROOT).split("\\s+");
        if (parts.length > 2) {
            throw new IllegalArgumentException("Código Strong mal formado");
        }
        return new StrongCode(parts[0], parts.length == 2 ? parts[1] : null);
    }

    public boolean isCompound() {
        return second != null;
    }

    /** El compuesto con las mitades intercambiadas; un código simple se devuelve tal cual. */
    public StrongCode reversed() {
        return isCompound() ? new StrongCode(second, first) : this;
    }

    /** La forma canónica, la que se guarda en {@code id_word}. */
    public String value() {
        return isCompound() ? first + " " + second : first;
    }

    @Override
    public String toString() {
        return value();
    }
}
