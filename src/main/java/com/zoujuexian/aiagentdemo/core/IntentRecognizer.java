package com.zoujuexian.aiagentdemo.core;

import jakarta.annotation.Resource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 意图识别器
 * <p>
 * 在 Agent 处理用户输入之前，先通过 LLM 轻量级分类判断用户意图，
 * 当前支持区分是否需要查询 RAG 知识库。
 * <p>
 * 识别策略：使用 few-shot prompt 让 LLM 输出意图标签（RAG / GENERAL），
 * 后续可扩展更多意图类型。
 */
@Component
public class IntentRecognizer {

    private static final String INTENT_PROMPT_TEMPLATE = """
            你是一个意图分类器。请根据用户的输入和当前知识库的主题范围，判断该问题是否需要从知识库中检索信息来回答。
            
            当前知识库主要包含以下主题内容：{{knowledgeTopics}}
            
            分类规则：
            - 如果用户的问题涉及上述知识库主题范围内的技术知识、概念解释、用法说明、最佳实践等，输出：RAG
            - 如果用户的问题是闲聊、问候、数学计算、天气查询、股票查询、代码编写、翻译等与知识库主题无关的内容，输出：GENERAL
            
            示例：
            用户：Spring Boot 的自动配置原理是什么？ → RAG
            用户：Maven 的依赖冲突怎么解决？ → RAG
            用户：什么是单例模式？ → RAG
            用户：今天天气怎么样？ → GENERAL
            用户：帮我写一个排序算法 → GENERAL
            用户：你好 → GENERAL
            用户：AAPL 的股价是多少？ → GENERAL
            
            请只输出一个单词：RAG 或 GENERAL，不要输出任何其他内容。
            
            用户输入：{{input}}
            """;

    @Resource
    private ChatClient chatClient;

    /**
     * 识别用户输入的意图
     *
     * @param userInput 用户输入文本
     * @param knowledgeTopics 知识库主题描述（动态传入）
     * @return 识别出的意图
     */
    public Intent recognize(String userInput, String knowledgeTopics) {
        try {
            String prompt = INTENT_PROMPT_TEMPLATE
                    .replace("{{knowledgeTopics}}", knowledgeTopics != null ? knowledgeTopics : "通用技术文档")
                    .replace("{{input}}", userInput);
            String result = chatClient.prompt().user(prompt).call().content();

            if (result != null && result.trim().toUpperCase().contains("RAG")) {
                System.out.println("[意图识别] RAG — " + userInput);
                return Intent.RAG;
            }

            System.out.println("[意图识别] GENERAL — " + userInput);
            return Intent.GENERAL;
        } catch (Exception exception) {
            System.err.println("[意图识别] 识别失败，降级为 GENERAL: " + exception.getMessage());
            return Intent.GENERAL;
        }
    }

    /**
     * 识别用户输入的意图（向后兼容方法）
     *
     * @param userInput 用户输入文本
     * @return 识别出的意图
     */
    public Intent recognize(String userInput) {
        return recognize(userInput, "Java、Spring Boot、Maven、设计模式等技术文档");
    }
}
