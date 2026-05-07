package com.zoujuexian.aiagentdemo.service.tool.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.zoujuexian.aiagentdemo.service.tool.InnerTool;
import com.zoujuexian.aiagentdemo.service.tool.ToolCallbackBuilder;
import com.zoujuexian.aiagentdemo.service.tool.ToolDomain;
import com.zoujuexian.aiagentdemo.service.rag.RagService;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 知识库检索工具
 * <p>
 * 将 RAG 检索能力封装为 Function Calling 工具，
 * 当用户询问知识库相关问题时，Agent 会自动调用此工具。
 */
@Component
public class RagTool implements InnerTool {

    private final RagService ragService;

    public RagTool(RagService ragService) {
        this.ragService = ragService;
    }

    @Override
    public List<ToolCallback> loadToolCallbacks() {
        Map<String, Map<String, String>> properties = Map.of(
                "question", Map.of("type", "string", "description", "需要从知识库中检索的问题")
        );

        return Collections.singletonList(ToolCallbackBuilder.build(
                "knowledge_search",
                "从知识库中检索与问题相关的信息。当用户询问 Java、Spring Boot、Maven 等技术知识时，应使用此工具检索知识库",
                properties,
                List.of("question"),
                this::execute
        ));
    }

    @Override
    public ToolDomain getDomain() {
        return ToolDomain.RAG;
    }

    private String execute(String argumentsJson) {
        JSONObject args = JSON.parseObject(argumentsJson);
        String question = args.getString("question");
        return ragService.query(question);
    }
}
