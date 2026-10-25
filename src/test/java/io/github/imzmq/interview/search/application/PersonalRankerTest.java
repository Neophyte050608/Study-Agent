package io.github.imzmq.interview.search.application;

import io.github.imzmq.interview.learning.application.contract.UserKnowledgeStateQuery;
import io.github.imzmq.interview.learning.application.contract.result.UserKnowledgeStateView;
import io.github.imzmq.interview.search.application.dto.AutocompleteItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersonalRankerTest {

    @Test
    void boostsWeakTopicsWithoutMutatingSourceItems() {
        UserKnowledgeStateQuery query = mock(UserKnowledgeStateQuery.class);
        when(query.findByUserId("user-1")).thenReturn(List.of(state("JAVA", 0.1d)));
        PersonalRanker ranker = new PersonalRanker(query);
        AutocompleteItem java = item(1L, "Java concurrency", "backend java", 10.0d);
        AutocompleteItem spring = item(2L, "Spring Boot", "spring", 10.0d);

        List<AutocompleteItem> ranked = ranker.rerank(List.of(spring, java), "user-1", "", 2);

        assertThat(ranked).extracting(AutocompleteItem::getId).containsExactly(1L, 2L);
        assertThat(ranked).extracting(AutocompleteItem::getScore).containsExactly(0.67d, 0.4d);
        assertThat(java.getScore()).isEqualTo(10.0d);
        assertThat(spring.getScore()).isEqualTo(10.0d);
        verify(query).findByUserId("user-1");
    }

    @Test
    void treatsNullMasteryAsZeroAndKeepsLastDuplicateTopicValue() {
        UserKnowledgeStateQuery query = mock(UserKnowledgeStateQuery.class);
        when(query.findByUserId("user-1")).thenReturn(List.of(
                state("java", 0.9d),
                state("JAVA", null)
        ));
        PersonalRanker ranker = new PersonalRanker(query);

        List<AutocompleteItem> ranked = ranker.rerank(List.of(
                item(1L, "Java", "backend", 5.0d),
                item(2L, "Spring", "backend", 5.0d)
        ), "user-1", "", 2);

        assertThat(ranked).extracting(AutocompleteItem::getId).containsExactly(1L, 2L);
        assertThat(ranked.get(0).getScore()).isEqualTo(0.7d);
    }

    @Test
    void trimsPrefixScoresShorterPhrasesHigherAndAppliesLimit() {
        UserKnowledgeStateQuery query = mock(UserKnowledgeStateQuery.class);
        when(query.findByUserId("user-1")).thenReturn(List.of());
        PersonalRanker ranker = new PersonalRanker(query);

        List<AutocompleteItem> ranked = ranker.rerank(List.of(
                item(1L, "Beta", "category", 2.0d),
                item(2L, "Alpha", "category", 2.0d),
                item(3L, "Gamma", "category", 2.0d)
        ), "user-1", "  Al  ", 2);

        assertThat(ranked).extracting(AutocompleteItem::getId).containsExactly(1L, 2L);
        assertThat(ranked).allSatisfy(item -> assertThat(item.getScore()).isBetween(0.4d, 0.7d));
    }

    @Test
    void skipsQueryForBlankUserAndHandlesEmptyInputs() {
        UserKnowledgeStateQuery query = mock(UserKnowledgeStateQuery.class);
        PersonalRanker ranker = new PersonalRanker(query);

        List<AutocompleteItem> ranked = ranker.rerank(
                List.of(item(1L, "Java", "backend", 0.0d)), "  ", null, 1);

        assertThat(ranked).singleElement().satisfies(item -> assertThat(item.getScore()).isZero());
        assertThat(ranker.rerank(null, "user-1", "", 1)).isEmpty();
        assertThat(ranker.rerank(List.of(), "user-1", "", 1)).isEmpty();
        assertThat(ranker.rerank(List.of(item(1L, "Java", "backend", 1.0d)), "user-1", "", 0)).isEmpty();
        verify(query, never()).findByUserId("user-1");
        verify(query, never()).findByUserId("  ");
    }

    private static UserKnowledgeStateView state(String topic, Double masteryScore) {
        return new UserKnowledgeStateView(topic, masteryScore);
    }

    private static AutocompleteItem item(Long id, String phrase, String category, double score) {
        AutocompleteItem item = new AutocompleteItem();
        item.setId(id);
        item.setPhrase(phrase);
        item.setCategory(category);
        item.setScore(score);
        return item;
    }
}
