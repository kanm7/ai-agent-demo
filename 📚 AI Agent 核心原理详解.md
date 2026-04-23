## 📚 AI Agent 核心原理详解

### **一、什么是 AI Agent?**

**Agent = LLM + 记忆 + 工具 + 规划能力**

传统的LLM只能"思考",Agent让它能"行动"。就像人类一样:
- **大脑(LLM)**:理解问题、做决策
- **记忆(ChatMemory)**:记住之前的对话
- **双手(Tools)**:调用外部能力(查天气、搜知识库)
- **规划能力**:决定先做什么后做什么

---

### **二、Agent 核心架构:AgentCore**

你的项目中,[AgentCore](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/core/AgentCore.java)就是整个系统的"大脑中枢",它负责编排完整的对话流程:

```
用户输入 → 意图识别 → RAG注入 → 构建Prompt → 调用LLM → (可能触发工具) → 返回结果
```


#### **关键设计点:**

1. **意图识别前置**([IntentRecognizer](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/core/IntentRecognizer.java))
   - 为什么需要先判断意图?因为RAG检索很耗时(向量计算+重排),如果用户只是闲聊,没必要检索
   - 实现方式:用LLM本身做分类器,通过few-shot prompt让模型输出"RAG"或"GENERAL"标签
   - 这是一种**轻量级路由策略**,避免不必要的计算开销

2. **多会话隔离**
   ```java
   private final Map<String, ChatMemory> sessionMemories = new ConcurrentHashMap<>();
   ```

   - 每个sessionId对应独立的ChatMemory实例
   - 使用ConcurrentHashMap保证线程安全,支持多个用户同时对话互不干扰

3. **运行时动态切换模型**
   - 可以在不停止服务的情况下,从智谱切换到通义千问
   - 原理:重新构建OpenAiApi和ChatClient,替换原有实例

---

### **三、Function Calling(Function Tool)原理**

这是让LLM能够"行动"的核心机制。

#### **工作流程:**

```
用户:"杭州今天天气怎么样?"
    ↓
LLM分析:需要调用get_weather工具
    ↓
LLM返回:{tool_calls: [{name: "get_weather", arguments: {city: "杭州"}}]}
    ↓
Agent服务端执行:get_weather("杭州")
    ↓
返回结果:"杭州,晴,22°C"
    ↓
将结果再次发送给LLM
    ↓
LLM生成最终回复:"杭州今天天气晴朗,气温22°C"
```


**关键点:**
- **LLM不会真正调用工具**,它只是告诉Agent"我想调用哪个工具,参数是什么"
- **真实调用在Agent服务端完成**,Spring AI的`ToolCallAdvisor`自动处理这个循环(ReAct模式)
- 工具可以连续调用多次,直到LLM认为信息充足

#### **你的项目中的工具注册机制:**

所有工具实现[InnerTool](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/service/tool/InnerTool.java)接口:
```java
public interface InnerTool {
    List<ToolCallback> loadToolCallbacks();
}
```


启动时,Spring自动扫描所有InnerTool Bean,统一收集并注册到AgentCore。这种**插件化设计**的好处是:新增工具只需实现接口,无需修改已有代码。

---

### **四、RAG(Retrieval-Augmented Generation)原理**

RAG解决的是LLM的**知识时效性**和**私有数据**问题。

#### **完整流水线:**

```
文档加载 → 分块(Chunking) → 向量化(Embedding) → 存入向量库
                                              ↓
用户提问 → 多路召回 → RRF融合 → Rerank重排 → 拼接上下文 → 送入LLM
```


#### **1. 文档分块(Chunking)**

为什么需要分块?
- 向量数据库存储的是**文本片段**,不是整篇文档
- 分块太小→丢失上下文;分块太大→检索精度下降

你的项目提供了多种策略:
- **TextSplitter**(默认):递归语义分块,按标题→段落→句子优先级切分
- **FixedSizeSplitter**:固定字符数切分
- **SemanticChunkSplitter**:基于语义相似度判断切分点(智能分块)

#### **2. 向量化(Embedding)**

