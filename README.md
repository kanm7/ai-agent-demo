# **快速开始**

本项目是一个基于Spring AI的AI Agent应用（**纯Demo，仅学习用途**），集成了 RAG 检索增强生成、Function Calling 工具调用、MCP 协议、SubAgent 子代理、Skill 技能系统等核心能力。本文将从六个核心模块出发，深入剖析其架构设计和实现细节。

## **环境要求**

- Java 21+
- Maven 3.9+

## **核心模块**

| **模块**                     | **说明**                                                     |
| ---------------------------- | ------------------------------------------------------------ |
| **AgentCore**                | 核心编排器，具备意图识别、记忆管理与大模型调用等能力。       |
| **ChatMemory**               | 对话记忆管理，支持三层上下文压缩（摘要压缩 → Assistant 裁剪 → 滑动窗口）。 |
| **Tool（Function Calling）** | 可插拔的工具注册机制，通过 `InnerTool` 统一接口注册，LLM 自主决策调用 |
| **RAG**                      | 完整的检索增强生成流水线：文档加载 → 文档分块 → 向量化 → 向量存储 → 多路召回（语义 + BM25 + 查询改写）→ RRF 融合 → Rerank 重排 → LLM → 内容生成 |
| **Command & Skill**          | 两种 Markdown 驱动的 Prompt 模板机制：Command 由用户主动调用，Skill 本质作为Tool由 LLM 决策调用。 |
| **SubAgent**                 | 拥有独立记忆的子代理，支持内部 SubAgent 和外部 IdeaLab Agent 两种形态 |
| **MCP**                      | 双向 MCP 支持：作为 Client 动态连接外部 MCP 服务，作为 Server 对外暴露服务 |

## **配置**

编辑 `src/main/resources/application.properties`，配置大模型 API

```
spring.ai.openai.base-url=https://open.bigmodel.cn/api/paas/v4
spring.ai.openai.api-key=你的API密钥
spring.ai.openai.chat.options.model=glm-4
spring.ai.openai.embedding.options.model=embedding-3
```

## **启动**

```
./mvnw spring-boot:run
```

## **访问**

### **前端页面**

启动成功后，打开浏览器访问：

```
http://localhost:8080
```

项目内置了一个完整的 Web 聊天界面（`src/main/resources/static/index.html`），支持：

- **流式对话**：实时逐字输出 AI 回复（SSE）
- **Markdown 渲染**：自动渲染代码块、表格、列表等
- **命令面板**：输入 `/` 唤起快捷命令列表
- **会话管理**：支持清空对话历史

