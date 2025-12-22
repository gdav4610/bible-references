package com.gdav.bible.bible_references.repository.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;


@Entity
@Table(name = "bible_compound_words_catalog")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CompoundWordEntity {

    @Id
    @Column(name = "id_word", nullable = false)
    private String idWord;

    @Column(name = "transliteration")
    private String transliteration;

    @Column(name = "inflection")
    private String inflection;

    @Column(name = "meaning")
    private String meaning;

    // Nuevo campo idParent que referencia a un posible padre en el catálogo
    @Column(name = "id_parent")
    private String idParent;

    // Nuevo campo idParentSec que referencia a un posible padre en el catálogo
    @Column(name = "id_parent_sec")
    private String idParentSec;

    // Nuevo campo parentMeaning que almacena el significado del padre
    @Column(name = "parent_meaning")
    private String parentMeaning;

    // Nuevo campo parentSecMeaning que almacena el significado del padre alterno
    @Column(name = "parent_sec_meaning")
    private String parentSecMeaning;

    // Nuevo campo first_app_book que almacena la primera aparición de la palabra
    @Column(name = "first_app_book")
    private Integer firstAppBook;

    // Nuevo campo first_app_chapter que almacena la primera aparición de la palabra
    @Column(name = "first_app_chapter")
    private Integer firstAppChapter;

    // Nuevo campo first_app_verse que almacena la primera aparición de la palabra
    @Column(name = "first_app_verse")
    private Integer firstAppVerse;


    // Relación one-to-many hacia KeywordEntity (un registro de CompoundWord puede corresponder a muchas palabras clave)
    @OneToMany(fetch = FetchType.LAZY)
    @JoinColumn(name = "strong_number", referencedColumnName = "id_word", insertable = false, updatable = false)
    private List<KeywordEntity> keywords;

}
