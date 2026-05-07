## 🧠 ChatMemory 核心原理

**为什么需要ChatMemory？**

LLM是无状态的，每次请求都是独立的。如果不保存历史，LLM就记不住之前说过什么。

```
第1轮：用户"我叫张三"
       LLM回复"你好张三"
       
第2轮：用户"我叫什么名字？"
       ❌ 如果没有记忆，LLM不知道
       ✅ 如果有记忆，LLM能看到第1轮的对话
```


---

### **ChatMemory的三层压缩策略**

这是这个项目最精妙的设计之一。我们逐层拆解。

#### **先看代码结构：**

[ChatMemory](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/core/ChatMemory.java)中有几个关键常量：

```java
private static final int MAIN_AGENT_MAX_ROUNDS = 20;        // 主Agent最大20轮
private static final int MAIN_AGENT_MAX_ASSISTANT = 3;      // 最多保留3条Assistant回复

private static final int COMPRESS_THRESHOLD_MESSAGES = 15;  // 超过15条消息触发摘要压缩
private static final int PRESERVE_RECENT_MESSAGES = 5;      // 压缩后保留最近5条
```


---

### **第一层：摘要压缩（Summary Compression）**

**触发条件：** 历史消息数 > 15条

**核心方法：** [compressIfNeeded()](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/core/ChatMemory.java#L133-L161)

```java
private void compressIfNeeded() {
    if (chatClient == null || history.size() <= COMPRESS_THRESHOLD_MESSAGES) {
        return;  // 消息不多，不需要压缩
    }

    // 计算压缩范围：保留最近5条，其余压缩
    int compressEndIndex = history.size() - PRESERVE_RECENT_MESSAGES;
    
    // ⚠️ 重要：确保不会在TOOL消息前面截断
    while (compressEndIndex < history.size()
            && history.get(compressEndIndex).getMessageType() == MessageType.TOOL) {
        compressEndIndex--;
    }

    if (compressEndIndex <= 0) return;

    // 取出需要压缩的消息
    List<Message> messagesToCompress = new ArrayList<>(history.subList(0, compressEndIndex));
    
    // 调用LLM生成摘要
    String newSummary = SummaryCompressor.compress(chatClient, messagesToCompress, summaryText);
    
    if (newSummary != null && !newSummary.isBlank()) {
        this.summaryText = newSummary;           // 保存新摘要
        history.subList(0, compressEndIndex).clear();  // 删除已压缩的消息
    }
}
```


**关键点解析：**

#### **1. 为什么要保护TOOL消息？**

消息序列可能是这样的：
```
1. USER: "查天气"
2. ASSISTANT: [调用get_weather工具]
3. TOOL: "杭州晴22°C"  ← 这个必须紧跟在ASSISTANT后面
4. ASSISTANT: "杭州今天晴天..."
```


如果在第2条和第3条之间截断，TOOL消息就失去了上下文，LLM会看不懂。

所以代码中这个循环：
```java
while (compressEndIndex < history.size()
        && history.get(compressEndIndex).getMessageType() == MessageType.TOOL) {
    compressEndIndex--;  // 往前退，避开TOOL消息
}
```


#### **2. 增量压缩机制**

看[SummaryCompressor.compress()](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/core/ChatMemory.java#L214-L247)：

```java
static String compress(ChatClient chatClient, List<Message> messagesToCompress, String existingSummary) {
    StringBuilder conversationText = new StringBuilder();

    // 如果已有旧摘要，先加入
    if (existingSummary != null && !existingSummary.isBlank()) {
        conversationText.append("【之前的对话摘要】\n").append(existingSummary).append("\n\n");
    }

    // 再加上新对话
    conversationText.append("【需要总结的新对话】\n");
    for (Message message : messagesToCompress) {
        conversationText.append(formatRole(message.getMessageType()))
                       .append(": ").append(message.getText()).append("\n");
    }

    // 让LLM合并总结
    String summary = chatClient.prompt(new Prompt(promptMessages)).call().content();
    return summary;
}
```


**这样做的好处：**
- 避免信息随多次压缩逐渐丢失
- 旧摘要 + 新对话 → 生成更完整的新摘要

**举个例子：**
```
第1次压缩：
  旧摘要：无
  新对话：用户问Java基础，助手解释了HashMap
  生成摘要："用户询问了Java HashMap的原理，助手解释了底层数据结构"

第2次压缩：
  旧摘要："用户询问了Java HashMap的原理..."
  新对话：用户问Spring自动配置，助手解释了@EnableAutoConfiguration
  生成摘要："用户先后询问了Java HashMap原理和Spring自动配置机制，
           助手分别解释了底层数据结构和@EnableAutoConfiguration注解的作用"
```


---

### **第二层：Assistant消息裁剪**

**触发条件：** 始终生效（在[getMessages()](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/core/ChatMemory.java#L92-L125)中）

```java
public List<Message> getMessages() {
    compressIfNeeded();  // 先执行第一层压缩
    
    List<Message> messages = new ArrayList<>();
    
    // 将system prompt和摘要合并
    if (systemMessage != null || (summaryText != null && !summaryText.isBlank())) {
        String systemContent = systemMessage != null ? systemMessage.getText() : "";
        if (summaryText != null && !summaryText.isBlank()) {
            systemContent += "\n\n【以下是之前对话的摘要，请参考】\n" + summaryText;
        }
        messages.add(new SystemMessage(systemContent));
    }

    // ⭐ 第二层：只保留最近3条Assistant回复
    long assistantCount = history.stream()
            .filter(msg -> msg.getMessageType() == MessageType.ASSISTANT)
            .count();
    
    long skipCount = Math.max(0, assistantCount - maxAssistantMessages);  // 需要跳过的数量
    long skipped = 0;

    for (Message msg : history) {
        if (msg.getMessageType() == MessageType.ASSISTANT && skipped < skipCount) {
            skipped++;
            continue;  // 跳过早期的Assistant消息
        }
        messages.add(msg);
    }

    return Collections.unmodifiableList(messages);
}
```


**为什么要裁剪Assistant消息？**

因为LLM的回复通常很长，是token消耗的大户。

**举例：**
```
USER: "解释一下HashMap"
ASSISTANT: "HashMap是Java中的...[500字详细解释]"

USER: "那HashSet呢？"
ASSISTANT: "HashSet基于HashMap实现...[500字详细解释]"

USER: "ArrayList呢？"
ASSISTANT: "ArrayList是...[500字详细解释]"

如果不裁剪，这3条Assistant回复就有1500字，非常占token。
只保留最近1条，能节省大量token。
```


**但这样不会丢失信息吗？**

不会！因为第一层的**摘要压缩**已经保留了关键信息。两层配合：
- 摘要压缩：保留语义信息
- Assistant裁剪：节省token

---

### **第三层：滑动窗口（兜底保护）**

**触发条件：** 消息总数 > maxRounds × 4

看[trimHistory()](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/core/ChatMemory.java#L168-L187)：

```java
private void trimHistory() {
    int maxMessages = maxRounds * 4;  // 主Agent: 20 × 4 = 80条
    if (history.size() <= maxMessages) {
        return;  // 没超限，不处理
    }

    int removeCount = history.size() - maxMessages;
    int actualRemoveCount = 0;
    
    for (int i = 0; i < removeCount && i < history.size(); i++) {
        // ⚠️ 同样保护TOOL消息
        MessageType nextType = (i + 1 < history.size()) 
                             ? history.get(i + 1).getMessageType() : null;
        if (MessageType.TOOL == nextType) {
            continue;  // 跳过，不删除这条
        }
        actualRemoveCount = i + 1;
    }

    if (actualRemoveCount > 0) {
        history.subList(0, actualRemoveCount).clear();  // 删除最早的消息
    }
}
```


**这是最后一道防线：**
- 即使前两层都失效了，这一层也能保证消息不会无限增长
- 直接丢弃最早的消息，简单粗暴但有效

---

### **三层策略协同工作图：**

```
用户持续对话...
    ↓
消息数 ≤ 15
    ↓ 不需要处理
    
消息数 > 15
    ↓
【第一层】摘要压缩触发
    ├─ 用LLM总结早期消息为摘要
    ├─ 摘要注入到SystemMessage
    └─ 删除已压缩的原始消息
    ↓
【第二层】Assistant裁剪（始终生效）
    ├─ 统计Assistant消息数量
    ├─ 只保留最近3条
    └─ 跳过早期的Assistant消息
    ↓
消息数 > 80 (20×4)
    ↓
【第三层】滑动窗口兜底
    ├─ 直接删除最早的消息
    └─ 确保不超过硬性上限
```


---

### **实际运行效果演示：**

假设用户和Agent进行了30轮对话：

**初始状态：**
```
history = [
  USER1, ASSISTANT1, USER2, ASSISTANT2, ..., USER30, ASSISTANT30
]  // 共60条消息
```


**调用getMessages()时：**

1. **第一层触发**（60 > 15）：
   ```
   压缩前55条消息 → 生成摘要
   summaryText = "用户询问了Java、Spring等多个技术问题..."
   history = [USER26, ASSISTANT26, ..., USER30, ASSISTANT30]  // 剩10条
   ```


2. **第二层生效**：
   ```
   统计Assistant消息：5条
   需要跳过：5 - 3 = 2条
   最终返回：[摘要+System, USER26, ASSISTANT26, USER28, ASSISTANT28, 
            USER29, ASSISTANT29, USER30, ASSISTANT30]
   ```


3. **第三层未触发**（10 < 80）

---

### **SubAgent为什么不启用摘要压缩？**

看[ChatMemory.forSubAgent()](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/core/ChatMemory.java#L68-L70)：

```java
public static ChatMemory forSubAgent() {
    return new ChatMemory(SUB_AGENT_MAX_ROUNDS, SUB_AGENT_MAX_ASSISTANT, null);
    //                                                                    ^^^^
    //                                                                chatClient = null
}
```


传入`null`，所以在`compressIfNeeded()`中：
```java
if (chatClient == null || ...) {
    return;  // 直接返回，不压缩
}
```


**原因：**
- SubAgent生命周期短（完成子任务就销毁）
- 默认只有10轮对话，不太可能超限
- 避免额外的LLM调用开销

---

### **✅ ChatMemory总结：**

摘要压缩是把history的前一段和oldSummary继续压缩为newSummary。then history = history.subList(保留部分)。

Assistant是先尝试执行摘要压缩，然后接着把systemPrompt、summary以及当前history中的最近几条大模型的回复加到messages（用于构建新轮对话的prompt）中，然后再结合用户新输入的问题（其实一开始就把用户新问题加到history中了，然后再压缩），一起打包给llm，开启新一轮对话。

滑动窗口是用户每提问一个问题，系统会自动执行addhistory（问题），然后就执行一次滑动窗口压缩。

| 层级              | 触发条件  | 作用                        | 代价            |
| ----------------- | --------- | --------------------------- | --------------- |
| **摘要压缩**      | 消息>15条 | 用LLM总结早期对话，保留语义 | 额外一次LLM调用 |
| **Assistant裁剪** | 始终生效  | 只保留最近3条Assistant回复  | 无              |
| **滑动窗口**      | 消息>80条 | 直接删除最早的消息          | 无              |

**设计亮点：**
1. **内聚透明**：压缩逻辑封装在`getMessages()`内部，调用方无感知
2. **增量压缩**：旧摘要+新对话合并，避免信息丢失
3. **TOOL消息保护**：确保工具调用上下文完整
4. **分层协作**：摘要优先（保信息）→ 裁剪持续（省token）→ 窗口兜底（硬保护）

---

## 🎯 验证理解

**场景：** 用户和Agent进行了50轮深度技术讨论，每轮Assistant回复都很详细（平均300字）。

**问题1：** 此时history中大概有多少条消息？

<details>
<summary>点击查看答案</summary>

50轮对话 = 50条USER + 50条ASSISTANT = **100条消息**

</details>

---

**问题2：** 调用getMessages()后，会发生什么？

<details>
<summary>点击查看答案</summary>

1. **第一层触发**（100 > 15）：
   - 压缩前95条消息（保留最近5条）
   - 生成摘要："用户深入讨论了Java并发、JVM调优、Spring事务等主题..."
   - history剩下5条

2. **第二层生效**：
   - 假设剩下5条中有3条ASSISTANT
   - 3 ≤ 3，不需要跳过
   - 全部保留

3. **第三层未触发**（5 < 80）

最终返回：[System+摘要, 最近5条消息]

</details>

---

**问题3：** 如果我把`COMPRESS_THRESHOLD_MESSAGES`从15改成5，会有什么影响？

<details>
<summary>点击查看答案</summary>

**优点：**
- 更早触发压缩，节省更多token
- 适合长对话场景

**缺点：**

- 频繁调用LLM做摘要，增加成本和延迟
- 可能丢失一些细节信息（因为更早被压缩了）

**建议：**
- 短对话场景（<20轮）：保持15或更高
- 长对话场景（>50轮）：可以降低到10左右

</details>

## 🎯 getMessages() 的触发时机

结论：被动触发，由 AgentCore 在每次对话时主动调用。

### 触发链路：

```
用户发起对话
    ↓
AgentCore.chat(sessionId, userInput)
    ↓
【第1步】memory.addMessage(new UserMessage(userInput))  // 添加用户消息
    ↓
【第2步】List<Message> messages = memory.getMessages()  // ⭐ 这里触发！
    ↓
【第3步】Prompt prompt = new Prompt(messages, ...)
    ↓
【第4步】chatClient.prompt(prompt).call()  // 发送给LLM
```

------

在大型语言模型（LLM）的长期记忆（Long-term Memory）管理中，**CharMemory** 是一种为了解决上下文窗口（Context Window）限制而设计的内存管理框架。它的核心逻辑是参考了人类大脑的记忆机制，通过**三层压缩策略**（Three-Layer Compression Strategy）来平衡记忆的“深度”与“广度”。

### 1. 核心结论

CharMemory 通过 **原始上下文（Raw Context）**、**精简摘要（Condensed Summary）** 和 **外部知识库（External Vector Database）** 这三层结构，实现从“细节”到“精炼”再到“索引”的逐级压缩，确保模型既能保留当前对话的细节，又能检索到很久以前的关键信息。

------

### 2. 整体主线

这套策略本质上是一个**信息漏斗**。随着对话增加，较旧的信息会经历：

**保留原貌 -> 提取关键点 -> 向量化存储**。

------

### 3. 逐项展开解释

#### 第一层：工作记忆层（Working Memory / Raw Buffer）

- **做法：** 这一层不进行任何压缩，直接保留最近的对话原件（Tokens）。
- **为什么：** LLM 处理当前任务需要极其精确的上下文（如代码变量名、语气语境）。
- **触发机制：** 采用 **FIFO（先进先出）队列**。当对话长度超过设定阈值（例如 2k Tokens）时，最旧的消息会被推向下一层。

#### 第二层：短期记忆压缩层（Short-term Compression / Summary Layer）

- **做法：** 当第一层溢出时，系统会调用 LLM 对这部分“即将过期”的原始文本进行**语义抽象**。

- **代码思路逻辑：**

  Python

  ```
  # 伪代码示例：将过期的 Buffer 转化为摘要
  def compress_to_summary(raw_buffer_segment):
      prompt = f"请精炼以下对话的核心事实和用户偏好，剔除废话：{raw_buffer_segment}"
      summary = llm.generate(prompt)
      return summary
  ```

- **为什么：** 摘要能以 10% 甚至更少的 Token 消耗保留 80% 的关键信息。它作为“中间状态”存在于当前的上下文窗口中。

#### 第三层：长期记忆层（Long-term Memory / Vector Storage）

- **做法：** 当摘要层也过长，或者某些信息极其陈旧时，系统将其转化为**向量编码（Embedding）**，存入外部数据库。
- **为什么：** 这是为了实现“无限记忆”。模型不再把这些内容直接塞进 Prompt，而是通过 **RAG（检索增强生成）** 机制，只有在当前对话触碰到相关关键词时，才从数据库里“捞”出来。

------

### 4. 协同工作机制：动态检索与遗忘

CharMemory 不仅仅是死板的压缩，它还涉及到一个关键操作：**记忆召回（Memory Retrieval）**。

1. **输入分析：** 用户提问时，系统提取关键词。
2. **多级匹配：**
   - 首先匹配**第一层**（最近说了什么？）。
   - 如果找不到，查询**第二层**摘要。
   - 最后对**第三层**数据库进行相似度检索（Top-K 检索）。
3. **结果重组：** 将检索到的长期记忆与当前的短期记忆拼接，形成最终交给模型的 Prompt。

------

### 5. 总结

CharMemory 的三层压缩策略实际上是在做**信息密度（Information Density）的阶梯式提升**：

- **第一层保真度最高**，负责处理当下的逻辑。
- **第二层负责承上启下**，减小显存压力。
- **第三层负责海量存储**，实现跨越时间的知识关联。

一句话串联：**CharMemory 通过“原文缓存、摘要提炼、向量归档”三位一体的流程，让 AI 既能‘目不转睛’地盯着当前的对话细节，又能‘念念不忘’历史的久远信息。**
