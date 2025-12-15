package com.gdav.bible.bible_references.repository.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;



@Entity
@Table(name = "bible_source_words_catalog")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SourceWordEntity {

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

    // Relación one-to-many hacia KeywordEntity (un registro de SourceWord puede corresponder a muchas palabras clave)
    @OneToMany(mappedBy = "sourceWordEntity", fetch = FetchType.LAZY)
    private List<KeywordEntity> keywords;
}