将文本转换为高维向量(如768维),语义相似的文本向量距离更近。
- 使用EmbeddingModel(如智谱的embedding-3)
- 向量存储在[VectorStore](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/service/rag/VectorStore.java)(内存实现,生产环境可换Milvus/Pinecone)

#### **3. 多路召回(Multi-Retriever)**

单一召回策略有盲区,所以采用三路召回:

| 召回器                    | 原理                       | 擅长场景           |
| ------------------------- | -------------------------- | ------------------ |
| **SemanticRetriever**     | 向量余弦相似度             | 语义相近但措辞不同 |
| **Bm25Retriever**         | BM25关键词匹配             | 精确关键词匹配     |
| **QueryRewriteRetriever** | LLM改写问题为3种表达再检索 | 扩大覆盖面         |

**RRF融合算法:**
```java
// RRF公式: score(d) = Σ 1 / (k + rank), k=60为平滑常数
```

只看排名不看绝对分数,天然适合融合不同算法的结果。

#### **4. Rerank重排**

多路召回后有9个候选文档,用专用的Rerank模型精排,取最相关的3个。
- 为什么需要重排?因为召回阶段追求速度,重排阶段追求精度
- Rerank模型专门训练用于判断(query, document)的相关性

#### **5. 意图识别优化**

在[AgentCore.chat()](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/core/AgentCore.java#L197-L232)中:
```java
if (intent == Intent.RAG && ragService.isKnowledgeLoaded()) {
    String ragContext = ragService.query(userInput);
    // 将检索结果拼接到用户输入中
    String enrichedInput = "以下是参考资料...\n\n" + ragContext + "\n\n用户问题:" + userInput;
}
```


这样LLM就能基于检索到的知识回答问题,而不是依赖训练时的旧知识。

---

### **五、ChatMemory(对话记忆)原理**

LLM是无状态的,每次请求都是独立的。ChatMemory的作用就是**维护对话历史**。

#### **三层压缩策略:**

**第一层:摘要压缩(Summary Compression)**
- 当历史消息超过15条时,用LLM将早期消息总结为300字以内的摘要
- 摘要注入到system prompt中
- **增量压缩**:如果已有摘要,会将旧摘要与新对话合并总结,避免信息丢失
- **TOOL消息保护**:截断时避开TOOL消息,确保工具调用上下文完整

**第二层:Assistant消息裁剪**
- 只保留最近3条Assistant回复
- 原因:LLM回复通常很长,是token消耗大户

**第三层:滑动窗口(兜底)**
- 当消息总数超过maxRounds×4时,直接丢弃最早的消息
- 硬性保护,防止无限增长

这三层策略协同工作:**摘要优先**(保留信息)→**精准裁剪**(省token)→**滑动窗口**(兜底)。

---

### **六、Skill与Command的区别**

两者都是基于Markdown的Prompt模板,但设计理念完全不同:

| 维度               | Command                    | Skill                                 |
| ------------------ | -------------------------- | ------------------------------------- |
| **触发方式**       | 用户主动指定(`/summarize`) | LLM自主决策                           |
| **是否注册为工具** | ❌                          | ✅                                     |
| **文件格式**       | 纯Prompt模板               | Front Matter(name+description)+Prompt |
| **适用场景**       | 用户明确知道要什么         | 需要LLM理解上下文后判断               |

**Skill的工作原理:**
1. [SkillManager](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/service/skill/SkillManager.java)扫描`classpath:skill/*.md`
2. 解析YAML Front Matter获取name和description
3. [SkillTool](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/service/tool/impl/SkillTool.java)将每个技能转换为ToolCallback注册
4. LLM根据description自主判断是否调用

例如用户说"帮我总结一下这段代码",LLM看到summarize技能的description是"对文本进行摘要总结",就会自主决定调用这个技能。

---

### **七、SubAgent(子代理)原理**

**为什么需要SubAgent?**

有些任务需要独立的上下文。比如用户说"帮我写一篇技术文章",可能需要多轮对话完善细节,但不应该污染主对话的记忆。

#### **核心设计:记忆隔离**

```java
public SubAgent(String id, String name, String systemPrompt, ChatClient chatClient) {
    this.memory = ChatMemory.forSubAgent();  // 独立记忆!
    this.memory.setSystemPrompt(systemPrompt);
}
```


- 每个SubAgent拥有独立的ChatMemory实例
- 共享主Agent的ChatClient(同一个大模型连接)
- SubAgent内部的多轮对话不会影响主对话

#### **生命周期管理:**

通过3个工具暴露给主Agent:
- `create_sub_agent`:创建SubAgent并执行首个任务
- `chat_with_sub_agent`:与已有SubAgent继续对话
- `destroy_sub_agent`:销毁SubAgent释放资源

主LLM根据对话上下文自主决定是否需要创建SubAgent,整个生命周期由工具调用驱动。

---

### **八、MCP(Model Context Protocol)原理**

MCP是Anthropic提出的开放协议,让AI应用能够**标准化地连接外部工具和数据源**。

#### **双向支持:**

**1. MCP Server(对外暴露能力)**

[SimpleMcpServer](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/api/mcpserver/SimpleMcpServer.java)对外提供知识库检索:
```java
@Tool(name = "knowledge_query", description = "查询内部知识库...")
public String knowledgeQuery(@ToolParam String keyword, ...) {
    return ragService.query(formattedQuery);
}
```


其他支持MCP协议的AI应用可以通过标准协议调用这个服务。

**2. MCP Client(连接外部服务)**

[McpClient](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/service/extrenal/McpClient.java)封装了连接外部MCP Server的逻辑:

```java
public ToolCallback[] connect(String serverUrl) {
    // 1. 尝试Streamable HTTP,失败回退SSE
    // 2. 初始化连接
    // 3. 自动发现远程工具
    SyncMcpToolCallbackProvider provider = ...;
    ToolCallback[] toolCallbacks = provider.getToolCallbacks();
    // 4. 持久化URL,下次启动自动恢复
    store.add(serverUrl);
    return toolCallbacks;
}
```


**关键特性:**
- **传输协议自动适配**:优先Streamable HTTP(新规范),失败回退SSE(旧规范)
- **工具自动发现**:连接成功后自动获取远程工具,转换为ToolCallback注册
- **持久化**:URL保存到`mcp-servers.json`,重启自动重连

**本质理解:**
MCP Client连接外部服务后,远程的工具就变成了本地的ToolCallback,对Agent来说,**本地工具和远程工具没有区别**,都是通过Function Calling调用。

---

### **九、整体架构总结**

```
┌─────────────────────────────────────────┐
│          用户界面(Web/API)               │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│         AgentCore(核心编排器)            │
│  ┌──────────┐  ┌──────────┐            │
│  │意图识别  │→ │RAG注入   │            │
│  └──────────┘  └──────────┘            │
│         ↓                              │
│  ┌──────────────────────┐              │
│  │  ChatMemory(记忆管理) │              │
│  │  - 摘要压缩           │              │
│  │  - Assistant裁剪      │              │
│  │  - 滑动窗口           │              │
│  └──────────────────────┘              │
│         ↓                              │
│  ┌──────────────────────┐              │
│  │  ChatClient + Tools  │              │
│  │  (ReAct循环)         │              │
│  └──────────────────────┘              │
└──────────┬─────────────────────────────┘
           │
     ┌─────┴─────┬──────────┬──────────┐
     ▼           ▼          ▼          ▼
  ┌──────┐  ┌──────┐  ┌──────┐  ┌──────┐
  │ RAG  │  │Skill │  │SubAg │  │ MCP  │
  │工具  │  │工具  │  │ent   │  │Client│
  └──────┘  └──────┘  └──────┘  └──────┘
                                    │
                                    ▼
                            ┌──────────────┐
                            │ 外部MCP服务  │
                            └──────────────┘
```


---

### 🎯 学习路线规划

我建议按这个顺序来：
Function Calling（工具调用） - 最基础，理解Agent如何"行动"
ChatMemory（对话记忆） - 理解状态管理
RAG（检索增强） - 理解如何让LLM使用私有知识
Skill vs Command - 理解两种Prompt模板机制
SubAgent（子代理） - 理解记忆隔离
MCP协议 - 理解标准化连接外部服务