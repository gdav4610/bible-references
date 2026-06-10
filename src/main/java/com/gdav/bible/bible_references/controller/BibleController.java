package com.gdav.bible.bible_references.controller;

import com.gdav.bible.bible_references.service.BibleService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/bible")
public class BibleController {

    private final BibleService bibleService;

    public BibleController(BibleService bibleService) {
        this.bibleService = bibleService;
    }

    @GetMapping("/chapter/{idBook}/{chapter}")
    public Map<String, Object> getChapter(@PathVariable int idBook, @PathVariable int chapter,
                                          @RequestParam(required = false) Integer idVerse,
                                          @RequestParam(name = "includeLXX", required = false) Boolean includeLxx) {
        return bibleService.getChapter(idBook, chapter, idVerse, includeLxx);
    }

    @GetMapping(value = "/search")
    public Object getStrongDetail(
                                  @RequestParam(name = "q", required = false) String q,
                                  @RequestParam(name = "includeLXX", required = false) Boolean includeLxx) {
        return bibleService.search(q, includeLxx);
    }

}
