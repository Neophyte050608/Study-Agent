package com.example.interview.graph;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Neo4j 图数据库的访问层接口。
 * 提供节点保存以及复杂的 Cypher 图查询能力。
 */
@Repository
public interface TechConceptRepository extends Neo4jRepository<TechConcept, String> {

    /**
     * 核心 GraphRAG 查询：查找指定技术名词在 1 到 2 跳范围内的关联概念摘要。
     *
     * <p>相比只返回概念名称，这里额外返回 description 与 type，
     * 让上层检索链路可以直接拼出更像证据片段的文本。</p>
     *
     * @param conceptName 中心技术名词，如 "HashMap"
     * @return 关联概念的轻量摘要视图
     */
    @Query("MATCH (n:TechConcept {name: $conceptName})-[*1..2]-(m:TechConcept) " +
           "WHERE n <> m " +
           "RETURN DISTINCT m.name AS name, m.description AS description, m.type AS type LIMIT 10")
    List<TechConceptSnippetView> findRelatedConceptSnippetsWithinTwoHops(@Param("conceptName") String conceptName);
}
