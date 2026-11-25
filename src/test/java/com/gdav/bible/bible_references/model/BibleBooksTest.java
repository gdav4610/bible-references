package com.gdav.bible.bible_references.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BibleBooksTest {

    @Test
    void nameOf_canon() {
        assertEquals("Génesis", BibleBooks.nameOf(1));
        assertEquals("Deuteronomio", BibleBooks.nameOf(5));
        assertEquals("Apocalipsis", BibleBooks.nameOf(66));
    }

    @Test
    void nameOf_fueraDelCanon() {
        assertEquals("Desconocido", BibleBooks.nameOf(0));
        assertEquals("Desconocido", BibleBooks.nameOf(67));
    }
}
