package io.github.imzmq.interview.knowledge.infrastructure.graph;

import io.github.imzmq.interview.knowledge.application.contract.command.KnowledgeGraphIndexCommand.KnowledgeGraphSourceDocument;
import io.github.imzmq.interview.knowledge.application.indexing.KnowledgeGraphIndexService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeGraphIndexServiceTest {

    @Test
    void shouldPersistRelatedConceptsBeforeTheSourceConcept() {
        TechConceptRepository repository = mock(TechConceptRepository.class);
        when(repository.findById("Redis")).thenReturn(Optional.empty());
        when(repository.findById("Cache")).thenReturn(Optional.empty());
        when(repository.findById("Database")).thenReturn(Optional.empty());
        KnowledgeGraphIndexService service = new KnowledgeGraphIndexService(repository);
        String content = "x".repeat(201);

        service.index(new KnowledgeGraphSourceDocument(
                "/vault/Redis.md", content, " Cache , Redis, , Database "));

        ArgumentCaptor<TechConcept> captor = ArgumentCaptor.forClass(TechConcept.class);
        verify(repository, times(3)).save(captor.capture());
        List<TechConcept> saved = captor.getAllValues();
        assertThat(saved).extracting(TechConcept::getName)
                .containsExactly("Cache", "Database", "Redis");
        assertThat(saved.getLast().getDescription()).isEqualTo("x".repeat(200) + "...");
        assertThat(saved.getLast().getRelatedConcepts()).extracting(TechConcept::getName)
                .containsExactlyInAnyOrder("Cache", "Database");
    }

    @Test
    void shouldPreserveExistingDescriptionAndExposeTheWarmupQuery() {
        TechConceptRepository repository = mock(TechConceptRepository.class);
        TechConcept existing = new TechConcept("Redis");
        existing.setDescription("existing");
        when(repository.findById("Redis")).thenReturn(Optional.of(existing));
        KnowledgeGraphIndexService service = new KnowledgeGraphIndexService(repository);

        service.index(new KnowledgeGraphSourceDocument("Redis", "replacement", null));
        service.warmup();

        assertThat(existing.getDescription()).isEqualTo("existing");
        verify(repository).findRelatedConceptSnippetsWithinTwoHops("warmup");
    }
}
