好的！我们把所有模块串起来，看整个Agent系统是如何协作的。

## 🏗️ AI Agent 完整架构全景图

---

## **一、核心组件关系图**

```
┌─────────────────────────────────────────────────────┐
│                  用户界面 (Web/API)                   │
└──────────────────┬──────────────────────────────────┘
                   │ HTTP请求
                   ▼
┌─────────────────────────────────────────────────────┐
│              Controller 层 (接口入口)                  │
│  ┌──────────┐  ┌──────────┐  ┌──────────────────┐  │
│  │ChatCtrl  │  │Command   │  │ManagerCtrl       │  │
│  │          │  │Ctrl      │  │(模型/MCP管理)     │  │
│  └────┬─────┘  └────┬─────┘  └────────┬─────────┘  │
└───────┼──────────────┼────────────────┼─────────────┘
        │              │                │
        ▼              │                │
┌──────────────────────┼────────────────┼─────────────┐
│                      ▼                ▼             │
│           AgentCore (核心编排器)                      │
│  ┌──────────────────────────────────────────────┐  │
│  │ 1. 意图识别 (IntentRecognizer)               │  │
│  │ 2. RAG注入 (RagService)                      │  │
│  │ 3. 记忆管理 (ChatMemory)                     │  │
│  │ 4. 工具调用 (ToolCallbacks + ReAct循环)      │  │
│  └──────────────────┬───────────────────────────┘  │
│                     │                               │
│  ┌──────────────────┴───────────────────────────┐  │
│  │         ToolCallback 列表                     │  │
│  │  ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐        │  │
│  │  │RAG │ │Skill│ │SubA│ │MCP │ │其他│        │  │
│  │  │Tool│ │Tool│ │gent│ │Tool│ │Tool│        │  │
│  │  └──┬─┘ └──┬─┘ └──┬─┘ └──┬─┘ └──┬─┘        │  │
│  └─────┼──────┼──────┼──────┼──────┼───────────┘  │
└────────┼──────┼──────┼──────┼──────┼──────────────┘
         │      │      │      │      │
    ┌────┴──┐ ┌─┴────┐ ┌─┴────┐ ┌─┴────┐ ┌───────┐
    │RAG    │ │Skill │ │SubAg │ │MCP   │ │其他   │
    │服务   │ │管理器│ │ent   │ │Client│ │工具   │
    │       │ │      │ │Manager│ │     │ │       │
    └───┬───┘ └──┬───┘ └───┬──┘ └──┬──┘ └───┬───┘
        │        │         │       │        │
        ▼        ▼         ▼       ▼        ▼
   ┌────────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐
   │向量库  │ │LLM   │ │独立  │ │远程  │ │本地  │
   │分块    │ │调用  │ │记忆  │ │MCP   │ │逻辑  │
   └────────┘ └──────┘ └──────┘ └──────┘ └──────┘
```


---

## **二、一次完整对话的生命周期**

### **场景：用户问"Spring Boot的自动配置原理是什么？帮我总结一下"**

#### **阶段1：请求进入**

```
用户输入 → ChatController.chat()
    ↓
POST /api/chat
{
  "message": "Spring Boot的自动配置原理是什么？帮我总结一下",
  "sessionId": "user-001"
}
```


---

#### **阶段2：AgentCore 编排**

