package com.zoujuexian.aiagentdemo.core;

import com.zoujuexian.aiagentdemo.service.tool.InnerTool;
import com.zoujuexian.aiagentdemo.service.tool.ToolDomain;
import com.zoujuexian.aiagentdemo.service.rag.RagService;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI Agent 核心编排器
 * <p>
 * 基于 Spring AI ChatClient 实现，自动支持 ReAct 循环（工具调用）。
 * 职责单一：管理对话记忆 + 工具回调 + 模型调用，不关心工具来源。
 */
@Component
public class AgentCore implements InitializingBean , ApplicationContextAware {

    /** 按 sessionId 隔离的对话记忆，支持多客户端并发 */
    private final Map<String, ChatMemory> sessionMemories = new ConcurrentHashMap<>();
    private String systemPromptText;
    
    /** 按功能域分组的工具回调 */
    private final Map<ToolDomain, List<ToolCallback>> domainTools = new ConcurrentHashMap<>();
    
    /** 所有工具的扁平列表（用于全量注册场景） */
    private final List<ToolCallback> allToolCallbacks = new ArrayList<>();
    
    private ApplicationContext applicationContext;

    @Resource
    private ChatClient chatClient;

    @Resource
    private IntentRecognizer intentRecognizer;

    @Resource
    private RagService ragService;

    @Resource
    private SubAgentManager subAgentManager;

    /** 模型推理参数 */
    private Double temperature = 0.7;
    private Integer maxTokens = 2048;
    private Double topP = 1.0;

    @Override
    public void afterPropertiesSet() throws Exception {
        this.systemPromptText = "你是一个智能助手，具备知识库检索（RAG）、工具调用（Function Calling）、"
                + "技能执行（Skill）、MCP 协议连接和子代理（SubAgent）等能力。\n"
                + "当遇到需要独立上下文记忆的复杂子任务时，你可以创建 SubAgent 来处理。\n"
                + "请根据用户的问题，合理选择使用工具或直接回答。\n"
                + "回答时请简洁准确，必要时引用工具返回的结果。";

        // 初始化 SubAgentManager，共享 ChatClient
        subAgentManager.setChatClient(chatClient);

        // 自动发现所有 InnerTool Bean，按功能域分组加载 ToolCallback
        Collection<InnerTool> innerTools = applicationContext.getBeansOfType(InnerTool.class).values();
        
        for (InnerTool tool : innerTools) {
            try {
                List<ToolCallback> callbacks = tool.loadToolCallbacks();
                ToolDomain domain = tool.getDomain();
                
                // 按域分组存储
                domainTools.computeIfAbsent(domain, k -> new ArrayList<>()).addAll(callbacks);
                
                // 同时加入全量列表
                allToolCallbacks.addAll(callbacks);
                
            } catch (Exception exception) {
                System.err.println("[Tool] " + tool.getClass().getSimpleName() + " 加载失败: " + exception.getMessage());
            }
        }

        System.out.println("\n========================================");
        System.out.println("  AI Agent 已就绪");
        System.out.println("  工具分布:");
        domainTools.forEach((domain, tools) -> 
            System.out.println("    " + domain + ": " + tools.size() + " 个工具"));
        System.out.println("  HTTP API: POST /api/chat");
        System.out.println("  MCP 管理: GET/POST /api/mcp/*");
        System.out.println("========================================\n");
    }

    /**
     * 运行时切换模型
     *
     * @param modelConfig 新的模型配置
     */
    public void switchModel(ModelConfig modelConfig) {
        OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
                .baseUrl(modelConfig.getBaseUrl())
                .apiKey(modelConfig.getApiKey());

        if (modelConfig.getCompletionsPath() != null) {
            apiBuilder.completionsPath(modelConfig.getCompletionsPath());
        }

        OpenAiApi openAiApi = apiBuilder.build();

        OpenAiChatOptions chatOptions = OpenAiChatOptions.builder()
                .model(modelConfig.getChatModel())
                .build();

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(chatOptions)
                .build();
        ;
        this.chatClient = ChatClient.builder(chatModel).build();
        subAgentManager.setChatClient(this.chatClient);
        System.out.println("[模型切换] 已切换到: " + modelConfig);
    }

