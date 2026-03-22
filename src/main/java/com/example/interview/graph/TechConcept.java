package com.example.interview.graph;

import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.HashSet;
import java.util.Set;

/**
 * 知识图谱中的技术概念节点实体。
 * 代表从 Obsidian 笔记中提取出的每一个技术名词（如 "Redis", "HashMap", "AOP"）。
 */
@Node("TechConcept")
public class TechConcept {

    /**
     * 以技术名词本身作为唯一主键（例如 "Redis"）。
     * 在 Obsidian 中，双向链接通常是 [[名词]]，直接将其作为主键最合适。
     */
    @Id
    private String name;

    /** 节点类型：如 "Algorithm", "Framework", "Database", "Concept" */
    @Property("type")
    private String type;

    /** 从笔记中提取的简短描述或核心摘要 */
    @Property("description")
    private String description;

    /** 
     * 与其他技术概念的关联关系（RELATED_TO）。
     * 这直接对应于 Obsidian 笔记中的 [[双向链接]]。
     * 例如，当前节点是 "HashMap"，它内部有个链接 [[红黑树]]，就会形成一条指向 "红黑树" 的边。
     */
    @Relationship(type = "RELATED_TO", direction = Relationship.Direction.OUTGOING)
    private Set<TechConcept> relatedConcepts = new HashSet<>();

    public TechConcept() {}

    public TechConcept(String name) {
        this.name = name;
        this.type = "Concept"; // 默认类型
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Set<TechConcept> getRelatedConcepts() {
        return relatedConcepts;
    }

    public void addRelatedConcept(TechConcept concept) {
        if (concept != null) {
            this.relatedConcepts.add(concept);
        }
    }
}