![img](https://oss-ata.alibaba.com/article/2026/04/23d39606-5fa4-4e3a-aa6b-7603e823e42c)

### **API 直接调用**

```
# 非流式对话
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "你好，介绍一下你的能力", "sessionId": "test-001"}'
# 流式对话（SSE）
curl -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
```

# **一、核心编排器：AgentCore**

`AgentCore` 是整个系统的"大脑"，负责编排对话的完整流程：**意图识别 → RAG 注入 → 记忆管理 → 模型调用 → 工具执行**。

## **1.1 对话流程**

```
用户输入
  │
  ▼
意图识别（IntentRecognizer）
  │ 判断：这是知识问答还是通用对话？
  ▼
RAG 注入（RagService）
  │ 如果是知识问答，检索知识库，将参考资料拼入上下文
  ▼
记忆管理（ChatMemory）
  │ 自动摘要压缩 → 构建消息列表
  ▼
模型调用（ChatClient + ToolCallbacks）
  │ LLM 决策：直接回答 or 调用工具？
  │ 如果调用工具 → 执行工具 → 将结果返回 LLM → 继续决策（ReAct 循环）
  ▼
返回最终回复
```

核心代码（`AgentCore.chat()`）：

```
public String chat(String sessionId, String userInput) {
    ChatMemory memory = getOrCreateMemory(sessionId);
    // 1. 意图识别
    Intent intent = intentRecognizer.recognize(userInput);
    // 2. 如果是 RAG 意图，先检索知识库并注入上下文
    if (intent == Intent.RAG && ragService.isKnowledgeLoaded()) {
        String ragContext = ragService.query(userInput);
        if (ragContext != null && !ragContext.isBlank()) {
            String enrichedInput = "以下是从知识库中检索到的相关参考资料，"
                    + "请结合这些资料回答用户的问题：\n\n"
                    + ragContext + "\n\n用户问题：" + userInput;
            memory.addMessage(new UserMessage(enrichedInput));
        } else {
            memory.addMessage(new UserMessage(userInput));
        }
    } else {
        memory.addMessage(new UserMessage(userInput));
    }
    // 3. 构建 Prompt 并调用大模型（getMessages 内部自动触发摘要压缩）
    List<Message> messages = memory.getMessages();
    Prompt prompt = new Prompt(messages, buildChatOptions());
    ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt(prompt);
    if (!toolCallbacks.isEmpty()) {
        requestSpec.toolCallbacks(toolCallbacks.toArray(new ToolCallback[0]));
    }
    String response = requestSpec.call().content();
    memory.addMessage(new AssistantMessage(response != null ? response : ""));
    return response != null ? response : "";
}
```

## **1.2 Agent Loop**

Spring AI已实现Agent Loop，具体路径为org.springframework.ai.chat.client.advisor.ToolCallAdvisor#adviseCall

```
boolean isToolCall = false;

do {

    // Before Call
    var processedChatClientRequest = ChatClientRequest.builder()
       .prompt(new Prompt(instructions, optionsCopy))
       .context(chatClientRequest.context())
       .build();

    // Next Call
    processedChatClientRequest = this.doBeforeCall(processedChatClientRequest, callAdvisorChain);

    chatClientResponse = callAdvisorChain.copy(this).nextCall(processedChatClientRequest);

    chatClientResponse = this.doAfterCall(chatClientResponse, callAdvisorChain);

    // After Call

    // TODO: check that this tool call detection is sufficient for all chat models
    // that support tool calls. (e.g. Anthropic and Bedrock are checking for
    // finish status as well)
    ChatResponse chatResponse = chatClientResponse.chatResponse();
    isToolCall = chatResponse != null && chatResponse.hasToolCalls();

    if (isToolCall) {
       Assert.notNull(chatResponse, "redundant check that should never fail, but here to help NullAway");
       ToolExecutionResult toolExecutionResult = this.toolCallingManager
          .executeToolCalls(processedChatClientRequest.prompt(), chatResponse);

       if (toolExecutionResult.returnDirect()) {

          // Return tool execution result directly to the application client.
          chatClientResponse = chatClientResponse.mutate()
             .chatResponse(ChatResponse.builder()
                .from(chatResponse)
                .generations(ToolExecutionResult.buildGenerations(toolExecutionResult))
                .build())
             .build();

          // Interrupt the tool calling loop and return the tool execution
          // result directly to the client application instead of returning
          // it to the LLM.
          break;
       }

       instructions = this.doGetNextInstructionsForToolCall(processedChatClientRequest, chatClientResponse,
             toolExecutionResult);
    }

}
while (isToolCall); // loop until no tool calls are present
```

## **1.3 意图识别**

`IntentRecognizer` 通过 LLM 判断用户输入的意图，目前支持两种：

- **RAG**：用户在问知识库相关的问题，需要先检索知识库再回答
- **GENERAL**：通用对话，直接交给 LLM 处理

意图识别前置的好处是：**避免每次对话都触发 RAG 检索**，节省不必要的向量检索和 Rerank 开销。

## **1.4 对话记忆：ChatMemory**

每个 `sessionId` 对应一个独立的 `ChatMemory` 实例，天然支持多客户端并发。

`ChatMemory` 设计了**三层递进的上下文压缩策略**，防止对话过长导致 token 溢出或成本失控：

### **第一层：摘要压缩（智能压缩）**

当历史消息超过 16 条时，自动将较早的消息通过 LLM 总结为一段 300 字以内的摘要，注入到 system prompt 中。原消息从 history 中移除。

核心代码（`ChatMemory.getMessages()` 和 `compressIfNeeded()`）：

```
public List<Message> getMessages() {
    // 在构建消息列表之前，自动尝试摘要压缩
    compressIfNeeded();
    List<Message> messages = new ArrayList<>();
    // 将原始 system prompt 与摘要合并为一条 SystemMessage
    if (systemMessage != null || (summaryText != null && !summaryText.isBlank())) {
        String systemContent = systemMessage != null ? systemMessage.getText() : "";
        if (summaryText != null && !summaryText.isBlank()) {
            systemContent += "\n\n【以下是之前对话的摘要，请参考】\n" + summaryText;
        }
        messages.add(new SystemMessage(systemContent));
    }
    // ... 添加历史消息（跳过早期 Assistant 消息）
    return Collections.unmodifiableList(messages);
}
private void compressIfNeeded() {
    if (chatClient == null || history.size() <= COMPRESS_THRESHOLD_MESSAGES) {
        return;
    }
    int compressEndIndex = history.size() - PRESERVE_RECENT_MESSAGES;
    // 确保不会在 TOOL 消息的前面截断
    while (compressEndIndex < history.size()
            && history.get(compressEndIndex).getMessageType() == MessageType.TOOL) {
        compressEndIndex--;
    }
    if (compressEndIndex <= 0) return;
    List<Message> messagesToCompress = new ArrayList<>(history.subList(0, compressEndIndex));
    String newSummary = SummaryCompressor.compress(chatClient, messagesToCompress, summaryText);
    if (newSummary != null && !newSummary.isBlank()) {
        this.summaryText = newSummary;
        history.subList(0, compressEndIndex).clear();
    }
}
```

这一层的核心设计：

- **内聚透明**：压缩逻辑完全封装在 `getMessages()` 内部，调用方无感知。压缩器 `SummaryCompressor` 作为 `ChatMemory` 的私有静态内部类，不对外暴露
- **增量压缩**：如果已有历史摘要，新的压缩会将旧摘要与新对话合并总结，避免信息随多次压缩逐渐丢失
- **TOOL 消息边界保护**：截断时自动避开 TOOL 消息，确保 TOOL 消息始终紧跟在对应的 ASSISTANT 消息后面，不会破坏工具调用上下文

### **第二层：Assistant 消息裁剪（精准裁剪）**

只保留最近 3 条 Assistant 回复。因为 LLM 的回复通常很长，是 token 消耗的大户，裁剪早期的 Assistant 消息能显著减少上下文体积。

### **第三层：滑动窗口（兜底保护）**

当消息总数超过 `maxRounds × 4` 时，直接丢弃最早的消息。这是最后一道防线，确保上下文不会无限增长。

三层策略协同工作：**摘要压缩优先触发**（保留信息），**Assistant 裁剪持续生效**（精准省 token），**滑动窗口兜底**（硬性保护）。

## **1.5 多会话隔离与运行时配置**

- **多会话**：`ConcurrentHashMap<String, ChatMemory>` 按 sessionId 隔离，支持并发
- **运行时切换模型**：通过 API 动态切换模型提供商（如从智谱切到通义千问），无需重启
- **运行时调参**：支持动态调整 temperature、maxTokens、topP 等推理参数

# **二、Tool 机制（Function Calling）**

LLM 只能"想"，Tool 让它能"做"。LLM本身是不会去调用各种服务，Agent服务端只是告诉大模型“有哪些工具可以调用”，LLM返回给Agent服务端的是“要去调哪些工具”，真实调用实在Agent服务端。

本项目基于 Spring AI 的 Function Calling 能力，设计了一套**可插拔的工具注册机制**。

## **2.1 工具注册机制**

所有工具实现统一的 `InnerTool` 接口：

```
public interface InnerTool {
    List<ToolCallback> loadToolCallbacks();
}
```

启动时，Spring 自动扫描所有 `InnerTool` Bean，调用 `loadToolCallbacks()` 收集所有工具，统一注册到 `AgentCore`。新增工具只需实现这个接口，无需修改任何已有代码。

`ToolCallbackBuilder` 提供了简洁的工具构建 API，将工具名、描述、参数定义（JSON Schema）和执行函数组装为 Spring AI 标准的 `ToolCallback`。

## **2.2 工具调用流程**

```
用户："杭州今天天气怎么样？"
  │
  ▼
LLM 分析意图，决定调用 get_weather 工具
  │
  ▼
Spring AI 自动执行工具：get_weather({"city": "杭州"})
  │
  ▼
工具返回结果："杭州，晴，22°C"
  │
  ▼
LLM 基于工具结果生成最终回复："杭州今天天气晴朗，气温 22°C，适合出行。"
```

Spring AI 的 ChatClient 内置了 **ReAct 循环**：LLM 可以连续调用多个工具，直到认为信息充足后给出最终回复。整个过程对开发者透明。

## **2.3 内置工具一览**

| **工具名**            | **功能**        | **说明**                                    |
| --------------------- | --------------- | ------------------------------------------- |
| `knowledge_search`    | 知识库检索      | 将 RAG 检索能力封装为工具，LLM 可主动检索   |
| `create_sub_agent`    | 创建子代理      | 创建拥有独立记忆的 SubAgent                 |
| `chat_with_sub_agent` | 与子代理对话    | 在 SubAgent 的独立上下文中继续对话          |
| `destroy_sub_agent`   | 销毁子代理      | 释放 SubAgent 资源                          |
| `call_ideas_{name}`   | 调用 IDEAs 应用 | 调用外部 IdeaLab 平台的 AI 应用（支持多个） |
| `{skill_name}`        | 执行技能        | 由 Markdown 文件定义的技能，动态注册        |
| `{mcp_tool_name}`     | MCP 工具        | 从外部 MCP Server 发现并注册的工具          |
| `get_weather`         | 天气查询        | 示例工具                                    |
| `get_stock_price`     | 股票价格查询    | 示例工具                                    |

# **三、RAG 模块：检索增强生成**

RAG（Retrieval-Augmented Generation）让 Agent 能够基于私有知识库回答问题。

## **3.1 RAG完整流水线**

![img](https://oss-ata.alibaba.com/article/2026/04/a775e580-3f7e-45b8-9139-6ab2bc629b78)

## **3.2 文档分块策略**

分块质量直接决定检索质量。项目提供了多种分块策略，分为**确定规则分块**和**智能分块**两类：

### **确定规则分块（Definite）**

| **策略**                  | **原理**                                                     | **适用场景**             |
| ------------------------- | ------------------------------------------------------------ | ------------------------ |
| **TextSplitter**（默认）  | 递归语义分块，按标题 → 段落 → 句子 → 固定字符的优先级依次尝试切分 | 通用文档，兼顾语义完整性 |
| **FixedSizeSplitter**     | 按固定字符数切分                                             | 结构不明确的纯文本       |
| **ParagraphSplitter**     | 按段落（连续换行）切分                                       | 段落结构清晰的文档       |
| **SentenceSplitter**      | 按句子（句末标点）切分                                       | 需要细粒度检索的场景     |
| **SlidingWindowSplitter** | 滑动窗口切分，相邻块有重叠                                   | 需要保留上下文连续性     |

### **智能分块（Intelligent）**

| **策略**                  | **原理**                  | **适用场景**           |
| ------------------------- | ------------------------- | ---------------------- |
| **SemanticChunkSplitter** | 基于语义相似度判断切分点  | 语义边界不明确的长文本 |
| **PropositionSplitter**   | 将文本拆解为独立命题      | 需要精确事实检索       |
| **AgenticSplitter**       | 使用 LLM 判断最佳切分方式 | 复杂混合格式文档       |

默认使用 `TextSplitter`（递归语义分块），分块大小 500 字符，重叠 50 字符。

## **3.2 检索流程核心代码**

`RagService.query()` 封装了完整的检索流程：

```
public String query(String question) {
    // 1. 多路召回（语义 + BM25 + 查询改写，共 9 个候选）
    List<Document> candidates = multiRecaller.recall(question, RECALL_CANDIDATE_COUNT);
    // 2. Rerank 重排（取最相关的 3 个）
    List<Document> relevantDocuments = llmReranker.rerank(question, candidates, TOP_K);
    // 3. 拼接上下文
    StringBuilder contextBuilder = new StringBuilder();
    for (int i = 0; i < relevantDocuments.size(); i++) {
        contextBuilder.append("【参考资料 ").append(i + 1).append("】\n");
        contextBuilder.append(relevantDocuments.get(i).getContent()).append("\n\n");
    }
    return contextBuilder.toString().trim();
}
```

### **3.3 召回策略**

单一召回策略总有盲区，项目使用**多路召回 + RRF 融合**的方案：

| **召回器**               | **原理**                                             | **擅长**                 |
| ------------------------ | ---------------------------------------------------- | ------------------------ |
| **SemanticRecaller**     | 基于 EmbeddingModel 的向量余弦相似度检索             | 语义相近但措辞不同的查询 |
| **Bm25Recaller**         | 基于 BM25 算法的关键词匹配（TF-IDF 变体）            | 精确关键词匹配           |
| **QueryRewriteRecaller** | 先用 LLM 将问题改写为 3 种不同表达，再分别做向量召回 | 扩大语义覆盖面           |

三路召回结果通过 **RRF（Reciprocal Rank Fusion）** 算法融合：

```
// MultiRecaller 核心逻辑
public List<Document> recall(String query, int topK) {
    Map<String, Double> rrfScores = new HashMap<>();
    Map<String, Document> keyToDocument = new LinkedHashMap<>();
    for (Recaller retriever : retrievers) {
        List<Document> results = retriever.recall(query, PER_ROUTE_CANDIDATE_COUNT);
        // RRF 公式：score(d) = Σ 1 / (k + rank)，k=60 为平滑常数
        accumulateRrfScores(results, rrfScores, keyToDocument);
    }
    return rrfScores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(topK)
            .map(entry -> keyToDocument.get(entry.getKey()))
            .toList();
}
```

RRF 只看排名不看绝对分数，天然适合融合不同算法的结果。

## **3.4 Rerank 重排**

多路召回后通常有 9 个候选文档，通过专用的 **Rerank 模型**精排，取最相关的 3 个。

## **3.5 向量存储**

`VectorStore` 是一个轻量级的内存向量存储实现，使用 Spring AI 的 `EmbeddingModel` 生成向量，通过余弦相似度检索。适合中小规模知识库，生产环境可替换为 Milvus、Pinecone 等专业向量数据库。

# **四、Command 与 Skill：两种 Prompt 模板机制**

Command 和 Skill 都是基于 Markdown 文件定义的 Prompt 模板，但它们的设计理念和使用方式截然不同。

## **4.1 Skill：LLM 自主调用的工具** 

Skill 文件使用 **YAML Front Matter + Prompt 模板** 格式：

```
---
name: summarize
description: 对用户提供的文本内容进行摘要总结
---
请对以下文本进行摘要总结，提取核心要点：
{{input}}
```

`SkillManager` 在启动时扫描 `classpath:skill/*.md`，解析元数据后由 `SkillTool` 将每个技能转换为 `ToolCallback` 注册到 Agent。LLM 在对话中根据 `description` **自主判断**是否需要调用某个技能。

### **4.2 Command：用户主动调用的快捷指令**

Command 文件是**纯 Prompt 模板**，文件名即为命令名：

```
请对以下代码进行 Code Review，从代码质量、潜在 Bug、性能、可读性等维度给出改进建议：
{{input}}
```

`CommandManager` 在启动时扫描 `classpath:command/*.md`，加载到内存。用户通过 REST API（`POST /api/command/execute`）**主动指定**命令名来执行。

### **4.3 核心区别对比**

| **维度**           | **Command**                   | **Skill**                                  |
| ------------------ | ----------------------------- | ------------------------------------------ |
| **设计理念**       | 用户快捷指令                  | LLM 可调用的工具                           |
| **文件格式**       | 纯 Prompt 模板                | Front Matter（name + description）+ Prompt |
| **是否注册为工具** | ❌ 不注册                      | ✅ 注册为 ToolCallback                      |
| **调用触发方**     | 用户主动指定命令名            | LLM 根据 description 自主决策              |
| **执行路径**       | 用户 → Controller → AgentCore | 用户 → AgentCore → LLM 决策 → SkillTool    |
| **适用场景**       | 用户明确知道需要什么功能      | 需要 LLM 理解上下文后智能判断              |

**一句话总结**：

Command 是"用户告诉 Agent 做什么"，Skill 是"Agent 自己判断该做什么"。两者互补——Command 提供确定性的快捷入口，Skill 提供智能化的能力扩展。

# **五、SubAgent：独立记忆的子代理**

## **5.1 为什么需要 SubAgent**

有些任务需要独立的上下文。比如用户说"帮我写一篇技术文章"，这个任务可能需要多轮对话来完善，但不应该污染主对话的记忆。SubAgent 就是为此设计的。

## **5.2 记忆隔离机制**

SubAgent 的核心是**记忆隔离**：每个 SubAgent 拥有独立的 `ChatMemory` 实例，与主 Agent 的记忆完全隔离。

```
public SubAgent(String id, String name, String systemPrompt, ChatClient chatClient) {
    this.memory = ChatMemory.forSubAgent();  // 独立记忆！
    this.memory.setSystemPrompt(systemPrompt);
    // ...
}
```

SubAgent 共享主 Agent 的 `ChatClient`（即共享同一个大模型连接），但对话历史完全独立。这意味着：

- SubAgent 内部的多轮对话不会影响主对话的上下文
- 主 Agent 可以同时管理多个 SubAgent，各自互不干扰
- SubAgent 销毁后，其记忆随之释放

## **5.3 Tool 本质**

SubAgent 的能力通过 **3 个工具**暴露给主 Agent，本质上就是 Function Calling：

| **工具**              | **参数**                  | **说明**                     |
| --------------------- | ------------------------- | ---------------------------- |
| `create_sub_agent`    | name、system_prompt、task | 创建 SubAgent 并执行首个任务 |
| `chat_with_sub_agent` | agent_id、message         | 与已有 SubAgent 继续对话     |
| `destroy_sub_agent`   | agent_id                  | 销毁 SubAgent，释放资源      |

主 LLM 根据对话上下文自主决定是否需要创建 SubAgent。整个生命周期（创建 → 多轮对话 → 销毁）都由主 LLM 通过工具调用来驱动。

## **5.4 内部 SubAgent vs 外部 IdeaLab Agent**

项目还集成了阿里内部 IdeaLab（IDEAs）平台的 AI 应用。从主 Agent 的视角看，IdeaLab Agent 和内部 SubAgent 有着相似的定位——**都是作为工具被主 Agent 调用的"子代理"**。但两者在本质上有显著差异：

| **维度**     | **内部 SubAgent**                    | **外部 IdeaLab Agent**                        |
| ------------ | ------------------------------------ | --------------------------------------------- |
| **运行位置** | 当前 JVM 进程内                      | IdeaLab 平台（远程 HTTP 服务）                |
| **记忆管理** | 独立的 ChatMemory 实例，完全可控     | 通过 sessionId 维护，由平台管理               |
| **定制性**   | 可自定义 system prompt、模型参数     | 依赖平台应用的预设能力                        |
| **生命周期** | 动态创建 → 多轮对话 → 手动销毁       | 无状态，配置驱动，启动时注册                  |
| **配置方式** | 运行时由 LLM 动态创建                | application.properties 配置文件驱动           |
| **资源消耗** | 共享 ChatClient，内存占用小          | HTTP 调用，有网络开销                         |
| **工具注册** | 固定 3 个工具（create/chat/destroy） | 每个应用注册为独立工具（`call_ideas_{name}`） |

**IdeaLab Agent 的配置方式**：

```
# 支持配置多个应用，每个应用独立注册为工具
ideas.apps[0].name=翻译助手
ideas.apps[0].ak=你的AK
ideas.apps[0].app-code=应用代码
ideas.apps[0].app-version=latest
ideas.apps[0].description=专业的多语言翻译工具（必填，LLM 根据此描述决定何时调用）
```

`description` 是必填字段——它是 LLM 理解这个工具用途的唯一依据。启动时，`IdeasTool` 遍历配置列表，为每个有效配置创建独立的 `IdeasClient` 实例和 `ToolCallback`，实现**配置即注册**。

**选择建议**：需要灵活定制角色和多轮交互时用内部 SubAgent；需要调用平台已有的成熟 AI 应用时用 IdeaLab Agent。

# **六、MCP：连接一切外部服务**

[MCP（Model Context Protocol）](https://modelcontextprotocol.io/) 是 Anthropic 提出的开放协议，让 AI 应用能够标准化地连接外部工具和数据源。本项目同时实现了 **MCP Server**（对外暴露能力）和 **MCP Client**（连接外部服务）。

## **6.1 MCP Server：对外暴露知识库检索能力**

项目通过 `SimpleMcpServer` 对外提供知识库检索工具，其他 AI 应用可以通过 MCP 协议来调用：

**工具：**`knowledge_query`

| **参数**     | **类型** | **说明**                                                     |
| ------------ | -------- | ------------------------------------------------------------ |
| `keyword`    | String   | 检索关键词                                                   |
| `category`   | String   | 知识分类（java_basic / jvm / concurrent / spring / design_pattern / all） |
| `maxResults` | int      | 返回的最大结果条数，默认 3                                   |

内部调用 `RagService` 执行检索，将结果格式化后返回。这意味着本项目的 RAG 能力可以被任何支持 MCP 协议的 AI 应用复用。

## **6.2 MCP Client：动态连接外部 MCP 服务**

`McpClient` 封装了连接外部 MCP Server 的完整逻辑：

核心代码（`McpClient.connect()`）：

```
public ToolCallback[] connect(String serverUrl) {
    McpSyncClient mcpClient;
    McpSchema.InitializeResult initResult;
    // 优先尝试 Streamable HTTP，失败后回退到 SSE
    try {
        mcpClient = connectWithStreamableHttp(serverUrl);
        initResult = mcpClient.initialize();
    } catch (Exception streamableException) {
        mcpClient = connectWithSse(serverUrl);
        initResult = mcpClient.initialize();
    }
    // 自动发现远程工具
    SyncMcpToolCallbackProvider provider = SyncMcpToolCallbackProvider.builder()
            .mcpClients(mcpClient).build();
    ToolCallback[] toolCallbacks = provider.getToolCallbacks();
    // 持久化 URL，下次启动自动恢复
    store.add(serverUrl);
    return toolCallbacks;
}
```

**关键特性**：

- **传输协议自动适配**：优先 Streamable HTTP（2025-03-26 规范），失败自动回退 SSE（2024-11-05 规范）
- **工具自动发现**：连接成功后自动获取远程工具，转换为 `ToolCallback` 注册到 Agent
- **持久化与自动恢复**：URL 持久化到 `mcp-servers.json`，应用重启时自动重连

**运行时动态管理**：通过 REST API 在运行时动态管理 MCP 连接：

| **接口**                     | **方法** | **说明**                        |
| ---------------------------- | -------- | ------------------------------- |
| `/api/manage/mcp/connect`    | POST     | 连接新的 MCP 服务，工具立即可用 |
| `/api/manage/mcp/disconnect` | POST     | 断开 MCP 服务，移除对应工具     |
| `/api/manage/mcp/list`       | GET      | 查看所有 MCP 服务及其工具列表   |