    /**
     * 根据意图动态选择工具
     * <p>
     * 策略：基于意图映射到功能域，只返回相关领域的工具给 LLM。
     * 如果没有匹配到特定域，返回全部工具以保证功能完整性。
     *
     * @param intent 识别出的用户意图
     * @return 筛选后的工具列表
     */
    private List<ToolCallback> selectToolsByIntent(Intent intent) {
        // 基于意图映射到功能域
        List<ToolDomain> relevantDomains = mapIntentToDomains(intent);
        
        List<ToolCallback> selectedTools = new ArrayList<>();
        for (ToolDomain domain : relevantDomains) {
            List<ToolCallback> domainToolsList = this.domainTools.get(domain);
            if (domainToolsList != null) {
                selectedTools.addAll(domainToolsList);
            }
        }
        
        // 如果没有匹配到特定域，返回全部工具
        return selectedTools.isEmpty() ? allToolCallbacks : selectedTools;
    }
    
    /**
     * 意图到功能域的映射规则
     * <p>
     * 根据意图类型决定需要暴露哪些功能域的工具给 LLM。
     *
     * @param intent 用户意图
     * @return 相关的功能域列表
     */
    private List<ToolDomain> mapIntentToDomains(Intent intent) {
        switch (intent) {
            case WEATHER:
                return List.of(ToolDomain.WEATHER);
            case FINANCE:
                return List.of(ToolDomain.STOCK);
            case RAG:
                return List.of(ToolDomain.RAG);
            case CODE_REVIEW:
                // 代码审查可能需要代码工具和技能工具
                return List.of(ToolDomain.CODE, ToolDomain.SKILL);
            case SUB_TASK:
                // 子任务可能需要 SubAgent 工具
                return List.of(ToolDomain.SUB_AGENT);
            default:
                // 空列表表示使用全部工具
                return Collections.emptyList();
        }
    }

    /**
     * 获取或创建指定 sessionId 的对话记忆
     */
    private ChatMemory getOrCreateMemory(String sessionId) {
        return sessionMemories.computeIfAbsent(sessionId, id -> {
            ChatMemory memory = ChatMemory.forMainAgent(chatClient);
            memory.setSystemPrompt(systemPromptText);
            return memory;
        });
    }

    /**
     * 设置自定义 system prompt（全局生效，影响后续新建的会话）
     */
    public void setSystemPrompt(String systemPrompt) {
        this.systemPromptText = systemPrompt;
        // 同步更新所有已有会话的 system prompt
        sessionMemories.values().forEach(memory -> memory.setSystemPrompt(systemPrompt));
    }

    /**
     * 设置模型推理参数
     */
    public void setModelParams(Double temperature, Integer maxTokens, Double topP) {
        if (temperature != null) this.temperature = temperature;
        if (maxTokens != null) this.maxTokens = maxTokens;
        if (topP != null) this.topP = topP;
    }

    public Double getTemperature() { return temperature; }
    public Integer getMaxTokens() { return maxTokens; }
    public Double getTopP() { return topP; }

    /**
     * 构建当前模型推理参数
     */
    private OpenAiChatOptions buildChatOptions() {
        return OpenAiChatOptions.builder()
                .temperature(temperature)
                .maxTokens(maxTokens)
                .topP(topP)
                .build();
    }