[AgentCore.chat()](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/core/AgentCore.java#L197-L232)执行：

```java
public String chat(String sessionId, String userInput) {
    // 1. 获取或创建会话记忆
    ChatMemory memory = getOrCreateMemory(sessionId);
    
    // 2. ⭐ 意图识别
    Intent intent = intentRecognizer.recognize(userInput);
    // → 返回 Intent.RAG（因为涉及Spring Boot技术知识）
    
    // 3. ⭐ RAG注入（如果是RAG意图）
    if (intent == Intent.RAG && ragService.isKnowledgeLoaded()) {
        String ragContext = ragService.query(userInput);
        // → 检索知识库，返回相关资料
        
        String enrichedInput = "以下是参考资料...\n\n" 
                             + ragContext + "\n\n用户问题：" + userInput;
        memory.addMessage(new UserMessage(enrichedInput));
    } else {
        memory.addMessage(new UserMessage(userInput));
    }
    
    // 4. ⭐ 获取消息列表（触发ChatMemory压缩）
    List<Message> messages = memory.getMessages();
    
    // 5. 构建Prompt
    Prompt prompt = new Prompt(messages, buildChatOptions());
    
    // 6. ⭐ 注册工具回调
    ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt(prompt);
    if (!toolCallbacks.isEmpty()) {
        requestSpec.toolCallbacks(toolCallbacks.toArray(new ToolCallback[0]));
        // → 包含: RagTool, SkillTool, SubAgentTool, McpTool, GetWeatherTool...
    }
    
    // 7. ⭐ 调用LLM（触发ReAct循环）
    String response = requestSpec.call().content();
    
    // 8. 保存助手回复
    memory.addMessage(new AssistantMessage(response));
    
    return response;
}
```


---

#### **阶段3：LLM 决策**

LLM收到的Prompt包含：
- System Message：角色定义
- User Message：用户问题 + RAG检索到的资料
- Tools：所有可用工具的定義

**LLM分析：**
```
用户问的是Spring Boot自动配置原理，并且要求"总结"

我看到：
1. 已经有RAG检索到的资料在上下文中
2. 有一个skill叫"summarize"，description是"对文本进行摘要总结"

决策：
- 可以直接基于RAG资料回答
- 或者调用summarize技能生成更结构化的总结

→ LLM决定调用summarize技能
```


**LLM返回：**好的！我们把所有模块串起来，看它们如何协作构成一个完整的AI Agent系统。

---

## 🏗️ 整体架构全景图

```
┌─────────────────────────────────────────────────────┐
│                  用户界面层                           │
│  Web前端 (index.html) / REST API                     │
└──────────────────┬──────────────────────────────────┘
                   │ HTTP请求
                   ▼
┌─────────────────────────────────────────────────────┐
│              Controller 层                            │
│  ChatController / CommandController / ManagerController│
└──────────────────┬──────────────────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────────────────┐
│           AgentCore (核心编排器)                       │
│                                                       │
│  ┌──────────┐                                        │
│  │意图识别   │ → 判断是否需要RAG                      │
│  └────┬─────┘                                        │
│       │                                               │
│  ┌────▼─────┐                                        │
│  │RAG注入    │ → 检索知识库，拼接上下文                │
│  └────┬─────┘                                        │
│       │                                               │
│  ┌────▼──────────────────────────────┐               │
│  │     ChatMemory (记忆管理)          │               │
│  │  - 摘要压缩                        │               │
│  │  - Assistant裁剪                   │               │
│  │  - 滑动窗口                        │               │
│  └────┬──────────────────────────────┘               │
│       │                                               │
│  ┌────▼──────────────────────────────┐               │
│  │  ChatClient + ToolCallbacks       │               │
│  │  (ReAct循环：LLM ↔ Tools)         │               │
│  └────┬──────────────────────────────┘               │
└───────┼──────────────────────────────────────────────┘
        │
   ┌────┴────────────────┬───────────────┬──────────┐
   ▼                     ▼               ▼          ▼
┌────────┐      ┌──────────────┐ ┌──────────┐ ┌────────┐
│ RAG    │      │ Skill/Command│ │SubAgent  │ │ MCP    │
│ Tool   │      │ Tools        │ │ Tools    │ │ Client │
└───┬────┘      └──────┬───────┘ └────┬─────┘ └───┬────┘
    │                  │              │           │
    ▼                  ▼              ▼           ▼
┌────────┐      ┌──────────────┐ ┌──────────┐ ┌────────┐
│RAG服务 │      │SkillManager  │ │SubAgent  │ │外部MCP │
│-分块   │      │-扫描.md文件  │ │Manager   │ │Server  │
│-向量   │      │-解析Front    │ │-创建     │ │-GitHub │
│-检索   │      │-注册为Tool   │ │-对话     │ │-Slack  │
│-重排   │      └──────────────┘ │-销毁     │ └────────┘
└────────┘                       └──────────┘
```


---

## **一、一次完整对话的完整链路**

### **场景：用户问"Spring Boot的自动配置原理是什么？帮我总结一下"**

#### **阶段1：接收请求**

```
用户输入 → ChatController.chat()
    ↓
调用 agentCore.chat(sessionId, userInput)
```


---

#### **阶段2：意图识别**

[AgentCore.chat()](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/core/AgentCore.java#L197-L232)执行：

```java
// 1. 获取或创建会话记忆
ChatMemory memory = getOrCreateMemory(sessionId);

// 2. ⭐ 意图识别
Intent intent = intentRecognizer.recognize(userInput);
```


[IntentRecognizer](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/core/IntentRecognizer.java)内部：
```java
// 让LLM判断：这是技术问题还是闲聊？
String result = chatClient.prompt()
    .user("你是一个意图分类器...用户输入：Spring Boot的自动配置原理是什么？")
    .call().content();

// 返回 "RAG"
return Intent.RAG;
```


**为什么是RAG？**
- 问题涉及Spring Boot技术知识
- 知识库中有相关文档
- 需要从知识库检索最新、最准确的信息

---

#### **阶段3：RAG检索与注入**

```java
// 3. 如果是RAG意图，检索知识库
if (intent == Intent.RAG && ragService.isKnowledgeLoaded()) {
    String ragContext = ragService.query(userInput);
    
    // 将检索结果拼接到用户输入中
    String enrichedInput = "以下是从知识库中检索到的相关参考资料，"
            + "请结合这些资料回答用户的问题：\n\n"
            + ragContext + "\n\n用户问题：" + userInput;
    
    memory.addMessage(new UserMessage(enrichedInput));
}
```


[RagService.query()](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/service/rag/RagService.java#L121-L144)内部执行：

```
1. 多路召回（9个候选）
   ├─ SemanticRetriever: 向量检索 → 找到"Spring Boot自动配置机制"
   ├─ Bm25Retriever: 关键词匹配 → 找到包含"自动配置"的文档
   └─ QueryRewriteRetriever: 改写后检索 → 扩大覆盖面

2. RRF融合 → 合并三路结果

3. Rerank重排 → 从9个中选最相关的3个

4. 拼接上下文 → 返回格式化的参考资料
```


**ragContext的内容：**
```
【参考资料 1】
Spring Boot的自动配置基于@EnableAutoConfiguration注解，
通过SpringFactoriesLoader加载META-INF/spring.factories中的配置类...

【参考资料 2】
自动配置的核心是条件注解（@Conditional），如@ConditionalOnClass、
@ConditionalOnMissingBean等，确保只在满足条件时才生效...

【参考资料 3】
Starter机制将常用的依赖和自动配置打包，简化项目搭建...
```


---

#### **阶段4：构建Prompt并调用LLM**

```java
// 4. 获取消息列表（触发ChatMemory的三层压缩）
List<Message> messages = memory.getMessages();

// 5. 构建Prompt
Prompt prompt = new Prompt(messages, buildChatOptions());

// 6. ⭐ 注册所有工具
ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt(prompt);
if (!toolCallbacks.isEmpty()) {
    requestSpec.toolCallbacks(toolCallbacks.toArray(new ToolCallback[0]));
}

// 7. 调用LLM（可能触发ReAct循环）
String response = requestSpec.call().content();

// 8. 保存助手回复到记忆
memory.addMessage(new AssistantMessage(response));
```


**发送给LLM的完整Prompt：**
```
System: 你是一个智能助手，具备知识库检索、工具调用、技能执行等能力...

User: 以下是从知识库中检索到的相关参考资料，请结合这些资料回答用户的问题：

【参考资料 1】
Spring Boot的自动配置基于@EnableAutoConfiguration注解...

【参考资料 2】
自动配置的核心是条件注解...

【参考资料 3】
Starter机制将常用的依赖和自动配置打包...

用户问题：Spring Boot的自动配置原理是什么？帮我总结一下
```


**同时发送的工具列表（tools字段）：**
```json
{
  "tools": [
    {"name": "knowledge_search", "description": "从知识库中检索..."},
    {"name": "summarize", "description": "对文本进行摘要总结"},
    {"name": "create_sub_agent", "description": "创建子代理..."},
    {"name": "chat_with_sub_agent", "description": "与子代理对话..."},
    {"name": "destroy_sub_agent", "description": "销毁子代理..."},
    {"name": "get_weather", "description": "获取天气信息"},
    // ... 其他工具（包括MCP动态连接的工具）
  ]
}
```


---

#### **阶段5：LLM决策**

LLM分析：
```
1. 用户问题是关于Spring Boot自动配置的
2. Prompt中已经包含了从知识库检索到的相关资料
3. 用户要求"总结一下"
4. 我看到有一个工具叫 summarize，description是"对文本进行摘要总结"
5. 但我已经有足够的信息了，可以直接回答
→ 不需要调用任何工具，直接生成回复
```


**LLM返回：**
```json
{
  "choices": [{
    "message": {
      "role": "assistant",
      "content": "Spring Boot的自动配置原理如下：\n\n1. **核心注解**：@EnableAutoConfiguration...\n2. **条件装配**：通过@Conditional系列注解...\n3. **Starter机制**：将常用依赖打包...\n\n总结：自动配置通过约定优于配置的理念，简化了Spring应用的搭建。"
    }
  }]
}
```


---

#### **阶段6：返回结果**

```java
return response;  // 返回给Controller
    ↓
Controller返回JSON给前端
    ↓
前端展示给用户
```


---

## **二、如果LLM决定调用工具会怎样？**

### **场景变化：用户说"帮我用summarize技能总结这段代码"**

#### **阶段5变化：LLM决定调用Skill**

LLM分析：
```
用户明确要求使用summarize技能
我看到有一个工具叫 summarize
→ 我应该调用这个工具
```


**LLM返回：**
```json
{
  "tool_calls": [{
    "name": "summarize",
    "arguments": {"text": "public class UserService {...}"}
  }]
}
```


#### **ReAct循环第1轮：执行Skill**

Spring AI检测到tool_calls：
```
1. 找到summarize对应的ToolCallback（由SkillTool注册）
2. 解析参数：{"text": "public class UserService {...}"}
3. 执行SkillTool的execute方法
```


[SkillTool](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/service/tool/impl/SkillTool.java)内部：
```java
// 替换prompt中的{{text}}占位符
String prompt = definition.promptTemplate()
    .replace("{{text}}", "public class UserService {...}");

// 调用LLM执行总结
String summary = chatClient.prompt().user(prompt).call().content();

return summary;  // 返回总结结果
```


#### **ReAct循环第2轮：LLM生成最终回复**

Spring AI将工具结果再次发送给LLM：
```
【之前的对话】
USER: "帮我用summarize技能总结这段代码"
ASSISTANT: [调用了summarize工具]
TOOL: "UserService是一个用户服务类，负责用户的增删改查操作..."

【请基于工具结果生成回复】
```


LLM生成：
```
"我已经使用summarize技能对代码进行了总结：

UserService是一个用户服务类，主要负责用户的增删改查操作。
该类采用了标准的分层架构设计..."
```


**退出循环**（没有新的tool_calls），返回最终结果。

---

## **三、各模块的协作关系**

### **1. AgentCore ↔ ChatMemory**
- AgentCore负责调用`memory.addMessage()`和`memory.getMessages()`
- ChatMemory内部管理压缩逻辑，对AgentCore透明

### **2. AgentCore ↔ RAG**
- AgentCore通过`intentRecognizer`判断是否需要RAG
- 如果需要，调用`ragService.query()`获取参考资料
- 将参考资料拼接到Prompt中

### **3. AgentCore ↔ Tools**
- AgentCore维护`toolCallbacks`列表
- 启动时从所有`InnerTool` Bean收集工具
- 运行时可通过API动态添加/移除工具（如MCP）
- 每次对话时将工具列表传递给ChatClient

### **4. Tools ↔ 具体实现**
- **RagTool**: 封装RAG检索能力
- **SkillTool**: 将Skill注册为Tool
- **SubAgentTool**: 提供SubAgent的创建/对话/销毁
- **McpTool**: 启动时恢复持久化的MCP连接
- **GetWeatherTool等**: 简单的示例工具

### **5. SubAgent ↔ 主Agent**
- SubAgent通过3个Tool暴露给主Agent
- 主LLM自主决定何时创建SubAgent
- SubAgent拥有独立记忆，不污染主对话

### **6. MCP Client ↔ 外部服务**
- 连接外部MCP Server
- 自动发现远程工具
- 转换为本地ToolCallback
- 对LLM来说，远程工具和本地工具没有区别

---

## **四、数据流向总结**

```
用户输入
    ↓
【意图识别】→ 决定是否需要RAG
    ↓
【RAG检索】→ 获取相关知识（如果需要）
    ↓
【记忆管理】→ 获取历史对话 + 摘要
    ↓
【构建Prompt】→ System + History + 当前输入 + RAG上下文
    ↓
【传递工具列表】→ tools字段发送给LLM
    ↓
【LLM决策】→ 直接回答 or 调用工具
    ↓
    ├─ 直接回答 → 返回结果
    │
    └─ 调用工具 → 【ReAct循环】
           ├─ Spring AI执行工具
           ├─ 获取工具结果
           ├─ 将结果发送给LLM
           └─ LLM生成最终回复
    ↓
【保存记忆】→ 将助手回复存入ChatMemory
    ↓
返回给用户
```


---

## **五、关键设计模式总结**

| 设计模式       | 应用场景                    | 优势                   |
| -------------- | --------------------------- | ---------------------- |
| **策略模式**   | 多种分块策略、多种召回策略  | 可插拔，易于扩展       |
| **工厂模式**   | ToolCallbackBuilder构建工具 | 统一创建逻辑           |
| **装饰器模式** | MCP工具的日志包装           | 不修改原代码，添加功能 |
| **观察者模式** | InnerTool自动加载           | Spring自动扫描注册     |
| **代理模式**   | SubAgent通过Tool暴露        | 隐藏实现细节           |
| **模板方法**   | RagService的query流程       | 固定流程，可变细节     |

---

## **六、核心技术栈映射**

| 功能         | 技术实现                            |
| ------------ | ----------------------------------- |
| **LLM调用**  | Spring AI + OpenAI兼容接口          |
| **向量嵌入** | EmbeddingModel（智谱embedding-3）   |
| **向量存储** | 内存VectorStore（生产用Milvus）     |
| **工具调用** | Spring AI Function Calling          |
| **对话记忆** | 自定义ChatMemory（三层压缩）        |
| **MCP协议**  | spring-ai-starter-mcp-server-webmvc |
| **Web框架**  | Spring Boot + WebMVC                |
| **异步流式** | Reactor Flux（SSE）                 |

---

## **七、学习路径回顾**

我们已经学习了：

✅ **Function Calling** - LLM如何调用工具  
✅ **ReAct循环** - 多轮工具调用的自动化  
✅ **ChatMemory** - 三层压缩策略管理对话历史  
✅ **RAG** - 检索增强生成的完整流水线  
✅ **Skill vs Command** - 两种Prompt模板机制  
✅ **SubAgent** - 记忆隔离的子代理  
✅ **MCP** - 标准化连接外部服务  
✅ **整体架构** - 所有模块如何协作  

---

## **🎯 你现在已经掌握了：**

1. **Agent的核心原理**：LLM + 记忆 + 工具 + 规划
2. **每个模块的职责**：知道什么功能在哪个类中实现
3. **数据流转过程**：从用户输入到最终回复的完整链路
4. **关键设计决策**：为什么这样设计，有什么优势

---

## **💡 下一步建议**

如果你想进一步深入：

1. **动手实践**：
   - 启动项目，实际体验各个功能
   - 添加一个新工具（如计算器）
   - 创建一个新的Skill
   - 连接一个公开的MCP Server

2. **源码阅读**：
   - 深入Spring AI的ToolCallAdvisor实现
   - 研究RRF算法的数学原理
   - 理解MCP协议的详细规范

3. **优化改进**：
   - 将内存VectorStore替换为Milvus
   - 添加更多分块策略
   - 实现SubAgent的自动销毁（超时检测）

---

还有其他疑问吗？或者你想深入了解某个具体模块的实现细节？