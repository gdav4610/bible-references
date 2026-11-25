package com.gdav.bible.bible_references.model;

import java.util.Map;

/**
 * Catálogo de nombres de libro por id, del 1 (Génesis) al 66 (Apocalipsis).
 *
 * <p>Estaba como constante privada de {@code BibleService}. Deja de estarlo porque hay un
 * segundo consumidor —{@code StrongService}, para el desglose por libro de
 * {@code /api/strongs/{code}/stats}—, y la alternativa era duplicar las 66 entradas o que un
 * servicio dependiera del otro.
 *
 * <p>Sigue siendo un {@code static final} que se construye al cargar la clase, que es lo que
 * interesa con SnapStart: ese coste queda dentro del snapshot y no se paga por petición.
 * Vive en {@code model} y no lleva anotaciones de Spring ni de JPA, así que se prueba sin
 * levantar el contexto.
 */
public final class BibleBooks {

    private BibleBooks() {}

    /** Nombre del libro, o {@code "Desconocido"} si el id no está en el canon de 66. */
    public static String nameOf(int idBook) {
        return BOOK_NAMES.getOrDefault(idBook, "Desconocido");
    }

    private static final Map<Integer, String> BOOK_NAMES = Map.ofEntries(
            Map.entry(1, "Génesis"), Map.entry(2, "Éxodo"), Map.entry(3, "Levítico"),
            Map.entry(4, "Números"), Map.entry(5, "Deuteronomio"), Map.entry(6, "Josué"),
            Map.entry(7, "Jueces"), Map.entry(8, "Rut"), Map.entry(9, "1 Samuel"),
            Map.entry(10, "2 Samuel"), Map.entry(11, "1 Reyes"), Map.entry(12, "2 Reyes"),
            Map.entry(13, "1 Crónicas"), Map.entry(14, "2 Crónicas"), Map.entry(15, "Esdras"),
            Map.entry(16, "Nehemías"), Map.entry(17, "Ester"), Map.entry(18, "Job"),
            Map.entry(19, "Salmos"), Map.entry(20, "Proverbios"), Map.entry(21, "Eclesiastés"),
            Map.entry(22, "Cantares"), Map.entry(23, "Isaías"), Map.entry(24, "Jeremías"),
            Map.entry(25, "Lamentaciones"), Map.entry(26, "Ezequiel"), Map.entry(27, "Daniel"),
            Map.entry(28, "Oseas"), Map.entry(29, "Joel"), Map.entry(30, "Amós"),
            Map.entry(31, "Abdías"), Map.entry(32, "Jonás"), Map.entry(33, "Miqueas"),
            Map.entry(34, "Nahum"), Map.entry(35, "Habacuc"), Map.entry(36, "Sofonías"),
            Map.entry(37, "Hageo"), Map.entry(38, "Zacarías"), Map.entry(39, "Malaquías"),
            Map.entry(40, "Mateo"), Map.entry(41, "Marcos"), Map.entry(42, "Lucas"),
            Map.entry(43, "Juan"), Map.entry(44, "Hechos"), Map.entry(45, "Romanos"),
            Map.entry(46, "1 Corintios"), Map.entry(47, "2 Corintios"), Map.entry(48, "Gálatas"),
            Map.entry(49, "Efesios"), Map.entry(50, "Filipenses"), Map.entry(51, "Colosenses"),
            Map.entry(52, "1 Tesalonicenses"), Map.entry(53, "2 Tesalonicenses"),
            Map.entry(54, "1 Timoteo"), Map.entry(55, "2 Timoteo"), Map.entry(56, "Tito"),
            Map.entry(57, "Filemón"), Map.entry(58, "Hebreos"), Map.entry(59, "Santiago"),
            Map.entry(60, "1 Pedro"), Map.entry(61, "2 Pedro"), Map.entry(62, "1 Juan"),
            Map.entry(63, "2 Juan"), Map.entry(64, "3 Juan"), Map.entry(65, "Judas"),
            Map.entry(66, "Apocalipsis"));
}
