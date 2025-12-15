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


    // 🔹 Frases clave (OneToMany)
//    @OneToMany
//    @JoinColumns({
//            @JoinColumn(name = "id_book", referencedColumnName = "id_book", nullable=true, updatable=false,insertable=false),
//            @JoinColumn(name = "chapter", referencedColumnName = "chapter", nullable=true, updatable=false,insertable=false),
//            @JoinColumn(name = "verse", referencedColumnName = "verse", nullable=true, updatable=false,insertable=false)
//    })
//    private List<PhraseEntity> phrases;

    // --- Getters y Setters ---
//
//
//    public int getIdVerse() {
//        return idVerse;
//    }
//
//    public void setIdVerse(int idVerse) {
//        this.idVerse = idVerse;
//    }
//
//    public int getIdBible() {
//        return idBible;
//    }
//
//    public void setIdBible(int idBible) {
//        this.idBible = idBible;
//    }
//
//    public int getIdBook() {
//        return idBook;
//    }
//
//    public void setIdBook(int idBook) {
//        this.idBook = idBook;
//    }
//
//    public int getChapter() {
//        return chapter;
//    }
//
//    public void setChapter(int chapter) {
//        this.chapter = chapter;
//    }
//
//    public int getVerse() {
//        return verse;
//    }
//
//    public void setVerse(int verse) {
//        this.verse = verse;
//    }
//
//    public String getText() {
//        return text;
//    }
//
//    public void setText(String text) {
//        this.text = text;
//    }
//
//    public List<KeywordEntity> getKeywords() {
//        return keywords;
//    }
//
//    public void setKeywords(List<KeywordEntity> keywords) {
//        this.keywords = keywords;
//        if (keywords != null) {
//            keywords.forEach(k -> k.setVerseEntity(this));
//        }
//    }
//
//    public List<PhraseEntity> getPhrases() {
//        return phrases;
//    }
//
//    public void setPhrases(List<PhraseEntity> phrases) {
//        this.phrases = phrases;
//        if (phrases != null) {
//            phrases.forEach(p -> p.setVerseEntity(this));
//        }
//    }
}
