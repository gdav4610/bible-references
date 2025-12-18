package com.gdav.bible.bible_references.repository.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.util.List;

@Entity
@Table(name = "bible_verses")
@Getter
@Setter
@RequiredArgsConstructor
public class VerseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name="id_verse",updatable=false,nullable=false)
    private Integer idVerse;

    @Column(name = "id_bible")
    private Integer idBible;

    // Número de libro (1 = Génesis, 2 = Éxodo, etc.)
    @Column(name = "id_book")
    private Integer idBook;

    @Column(name = "chapter")
    private Integer chapter;

    @Column(name = "verse")
    private Integer verse;

    @Column(name = "text", columnDefinition = "TEXT")
    private String text;

    // Relación con palabras clave
    @OneToMany
    @JoinColumns({
            @JoinColumn(name = "id_book", referencedColumnName = "id_book", nullable=true, updatable=false,insertable=false),
            @JoinColumn(name = "chapter", referencedColumnName = "chapter", nullable=true, updatable=false,insertable=false),
            @JoinColumn(name = "verse", referencedColumnName = "verse", nullable=true, updatable=false,insertable=false)
    })
    private List<KeywordEntity> keywords;


}
