package io.github.imzmq.interview.integration.search.application;

import io.github.imzmq.interview.integration.search.application.dto.AutocompleteItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RadixNode {
    String fragment = "";
    Map<Character, RadixNode> children = new HashMap<>();
    List<AutocompleteItem> topK = new ArrayList<>();
    boolean isEndOfWord = false;
    AutocompleteItem entry;
    RadixNode parent;
}
