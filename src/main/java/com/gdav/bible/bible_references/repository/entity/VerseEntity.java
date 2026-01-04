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

    @EmbeddedId
    private VerseId id;

    @Column(name = "text", columnDefinition = "TEXT")
    private String text;

    // Relación con palabras clave
    @OneToMany
    @JoinColumns({
            @JoinColumn(name = "id_bible", referencedColumnName = "id_bible", nullable=true, updatable=false, insertable=false),
            @JoinColumn(name = "id_book", referencedColumnName = "id_book", nullable=true, updatable=false,insertable=false),
            @JoinColumn(name = "chapter", referencedColumnName = "chapter", nullable=true, updatable=false,insertable=false),
            @JoinColumn(name = "verse", referencedColumnName = "verse", nullable=true, updatable=false,insertable=false)
    })
    private List<KeywordEntity> keywords;

    // Métodos de conveniencia para mantener compatibilidad con código existente
    public Integer getIdBible() {
        return id == null ? null : id.getIdBible();
    }

    public Integer getIdBook() {
        return id == null ? null : id.getIdBook();
    }

    public Integer getChapter() {
        return id == null ? null : id.getChapter();
    }

    public Integer getVerse() {
        return id == null ? null : id.getVerse();
    }

}
