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

