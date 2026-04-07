package com.gdav.bible.bible_references.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RelatedCompoundWord {
    private String idWord;
    private String transliteratedWord;
    private String meaning;
}
