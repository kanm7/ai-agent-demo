好的！我们按模块逐个深入，我会带你从最简单的开始，逐步掌握每个核心概念。

## 🎯 学习路线规划

我建议按这个顺序来：

1. **Function Calling（工具调用）** - 最基础，理解Agent如何"行动"
2. **ChatMemory（对话记忆）** - 理解状态管理
3. **RAG（检索增强）** - 理解如何让LLM使用私有知识
4. **Skill vs Command** - 理解两种Prompt模板机制
5. **SubAgent（子代理）** - 理解记忆隔离
6. **MCP协议** - 理解标准化连接外部服务

---

## 📌 第一步：Function Calling（工具调用）

这是Agent最核心的能力，我们先从这里开始。

### **核心问题：**
LLM本身不会真的去查天气、搜数据库，它只能"说"想调用哪个工具。**真正执行工具的是你的Java代码**。

### **让我们看一个具体例子：**

假设用户问："杭州今天天气怎么样？"

**整个流程分为4个阶段：**

#### **阶段1：LLM决定调用工具**
```
LLM返回的不是文本，而是一个JSON结构：
{
  "tool_calls": [
    {
      "name": "get_weather",
      "arguments": {"city": "杭州"}
    }
  ]
}
```


#### **阶段2：Spring AI自动执行工具**
Spring AI的`ToolCallAdvisor`检测到有tool_calls，会自动：
1. 找到名为`get_weather`的工具
2. 解析参数`{"city": "杭州"}`
3. 调用对应的Java方法

#### **阶段3：将结果返回给LLM**
```
工具执行结果："杭州，晴，22°C"

这个结果会被包装成一条TOOL类型的消息，再次发送给LLM
```


#### **阶段4：LLM生成最终回复**
```
LLM看到工具返回的结果后，生成自然语言回复：
"杭州今天天气晴朗，气温22°C，适合出行。"
```


---

### **现在看你的项目是如何实现的：**

#### **1. 定义工具的接口**

所有工具都要实现[InnerTool](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/service/tool/InnerTool.java)接口：

```java
public interface InnerTool {
    List<ToolCallback> loadToolCallbacks();
}
```


**为什么这样设计？**
- 统一入口：启动时Spring扫描所有InnerTool Bean，自动收集所有工具
- 插件化：新增工具只需实现这个接口，无需修改AgentCore代码

#### **2. 构建工具回调**

看[RagTool](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/service/tool/impl/RagTool.java)的实现：

```java
@Override
public List<ToolCallback> loadToolCallbacks() {
    Map<String, Map<String, String>> properties = Map.of(
        "question", Map.of("type", "string", "description", "需要从知识库中检索的问题")
    );

    return Collections.singletonList(ToolCallbackBuilder.build(
        "knowledge_search",                              // 工具名
        "从知识库中检索与问题相关的信息...",               // 工具描述
        properties,                                       // 参数定义（JSON Schema）
        List.of("question"),                             // 必填参数列表
        this::execute                                    // 执行函数
    ));
}

private String execute(String argumentsJson) {
    JSONObject args = JSON.parseObject(argumentsJson);
    String question = args.getString("question");
    return ragService.query(question);  // 真正执行业务逻辑
}
```


**关键点解析：**

| 参数         | 作用                                               | 示例                                    |
| ------------ | -------------------------------------------------- | --------------------------------------- |
| **工具名**   | LLM通过这个名字识别工具                            | `"knowledge_search"`                    |
| **工具描述** | 告诉LLM这个工具是干什么的，LLM根据描述决定是否调用 | `"从知识库中检索..."`                   |
| **参数定义** | JSON Schema格式，告诉LLM需要传什么参数             | `{"question": {"type": "string", ...}}` |
| **必填参数** | 哪些参数必须提供                                   | `List.of("question")`                   |
| **执行函数** | 工具被调用时实际执行的Java方法                     | `this::execute`                         |

