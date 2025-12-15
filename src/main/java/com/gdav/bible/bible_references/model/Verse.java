package com.gdav.bible.bible_references.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Verse {
    private int verseNumber;
    private String text;
    private List<Keyword> keywords;
}
