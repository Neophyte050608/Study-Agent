package io.github.imzmq.interview.knowledge.infrastructure.graph;

import org.junit.jupiter.api.Test;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TechConceptPersistenceContractTest {

    private static final String SINGLE_QUERY =
            "MATCH (n:TechConcept {name: $conceptName})-[*1..2]-(m:TechConcept) " +
            "WHERE n <> m " +
            "RETURN DISTINCT m.name AS name, m.description AS description, m.type AS type LIMIT 10";

    private static final String BATCH_QUERY =
            "UNWIND $conceptNames AS conceptName " +
            "MATCH (n:TechConcept {name: conceptName}) " +
            "CALL { " +
            "  WITH n " +
            "  MATCH (n)-[*1..2]-(m:TechConcept) " +
            "  WHERE n <> m " +
            "  RETURN DISTINCT m.name AS name, m.description AS description, m.type AS type " +
            "  LIMIT 10 " +
            "} " +
            "RETURN n.name AS anchor, name, description, type";

    @Test
    void shouldPreserveNeo4jNodeAndRelationshipBehavior() {
        Node node = TechConcept.class.getAnnotation(Node.class);
        TechConcept redis = new TechConcept("Redis");
        TechConcept cache = new TechConcept("Cache");

        redis.addRelatedConcept(null);
        redis.addRelatedConcept(cache);
        redis.addRelatedConcept(cache);

        assertThat(node).isNotNull();
        assertThat(node.value()).containsExactly("TechConcept");
        assertThat(redis.getName()).isEqualTo("Redis");
        assertThat(redis.getType()).isEqualTo("Concept");
        assertThat(redis.getRelatedConcepts()).containsExactly(cache);
    }

    @Test
    void shouldPreserveProjectionAsConceptNameCharSequence() {
        TechConceptSnippetView view = snippet("Redis", "缓存数据库", "Database");

        assertThat(view.length()).isEqualTo(5);
        assertThat(view.charAt(1)).isEqualTo('e');
        assertThat(view.subSequence(1, 4).toString()).isEqualTo("edi");
        assertThat(snippet(null, null, null).length()).isZero();
        assertThat(BatchedConceptSnippetView.class.getInterfaces())
                .containsExactly(TechConceptSnippetView.class);
    }

    @Test
    void shouldPreserveRepositorySignaturesAndCypherQueries() throws NoSuchMethodException {
        Method single = TechConceptRepository.class.getMethod(
                "findRelatedConceptSnippetsWithinTwoHops", String.class);
        Method batch = TechConceptRepository.class.getMethod(
                "findRelatedConceptSnippetsBatch", List.class);

        assertThat(Neo4jRepository.class).isAssignableFrom(TechConceptRepository.class);
        assertThat(single.getGenericReturnType().getTypeName())
                .isEqualTo("java.util.List<io.github.imzmq.interview.knowledge.infrastructure.graph.TechConceptSnippetView>");
        assertThat(batch.getGenericReturnType().getTypeName())
                .isEqualTo("java.util.List<io.github.imzmq.interview.knowledge.infrastructure.graph.BatchedConceptSnippetView>");
        assertThat(single.getAnnotation(Query.class).value()).isEqualTo(SINGLE_QUERY);
        assertThat(batch.getAnnotation(Query.class).value()).isEqualTo(BATCH_QUERY);
        assertThat(single.getParameters()[0].getAnnotation(Param.class).value()).isEqualTo("conceptName");
        assertThat(batch.getParameters()[0].getAnnotation(Param.class).value()).isEqualTo("conceptNames");
    }

    private static TechConceptSnippetView snippet(String name, String description, String type) {
        return new TechConceptSnippetView() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return description;
            }

            @Override
            public String getType() {
                return type;
            }
        };
    }
}
