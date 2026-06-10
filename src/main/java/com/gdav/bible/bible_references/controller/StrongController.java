package com.gdav.bible.bible_references.controller;

import com.gdav.bible.bible_references.model.SourceWordResponse;
import com.gdav.bible.bible_references.model.SourceWordWithKeywordStatsResponse;
import com.gdav.bible.bible_references.service.StrongService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/strongs")
public class StrongController {

    private final StrongService strongService;

    public StrongController(StrongService strongService) {
        this.strongService = strongService;
    }

    @GetMapping("/{strongCode}/stats")
    public ResponseEntity<SourceWordWithKeywordStatsResponse> getStrongKeywordsGrouped(@PathVariable String strongCode,
                                                                                      @RequestParam(name = "includeLXX", required = false) Boolean includeLXX) {
        SourceWordWithKeywordStatsResponse response = strongService.getStrongKeywordsGrouped(strongCode, includeLXX);
        return ResponseEntity.ok(response);
    }

    @GetMapping(value = "/{strongCode}/details")
    public ResponseEntity<SourceWordResponse> getStrongDetail(@PathVariable String strongCode,
                                                              @RequestParam(name = "transliteratedWord", required = false) String transliteratedWord,
                                                              @RequestParam(name = "translatedWord", required = false) String translatedWord,
                                                              @RequestParam(name = "includeLXX", required = false) Boolean includeLXX) {
        SourceWordResponse response = strongService.getStrongDetail(strongCode, transliteratedWord, translatedWord, includeLXX);
        return ResponseEntity.ok(response);
    }

}
