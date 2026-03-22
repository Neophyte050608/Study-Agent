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
     * 核心 GraphRAG 查询：查找指定技术名词的 1 度到 2 度关联邻居节点。
     * 这在面试时极其有用：当候选人提到 $conceptName 时，查出与其相关的底层技术或延伸概念，
     * 用于生成深度连环追问。
     *
     * @param conceptName 中心技术名词，如 "HashMap"
     * @return 返回相关联的技术名词列表（例如可能返回 "红黑树", "ConcurrentHashMap", "CAS"）
     */
    @Query("MATCH (n:TechConcept {name: $conceptName})-[*1..2]-(m:TechConcept) " +
           "WHERE n <> m " +
           "RETURN DISTINCT m.name LIMIT 10")
    List<String> findRelatedConceptsWithinTwoHops(@Param("conceptName") String conceptName);
}