**重要理解：**
- **工具描述是给LLM看的**，不是给人看的！LLM会根据描述判断何时调用这个工具
- **参数定义也是给LLM看的**，LLM会根据Schema生成符合格式的JSON参数
- **execute方法接收的是JSON字符串**，因为LLM返回的参数就是JSON格式

---

### **动手实践建议：**

现在请你打开[GetWeatherTool.java](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/service/tool/impl/GetWeatherTool.java)，我们一起看看这个简单工具的实现。




**已查看文件** *GetWeatherTool.java*


完美！这是一个非常清晰的示例。

### **GetWeatherTool 代码解析：**

```java
// 1. 定义参数Schema（告诉LLM需要什么参数）
Map<String, Map<String, String>> properties = Map.of(
    "location", Map.of("type", "string", "description", "城市名称"),
    "date", Map.of("type", "string", "description", "日期，格式 yyyy-MM-dd")
);

// 2. 构建ToolCallback
ToolCallbackBuilder.build(
    "get_weather",                    // 工具名
    "获取指定城市某一天的天气信息",     // 描述（LLM根据这个决定是否调用）
    properties,                       // 参数定义
    List.of("location", "date"),     // 两个参数都是必填的
    this::execute                     // 执行函数
);

// 3. 真正执行的逻辑
private String execute(String argumentsJson) {
    JSONObject args = JSON.parseObject(argumentsJson);
    String location = args.getString("location");  // "杭州"
    String date = args.getString("date");          // "2026-04-22"
    return location + " " + date + " 的天气是：晴天，气温 22°C";
}
```


---

### **现在请你思考几个问题：**

**问题1：** 如果用户问"北京明天天气怎么样？"，LLM会如何决定调用这个工具？

<details>
<summary>点击查看答案解析</summary>

LLM看到工具的description是"获取指定城市某一天的天气信息"，当用户问天气问题时，LLM会判断：
- 这是在询问天气 → 需要调用get_weather工具
- 从用户输入中提取参数：location="北京", date="明天的日期"
- 返回tool_calls结构，让Spring AI执行工具

</details>

---

**问题2：** 为什么`execute`方法接收的是`String argumentsJson`而不是直接接收`String location, String date`？

<details>
<summary>点击查看答案解析</summary>

因为LLM返回的参数是JSON格式：
```json
{"location": "北京", "date": "2026-04-23"}
```


Spring AI会将这个JSON字符串原样传递给execute方法，由开发者自己解析。这样做的好处是：
- 灵活性高：可以处理任意复杂的参数结构（嵌套对象、数组等）
- 统一接口：所有工具的execute方法签名一致

</details>

---

**问题3：** 如果我把`List.of("location", "date")`改成`List.of("location")`（只标记location为必填），会发生什么？

<details>
<summary>点击查看答案解析</summary>

LLM在生成参数时：
- location是必填的，一定会提供
- date变成可选的，LLM可能不提供这个参数

在execute方法中，如果用户没提供date：
```java
String date = args.getString("date");  // 可能为null
```


你需要在代码中处理这种情况，比如给默认值：
```java
String date = args.getString("date");
if (date == null) {
    date = LocalDate.now().toString();  // 默认为今天
}
```


</details>

---

### **✅ 第一个知识点总结：**

Function Calling的核心就是：
1. **注册工具**：通过InnerTool接口，定义工具名、描述、参数、执行函数
2. **LLM决策**：LLM根据工具描述决定是否调用，并生成JSON参数
3. **服务端执行**：Spring AI自动调用execute方法，传入JSON字符串
4. **结果返回**：将执行结果再次发送给LLM，生成最终回复

---

## 🎯 下一步选择

现在你已经理解了Function Calling的基本原理，接下来你想：

**A. 深入理解ToolCallAdvisor的ReAct循环**（看看Spring AI是如何自动处理多轮工具调用的）

**B. 自己动手写一个新工具**（比如股票查询、计算器等，巩固理解）

**C. 进入下一个模块：ChatMemory对话记忆**

**D. 先启动项目实际体验一下**（看看工具调用在浏览器中是什么效果）

你想选哪个？或者有其他疑问也可以直接问我！