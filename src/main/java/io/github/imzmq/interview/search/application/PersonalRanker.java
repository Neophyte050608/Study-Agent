package io.github.imzmq.interview.search.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.imzmq.interview.dto.search.AutocompleteItem;
import io.github.imzmq.interview.entity.learning.UserKnowledgeStateDO;
import io.github.imzmq.interview.mapper.learning.UserKnowledgeStateMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
public class PersonalRanker {
    private final UserKnowledgeStateMapper knowledgeStateMapper;

    public PersonalRanker(UserKnowledgeStateMapper knowledgeStateMapper) {
        this.knowledgeStateMapper = knowledgeStateMapper;
    }

    public List<AutocompleteItem> rerank(List<AutocompleteItem> items, String userId, String prefix, int limit) {
        if (items == null || items.isEmpty() || limit <= 0) {
            return List.of();
        }

        List<AutocompleteItem> candidates = items.stream()
                .filter(Objects::nonNull)
                .map(this::copyItem)
                .collect(Collectors.toCollection(ArrayList::new));
        if (candidates.isEmpty()) {
            return List.of();
        }

        Map<String, Double> masteryMap = loadMasteryMap(userId);
        double minHeat = candidates.stream().mapToDouble(AutocompleteItem::getScore).min().orElse(0.0);
        double maxHeat = candidates.stream().mapToDouble(AutocompleteItem::getScore).max().orElse(0.0);
        String normalizedPrefix = prefix == null ? "" : prefix.trim();

        for (AutocompleteItem item : candidates) {
            double heatScore = normalize(item.getScore(), minHeat, maxHeat);
            double matchScore = computeMatchScore(normalizedPrefix, item.getPhrase());
            double weakScore = computeWeakScore(item, masteryMap);
            double finalScore = 0.4 * heatScore + 0.3 * matchScore + 0.3 * weakScore;
            item.setScore(finalScore);
        }

        candidates.sort(Comparator
                .comparingDouble(AutocompleteItem::getScore).reversed()
                .thenComparing(item -> item.getPhrase() == null ? "" : item.getPhrase()));
        if (candidates.size() > limit) {
            return new ArrayList<>(candidates.subList(0, limit));
        }
        return candidates;
    }

    private Map<String, Double> loadMasteryMap(String userId) {
        if (userId == null || userId.isBlank()) {
            return Map.of();
        }
        return knowledgeStateMapper.selectList(new LambdaQueryWrapper<UserKnowledgeStateDO>()
                        .eq(UserKnowledgeStateDO::getUserId, userId))
                .stream()
                .filter(item -> item.getTopic() != null && !item.getTopic().isBlank())
                .collect(Collectors.toMap(
                        item -> item.getTopic().toLowerCase(Locale.ROOT),
                        item -> item.getMasteryScore() == null ? 0.0 : item.getMasteryScore(),
                        (left, right) -> right
                ));
    }

    private AutocompleteItem copyItem(AutocompleteItem source) {
        AutocompleteItem target = new AutocompleteItem();
        target.setId(source.getId());
        target.setPhrase(source.getPhrase());
        target.setCategory(source.getCategory());
        target.setScore(source.getScore());
        return target;
    }

    private double normalize(double value, double min, double max) {
        if (Double.compare(max, min) <= 0) {
            return max <= 0 ? 0.0 : 1.0;
        }
        return (value - min) / (max - min);
    }

    private double computeMatchScore(String prefix, String phrase) {
        if (prefix == null || prefix.isEmpty() || phrase == null || phrase.isEmpty()) {
            return 0.0;
        }
        return Math.min(1.0, (double) prefix.length() / phrase.length());
    }

    private double computeWeakScore(AutocompleteItem item, Map<String, Double> masteryMap) {
        if (masteryMap.isEmpty()) {
            return 0.0;
        }
        List<String> probes = new ArrayList<>();
        if (item.getCategory() != null && !item.getCategory().isBlank()) {
            probes.add(item.getCategory().toLowerCase(Locale.ROOT));
        }
        if (item.getPhrase() != null && !item.getPhrase().isBlank()) {
            probes.add(item.getPhrase().toLowerCase(Locale.ROOT));
        }

        return masteryMap.entrySet().stream()
                .filter(entry -> entry.getValue() < 0.5)
                .filter(entry -> probes.stream().anyMatch(probe -> probe.contains(entry.getKey())))
                .map(Map.Entry::getValue)
                .mapToDouble(score -> 1.0 - score)
                .max()
                .orElse(0.0);
    }
}