    /**
     * 与 Agent 对话
     * <p>
     * 流程：意图识别 → 按需注入 RAG 上下文 → 动态选择工具 → 大模型调用（含工具调用）
     *
     * @param sessionId 会话 ID，用于隔离不同客户端的对话记忆
     * @param userInput 用户输入
     * @return 模型回复
     */
    public String chat(String sessionId, String userInput) {
        ChatMemory memory = getOrCreateMemory(sessionId);

        // 1. 意图识别（传入知识库主题描述）
        Intent intent = intentRecognizer.recognize(userInput, ragService.getKnowledgeTopics());

        // 2. 如果是 RAG 意图，先检索知识库并注入上下文
        if (intent == Intent.RAG && ragService.isKnowledgeLoaded()) {
            String ragContext = ragService.query(userInput);
            if (ragContext != null && !ragContext.isBlank()) {
                String enrichedInput = "以下是从知识库中检索到的相关参考资料，请结合这些资料回答用户的问题：\n\n"
                        + ragContext + "\n\n用户问题：" + userInput;
                memory.addMessage(new UserMessage(enrichedInput));
            } else {
                memory.addMessage(new UserMessage(userInput));
            }
        } else {
            memory.addMessage(new UserMessage(userInput));
        }

        // 3. 动态选择工具
        List<ToolCallback> selectedTools = selectToolsByIntent(intent);

        // 4. 构建 Prompt（带模型参数）并调用大模型（getMessages 内部自动触发摘要压缩）
        List<Message> messages = memory.getMessages();
        Prompt prompt = new Prompt(messages, buildChatOptions());

        ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt(prompt);

        if (!selectedTools.isEmpty()) {
            requestSpec.toolCallbacks(selectedTools.toArray(new ToolCallback[0])); // 把工具回调注册到请求中，toArray方法的参数的作用是指定数组的元素类型
        }

        String response = requestSpec.call().content();

        memory.addMessage(new AssistantMessage(response != null ? response : ""));

        return response != null ? response : "";
    }

    /**
     * 与 Agent 流式对话
     * <p>
     * 流程与 chat() 相同（意图识别 → RAG 注入 → 动态选择工具 → 大模型调用），
     * 但以 Flux 流式返回每个 token，同时在流结束后将完整响应存入记忆。
     *
     * @param sessionId 会话 ID
     * @param userInput 用户输入
     * @return 流式 token
     */
    public Flux<String> chatStream(String sessionId, String userInput) {
        ChatMemory memory = getOrCreateMemory(sessionId);

        // 1. 意图识别（传入知识库主题描述）
        Intent intent = intentRecognizer.recognize(userInput, ragService.getKnowledgeTopics());

        // 2. 如果是 RAG 意图，先检索知识库并注入上下文
        if (intent == Intent.RAG && ragService.isKnowledgeLoaded()) {
            String ragContext = ragService.query(userInput);
            if (ragContext != null && !ragContext.isBlank()) {
                String enrichedInput = "以下是从知识库中检索到的相关参考资料，请结合这些资料回答用户的问题：\n\n"
                        + ragContext + "\n\n用户问题：" + userInput;
                memory.addMessage(new UserMessage(enrichedInput));
            } else {
                memory.addMessage(new UserMessage(userInput));
            }
        } else {
            memory.addMessage(new UserMessage(userInput));
        }

        // 3. 动态选择工具
        List<ToolCallback> selectedTools = selectToolsByIntent(intent);

        // 4. 构建 Prompt（带模型参数）并流式调用大模型（getMessages 内部自动触发摘要压缩）
        List<Message> messages = memory.getMessages();
        Prompt prompt = new Prompt(messages, buildChatOptions());

        ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt(prompt);

        if (!selectedTools.isEmpty()) {
            requestSpec.toolCallbacks(selectedTools.toArray(new ToolCallback[0]));
        }

        StringBuilder fullResponse = new StringBuilder();

        return requestSpec.stream().content()
                .doOnNext(fullResponse::append)
                .doOnComplete(() -> {
                    String response = fullResponse.toString();
                    memory.addMessage(new AssistantMessage(response.isEmpty() ? "" : response));
                })
                .doOnError(error -> {
                    System.err.println("[Stream] 流式对话异常: " + error.getMessage());
                    memory.addMessage(new AssistantMessage(""));
                });
    }

    /**
     * 清空指定会话的对话历史
     *
     * @param sessionId 会话 ID
     */
    public void clearMemory(String sessionId) {
        ChatMemory memory = sessionMemories.remove(sessionId);
        if (memory != null) {
            memory.clear();
        }
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}
