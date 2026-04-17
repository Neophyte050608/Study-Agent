package io.github.imzmq.interview.search.application;

import io.github.imzmq.interview.dto.search.AutocompleteItem;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RadixTreeEngine {

    private static final int DEFAULT_TOP_K_SIZE = 50;

    private final RadixNode root = new RadixNode();
    private int topKSize = DEFAULT_TOP_K_SIZE;

    public void insert(AutocompleteItem item, String phrase) {
        if (item == null || phrase == null || phrase.isBlank()) {
            return;
        }
        RadixNode terminal = insert(root, phrase, item);
        updateTopK(terminal, topKSize);
    }

    public void insertWithoutTopK(AutocompleteItem item, String phrase) {
        if (item == null || phrase == null || phrase.isBlank()) {
            return;
        }
        insert(root, phrase, item);
    }

    public List<AutocompleteItem> search(String prefix, int roughRecallLimit) {
        String normalized = prefix == null ? "" : prefix.trim();
        if (normalized.isEmpty() || roughRecallLimit <= 0) {
            return List.of();
        }

        List<AutocompleteItem> result = new ArrayList<>();
        Set<Long> idSet = new HashSet<>();
        Set<String> wordSet = new HashSet<>();
        String current = normalized;
        while (current.length() >= 2 && result.size() < roughRecallLimit) {
            appendSearchResults(current, roughRecallLimit, result, idSet, wordSet);
            if (result.size() >= roughRecallLimit) {
                break;
            }
            current = current.substring(0, current.length() - 1);
        }
        return result;
    }

    public void updateTopK(RadixNode node, int topKSize) {
        this.topKSize = topKSize > 0 ? topKSize : DEFAULT_TOP_K_SIZE;
        RadixNode current = node;
        while (current != null) {
            current.topK = selectTopK(current, this.topKSize);
            current = current.parent;
        }
    }

    public void rebuildTopK(int topKSize) {
        this.topKSize = topKSize > 0 ? topKSize : DEFAULT_TOP_K_SIZE;
        rebuildTopK(root, this.topKSize);
    }

    private RadixNode insert(RadixNode parent, String phrase, AutocompleteItem item) {
        if (phrase.isEmpty()) {
            parent.isEndOfWord = true;
            parent.entry = item;
            return parent;
        }

        char firstChar = phrase.charAt(0);
        RadixNode child = parent.children.get(firstChar);
        if (child == null) {
            RadixNode newNode = new RadixNode();
            newNode.fragment = phrase;
            newNode.parent = parent;
            newNode.isEndOfWord = true;
            newNode.entry = item;
            parent.children.put(firstChar, newNode);
            return newNode;
        }

        int commonLength = commonPrefixLength(child.fragment, phrase);
        if (commonLength == child.fragment.length() && commonLength == phrase.length()) {
            child.isEndOfWord = true;
            child.entry = item;
            return child;
        }

        if (commonLength == child.fragment.length()) {
            return insert(child, phrase.substring(commonLength), item);
        }

        RadixNode splitNode = new RadixNode();
        splitNode.fragment = child.fragment.substring(0, commonLength);
        splitNode.parent = parent;
        parent.children.put(splitNode.fragment.charAt(0), splitNode);

        child.fragment = child.fragment.substring(commonLength);
        child.parent = splitNode;
        splitNode.children.put(child.fragment.charAt(0), child);

        if (commonLength == phrase.length()) {
            splitNode.isEndOfWord = true;
            splitNode.entry = item;
            return splitNode;
        }

        RadixNode newLeaf = new RadixNode();
        newLeaf.fragment = phrase.substring(commonLength);
        newLeaf.parent = splitNode;
        newLeaf.isEndOfWord = true;
        newLeaf.entry = item;
        splitNode.children.put(newLeaf.fragment.charAt(0), newLeaf);
        return newLeaf;
    }

    private void appendSearchResults(String prefix,
                                     int limit,
                                     List<AutocompleteItem> result,
                                     Set<Long> idSet,
                                     Set<String> wordSet) {
        List<RadixNode> matchedNodes = matchedNodes(prefix);
        for (int i = matchedNodes.size() - 1; i >= 0 && result.size() < limit; i--) {
            RadixNode node = matchedNodes.get(i);
            if (node == null || node.topK.isEmpty()) {
                continue;
            }
            appendUniqueEntries(result, node.topK, limit, idSet, wordSet);
        }
    }

    private List<RadixNode> matchedNodes(String prefix) {
        List<RadixNode> matchedNodes = new ArrayList<>();
        RadixNode current = root;
        String remaining = prefix;
        while (!remaining.isEmpty()) {
            RadixNode child = current.children.get(remaining.charAt(0));
            if (child == null) {
                break;
            }
            int commonLength = commonPrefixLength(child.fragment, remaining);
            if (commonLength == 0) {
                break;
            }
            if (commonLength < child.fragment.length() && commonLength < remaining.length()) {
                break;
            }
            matchedNodes.add(child);
            if (commonLength >= remaining.length()) {
                break;
            }
            current = child;
            remaining = remaining.substring(commonLength);
        }
        return matchedNodes;
    }

    private void rebuildTopK(RadixNode node, int limit) {
        for (RadixNode child : node.children.values()) {
            rebuildTopK(child, limit);
        }
        node.topK = selectTopK(node, limit);
    }

    private List<AutocompleteItem> selectTopK(RadixNode node, int limit) {
        List<AutocompleteItem> merged = new ArrayList<>();
        if (node.isEndOfWord && node.entry != null) {
            merged.add(node.entry);
        }
        for (RadixNode child : node.children.values()) {
            appendDeduplicated(merged, child.topK);
        }
        merged.sort(Comparator
                .comparingDouble(AutocompleteItem::getScore).reversed()
                .thenComparing(item -> item.getPhrase() == null ? "" : item.getPhrase()));
        if (merged.size() > limit) {
            return new ArrayList<>(merged.subList(0, limit));
        }
        return merged;
    }

    private void appendDeduplicated(List<AutocompleteItem> target, Collection<AutocompleteItem> source) {
        Set<Long> idSet = new HashSet<>();
        Set<String> wordSet = new HashSet<>();
        for (AutocompleteItem item : target) {
            if (item == null) {
                continue;
            }
            if (item.getId() != null) {
                idSet.add(item.getId());
            } else if (item.getPhrase() != null && !item.getPhrase().isEmpty()) {
                wordSet.add(item.getPhrase());
            }
        }
        appendUniqueEntries(target, source, Integer.MAX_VALUE, idSet, wordSet);
    }

    private void appendUniqueEntries(List<AutocompleteItem> target,
                                     Collection<AutocompleteItem> source,
                                     int limit,
                                     Set<Long> idSet,
                                     Set<String> wordSet) {
        for (AutocompleteItem entry : source) {
            if (entry == null || target.size() >= limit) {
                continue;
            }
            Long id = entry.getId();
            if (id != null) {
                if (!idSet.add(id)) {
                    continue;
                }
            } else {
                String word = entry.getPhrase();
                if (word != null && !word.isEmpty() && !wordSet.add(word)) {
                    continue;
                }
            }
            target.add(entry);
        }
    }

    private int commonPrefixLength(String left, String right) {
        int max = Math.min(left.length(), right.length());
        int index = 0;
        while (index < max && left.charAt(index) == right.charAt(index)) {
            index++;
        }
        return index;
    }
}



