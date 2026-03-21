package com.example.interview.config;

import com.example.interview.tool.VectorSearchTool;
import com.example.interview.tool.WebSearchTool;
import org.springframework.ai.document.Document;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.List;
import java.util.function.Function;

/**
 * Agent 自动工具调用 (Function Calling) 注册配置。
 * 
 * 职责：
 * 将原本由 Java 代码硬编码调用的 Tool 接口，包装为 Spring AI 标准的 Function Bean。
 * 这样底层的大语言模型（如 GPT-4o, GLM-4）就能在上下文中感知到这些工具，
 * 并在需要时（如知识库无法回答最新技术时）自主触发调用。
 */
@Configuration
public class AgentFunctionConfig {

    /**
     * 注册互联网搜索工具。
     * @Description 的内容非常重要，它就是大模型判断“什么时候该用这个工具”的唯一依据（即 Prompt）。
     */
    @Bean
    @Description("当用户的问题涉及到最新的技术动态，或者在上下文中找不到足够的信息时，使用此工具进行互联网搜索以获取最新知识。")
    public Function<WebSearchTool.Query, List<String>> webSearchFunction(WebSearchTool webSearchTool) {
        return webSearchTool::run;
    }

    /**
     * 注册本地知识库向量搜索工具。
     */
    @Bean
    @Description("当需要从本地知识库中查找面试题、架构经验或技术文档时，使用此工具进行向量语义搜索。")
    public Function<VectorSearchTool.Query, List<Document>> vectorSearchFunction(VectorSearchTool vectorSearchTool) {
        return vectorSearchTool::run;
    }
}