package com.gdav.bible.bible_references.repository.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "bible_word_references")
@Getter
@Setter
@RequiredArgsConstructor
public class KeywordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name="id_word_reference",updatable=false,nullable=false)
    private Integer idWordReference;

    @Column(name = "id_bible")
    private Integer idBible; // nuevo campo para poder mapear la FK hacia VerseEntity

    @Column(name = "id_book")
    private Integer idBook;

    @Column(name = "chapter")
    private Integer chapter;

    @Column(name = "verse")
    private Integer verse;

    @Column(name = "inflection_word")
    private String inflectionWord;

    @Column(name = "translated_word")
    private String translatedWord;

    @Column(name = "transliterated_word")
    private String transliteratedWord;

    @Column(name = "strong_number")
    private String strongNumber; // 🔹 Nuevo campo

    // Nuevo campo mapeado a la columna appearance_in_verse
    @Column(name = "appearance_in_verse")
    private Integer appearanceInVerse;

    @Column(name = "source")
    private String source; // 🔹 Nuevo campo

    // Relación inversa con el versículo
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
            @JoinColumn(name = "id_bible", referencedColumnName = "id_bible", nullable=true, updatable=false, insertable=false),
            @JoinColumn(name = "id_book", referencedColumnName = "id_book", nullable=true, updatable=false, insertable=false),
            @JoinColumn(name = "chapter", referencedColumnName = "chapter", nullable=true, updatable=false, insertable=false),
            @JoinColumn(name = "verse", referencedColumnName = "verse", nullable=true, updatable=false, insertable=false)
    })
    private VerseEntity verseEntity;

    // Relación many-to-one hacia la tabla bible_source_words_catalog
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "strong_number", referencedColumnName = "id_word", nullable = true, updatable = false, insertable = false)
    private SourceWordEntity sourceWordEntity;

    // Relación many-to-one hacia la tabla bible_compound_words_catalog
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "strong_number", referencedColumnName = "id_word", nullable = true, updatable = false, insertable = false)
    private CompoundWordEntity compoundWordEntity;

    
}
