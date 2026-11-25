package com.gdav.bible.bible_references.model;

/** Apariciones de una keyword en un libro concreto. */
public record KeywordBookStats(
        Integer idBook,
        String bookName,
        Integer count
) {}
