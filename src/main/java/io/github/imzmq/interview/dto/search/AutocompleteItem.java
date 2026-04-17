package io.github.imzmq.interview.dto.search;

import lombok.Data;

@Data
public class AutocompleteItem {
    private Long id;
    private String phrase;
    private String category;
    private double score;
}

