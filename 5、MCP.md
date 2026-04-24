好的！我们进入**MCP（Model Context Protocol）**模块。这是让AI应用能够标准化连接外部服务的协议。

## 🔌 MCP 核心概念

**MCP解决的问题：**
- 每个AI应用都有自己的工具集成方式，互不兼容
- 想让多个AI应用共享同一个数据源/工具，需要重复开发

**MCP的解决方案：**
定义一个标准协议，让AI应用（Client）能够统一的方式连接外部服务（Server）。

**类比理解：**
```
没有MCP：
  ChatGPT → 自己实现GitHub集成
  Claude → 自己实现GitHub集成
  你的Agent → 自己实现GitHub集成
  ❌ 重复造轮子

有MCP：
  GitHub提供标准的MCP Server
  ChatGPT、Claude、你的Agent → 都通过MCP协议连接
  ✅ 一次开发，处处可用
```


---

## **一、双向支持架构**

你的项目同时实现了**MCP Server**和**MCP Client**两种角色。

### **角色1：MCP Server（对外暴露能力）**

让你的Agent的能力可以被其他AI应用调用。

看[SimpleMcpServer](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/api/mcpserver/SimpleMcpServer.java)：

```java
@Component
public class SimpleMcpServer {

    private final RagService ragService;

    public SimpleMcpServer(RagService ragService) {
        this.ragService = ragService;
    }

    // ⭐ 用@Tool注解标记这是一个MCP工具
    @Tool(name = "knowledge_query", 
          description = "查询内部知识库，根据关键词和分类检索相关技术文档内容")
    public String knowledgeQuery(
            @ToolParam(description = "检索关键词") String keyword,
            @ToolParam(description = "知识分类") String category,
            @ToolParam(description = "返回的最大结果条数") int maxResults
    ) {
        // 1. 参数校验
        if (keyword == null || keyword.isBlank()) {
            return "错误：检索关键词不能为空";
        }

        // 2. 格式化查询
        String formattedQuery = buildFormattedQuery(keyword, category);

        // 3. 调用RAG服务
        String result = ragService.query(formattedQuery);

        return result;
    }
}
```


**关键点：**
- 用`@Tool`注解标记方法，Spring AI自动将其暴露为MCP工具
- 其他支持MCP协议的AI应用可以连接到你的服务，调用`knowledge_query`工具
- 本质上就是把你的RAG能力标准化输出

**如何启动MCP Server？**

Spring AI的`spring-ai-starter-mcp-server-webmvc`依赖会自动配置：
- 默认在`/mcp`端点提供SSE传输
- 外部Client可以通过这个端点连接

---

### **角色2：MCP Client（连接外部服务）**

让你的Agent能够调用外部MCP Server提供的工具。

看[McpClient](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/service/extrenal/McpClient.java)的核心逻辑：

```java
@Component
public class McpClient {

    private final Map<String, McpSyncClient> clientsByUrl = new ConcurrentHashMap<>();
    private final Map<String, List<ToolCallback>> toolCallbacksByUrl = new ConcurrentHashMap<>();

    /**
     * 连接到外部MCP Server
     */
    public ToolCallback[] connect(String serverUrl) {
        McpSyncClient mcpClient;
        McpSchema.InitializeResult initResult;

        // 1. ⭐ 优先尝试Streamable HTTP，失败回退SSE
        try {
            mcpClient = connectWithStreamableHttp(serverUrl);
            initResult = mcpClient.initialize();
            System.out.println("[MCP Client] 使用 Streamable HTTP 传输连接成功");
        } catch (Exception streamableException) {
            System.out.println("[MCP Client] Streamable HTTP 失败，尝试 SSE...");
            mcpClient = connectWithSse(serverUrl);
            initResult = mcpClient.initialize();
            System.out.println("[MCP Client] 使用 SSE 传输连接成功");
        }

        // 2. 获取服务器信息
        String serverName = initResult.serverInfo().name();
        String serverVersion = initResult.serverInfo().version();
        System.out.println("[MCP Client] 已连接到: " + serverName + " v" + serverVersion);

        // 3. ⭐ 自动发现远程工具
        SyncMcpToolCallbackProvider provider = SyncMcpToolCallbackProvider.builder()
                .mcpClients(mcpClient)
                .build();

        ToolCallback[] rawCallbacks = provider.getToolCallbacks();
        
        // 4. 包装日志代理
        ToolCallback[] toolCallbacks = new ToolCallback[rawCallbacks.length];
        for (int i = 0; i < rawCallbacks.length; i++) {
            toolCallbacks[i] = wrapWithLogging(rawCallbacks[i]);
        }

        // 5. 保存连接和工具
        clientsByUrl.put(serverUrl, mcpClient);
        toolCallbacksByUrl.put(serverUrl, Arrays.asList(toolCallbacks));

        // 6. ⭐ 持久化URL，下次启动自动恢复
        store.add(serverUrl);

        return toolCallbacks;
    }
}
```


---

## **二、MCP Client 完整工作流程**

### 🔧 MCP Client 连接后的自动注册流程

```
用户调用 connect(serverUrl)
    ↓
【步骤1】建立MCP连接（Streamable HTTP 或 SSE）
    ↓
【步骤2】自动发现工具（SyncMcpToolCallbackProvider.getToolCallbacks()）
    ↓
【步骤3】⭐ 立即返回 ToolCallback[] 给调用方
    ↓
【步骤4】调用方（ManagerController）收到 ToolCallback[]
    ↓
【步骤5】⭐ 调用 agentCore.registerToolCallbacks(toolCallbacks)
    ↓
【步骤6】AgentCore 将工具加入 toolCallbacks 列表
    ↓
【完成】LLM 下次对话时就能看到这些新工具

```



### **步骤1：建立连接**

用户通过API发起连接：

![image-20260423215427148](C:\Users\24276\AppData\Roaming\Typora\typora-user-images\image-20260423215427148.png)

```bash
POST /api/manage/mcp/connect
{
  "serverUrl": "http://example.com/mcp"
}
```

[McpClient.connect()](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/service/extrenal/McpClient.java#L61-L124)执行：

#### **1.1 传输协议协商**

MCP有两种传输协议：

| 协议                | 规范版本   | 特点                           |
| ------------------- | ---------- | ------------------------------ |
| **Streamable HTTP** | 2025-03-26 | 新规范，基于HTTP流             |
| **SSE**             | 2024-11-05 | 旧规范，基于Server-Sent Events |

代码优先尝试新协议，失败后回退：

```java
try {
    mcpClient = connectWithStreamableHttp(serverUrl);
    initResult = mcpClient.initialize();
} catch (Exception e) {
    mcpClient = connectWithSse(serverUrl);  // 回退
    initResult = mcpClient.initialize();
}
```


**Streamable HTTP连接：**
```java
private McpSyncClient connectWithStreamableHttp(String serverUrl) {
    URI uri = URI.create(serverUrl);
    String baseUri = uri.getScheme() + "://" + uri.getAuthority();
    String endpoint = uri.getPath();
    
    HttpClientStreamableHttpTransport transport = 
        HttpClientStreamableHttpTransport.builder(baseUri)
            .endpoint(endpoint)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    return io.modelcontextprotocol.client.McpClient.sync(transport)
            .requestTimeout(Duration.ofSeconds(30))
            .clientInfo(new McpSchema.Implementation("AiAgentDemo MCP Client", "1.0.0"))
            .build();
}
```


**SSE连接：**
```java
private McpSyncClient connectWithSse(String serverUrl) {
    String baseUri;
    String sseEndpoint;

    if (serverUrl.contains("/sse")) {
        int sseIndex = serverUrl.indexOf("/sse");
        baseUri = serverUrl.substring(0, sseIndex);
        sseEndpoint = serverUrl.substring(sseIndex);
    } else {
        baseUri = serverUrl;
        sseEndpoint = "/sse";
    }

    HttpClientSseClientTransport transport = 
        HttpClientSseClientTransport.builder(baseUri)
            .sseEndpoint(sseEndpoint)
            .build();

    return io.modelcontextprotocol.client.McpClient.sync(transport)
            .clientInfo(new McpSchema.Implementation("AiAgentDemo MCP Client", "1.0.0"))
            .build();
}
```


#### **1.2 初始化握手**

```java
McpSchema.InitializeResult initResult = mcpClient.initialize();
```


这一步会：
- 交换客户端和服务器信息
- 协商协议版本
- 建立通信通道

返回结果包含：
```java
initResult.serverInfo().name();     // 服务器名称
initResult.serverInfo().version();  // 服务器版本
```


---

### **步骤2：自动发现工具**

连接成功后，自动获取远程服务器提供的所有工具：

```java
SyncMcpToolCallbackProvider provider = SyncMcpToolCallbackProvider.builder()
        .mcpClients(mcpClient)
        .build();

ToolCallback[] toolCallbacks = provider.getToolCallbacks();
```


**这个过程发生了什么？**

1. Client向Server发送`tools/list`请求
2. Server返回所有可用的工具列表（名称、描述、参数Schema）
3. Spring AI将每个远程工具转换为本地的`ToolCallback`

**举例：**
假设连接的MCP Server提供了3个工具：
- `github_search_repos`
- `github_get_issue`
- `github_create_pr`

转换后，你的Agent就有了这3个ToolCallback，可以像本地工具一样调用。

---

### **步骤3：注册到Agent**

将获取到的ToolCallback注册到AgentCore：

```java
// 在ManagerController中
@PostMapping("/connect")
public ResponseEntity<?> connect(@RequestBody McpConnectRequest request) {
    ToolCallback[] toolCallbacks = mcpClient.connect(request.getServerUrl());
    
    // ⭐ 注册到AgentCore
    agentCore.registerToolCallbacks(toolCallbacks);
    
    return ResponseEntity.ok("连接成功，已注册 " + toolCallbacks.length + " 个工具");
}
```


现在这些远程工具就变成了Agent的一部分，LLM可以自主决定调用它们。

---

### **步骤4：持久化与自动恢复**

连接成功后，URL会被持久化：

```java
// McpClient.connect()实现
store.add(serverUrl);  // 保存到 mcp-servers.json
```


应用重启时，自动重新连接：

```java
// 自动重连逻辑在 McpTool 中实现！
List<String> savedUrls = mcpClient.getSavedUrls();
for (String url : savedUrls) {
    try {
        ToolCallback[] tools = mcpClient.connect(url);
        agentCore.registerToolCallbacks(tools);
    } catch (Exception e) {
        System.err.println("自动重连失败: " + url);
    }
}
```

### 🔄 完整的自动重连流程

```
Spring Boot 启动
    ↓
扫描所有 @Component Bean
    ↓
发现 McpTool 实现了 InnerTool 接口
    ↓
调用 McpTool.loadToolCallbacks()  ← ⭐ 这里触发自动重连！
    ↓
【内部执行】
    ├─ 从 mcp-servers.json 读取保存的URL
    ├─ 遍历每个URL
    ├─ 调用 mcpClient.connect(url) 重新连接
    ├─ 收集所有 ToolCallback
    └─ 返回 List<ToolCallback>
    ↓
AgentCore.afterPropertiesSet() 收集所有 InnerTool 的工具
    ↓
agentCore.registerToolCallbacks(allCallbacks) 注册到Agent
    ↓
完成！之前连接的MCP服务已自动恢复

```




---

## **三、工具调用的完整链路**

假设用户问："帮我查一下GitHub上Spring Boot的热门仓库"

### **阶段1：LLM决策**

```
LLM看到有一个工具叫 github_search_repos，description是"搜索GitHub仓库"
    ↓
LLM决定调用这个工具
    ↓
返回：{"tool_calls": [{"name": "github_search_repos", "arguments": {"query": "Spring Boot"}}]}
```


### **阶段2：Spring AI执行**

```
Spring AI检测到tool_calls
    ↓
找到名为 github_search_repos 的ToolCallback
    ↓
这个ToolCallback实际上是MCP Client的代理
```


### **阶段3：MCP Client转发请求**

```
MCP Client通过HTTP/SSE发送请求到远程Server：
{
  "method": "tools/call",
  "params": {
    "name": "github_search_repos",
    "arguments": {"query": "Spring Boot"}
  }
}
```


### **阶段4：远程MCP Server执行**

```
远程Server接收到请求
    ↓
执行真实的GitHub API调用
    ↓
返回结果：{"repositories": [...]}
```


### **阶段5：结果返回**

```
MCP Client收到结果
    ↓
返回给Spring AI
    ↓
Spring AI将结果包装成TOOL消息
    ↓
再次发送给LLM
    ↓
LLM生成最终回复
```


---

## **四、关键设计点解析**

### **1. 为什么需要包装日志代理？**

看[wrapWithLogging()](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/service/extrenal/McpClient.java#L267-L306)：

```java
private ToolCallback wrapWithLogging(ToolCallback original) {
    return new ToolCallback() {
        @Override
        public ToolDefinition getToolDefinition() {
            return original.getToolDefinition();
        }

        @Override
        public String call(String toolInput) {
            String toolName = original.getToolDefinition().name();
            
            System.out.println("\n╔══════════════════════════════════════════");
            System.out.println("║ 🌐 [MCP Tool Call] " + toolName);
            System.out.println("║ 📥 入参: " + truncate(toolInput, 200));
            System.out.println("╚══════════════════════════════════════════");

            long startTime = System.currentTimeMillis();
            try {
                String result = original.call(toolInput);
                long elapsed = System.currentTimeMillis() - startTime;

                System.out.println("\n╔══════════════════════════════════════════");
                System.out.println("║ ✅ [MCP Tool Result] " + toolName);
                System.out.println("║ ⏱️ 耗时: " + elapsed + "ms");
                System.out.println("║ 📤 结果: " + truncate(result, 300));
                System.out.println("╚══════════════════════════════════════════\n");
                return result;
            } catch (Exception exception) {
                // 异常日志...
                throw exception;
            }
        }
    };
}
```


**作用：**
- **装饰器模式**：在不修改原始ToolCallback的情况下，添加日志功能
- **调试友好**：清晰显示MCP工具的调用、参数、耗时、结果
- **性能监控**：记录每次调用的耗时

---

### **2. 为什么要持久化URL？**

**场景：**
```
今天：连接了GitHub MCP Server
明天：重启应用
❌ 如果不持久化：需要手动重新连接
✅ 如果持久化：自动重新连接，工具立即可用
```


**实现：**
```java
static class ServerStore {
    private final Path storePath = Paths.get("src/main/resources/data/mcp-servers.json");

    synchronized void add(String url) {
        List<String> urls = load();
        if (!urls.contains(url)) {
            urls.add(url);
            save(urls);  // 写入JSON文件
        }
    }

    synchronized void remove(String url) {
        List<String> urls = load();
        if (urls.remove(url)) {
            save(urls);
        }
    }
}
```


---

### **3. 断开连接的逻辑**

```java
public List<ToolCallback> disconnect(String serverUrl) {
    // 1. 移除Client
    McpSyncClient client = clientsByUrl.remove(serverUrl);
    
    // 2. 获取要移除的工具
    List<ToolCallback> removedCallbacks = toolCallbacksByUrl.remove(serverUrl);
    
    // 3. 优雅关闭连接
    if (client != null) {
        client.closeGracefully();
    }
    
    // 4. 从持久化存储中移除
    store.remove(serverUrl);
    
    // 5. 从AgentCore中移除工具
    agentCore.removeToolCallbacks(removedCallbacks);
    
    return removedCallbacks;
}
```


**关键点：**
- 不仅要关闭连接，还要从AgentCore中移除对应的ToolCallback
- 否则LLM还会尝试调用已经不存在的工具

---

## **五、实际应用场景**

### **场景1：连接GitHub MCP Server**

```bash
POST /api/manage/mcp/connect
{
  "serverUrl": "https://github-mcp.example.com/mcp"
}
```


连接成功后，Agent自动获得：
- `search_repositories`
- `get_repository_details`
- `list_issues`
- `create_pull_request`
- ...

用户可以直接说："帮我找一下Spring Boot的官方仓库，看看最近的Issue"

---

### **场景2：连接数据库MCP Server**

```bash
POST /api/manage/mcp/connect
{
  "serverUrl": "http://db-mcp.internal.com/mcp"
}
```


Agent获得：
- `execute_sql_query`
- `get_table_schema`
- `analyze_query_performance`

用户可以说："查一下上个月的销售数据"

---

### **场景3：作为MCP Server对外提供服务**

你的Agent启动了MCP Server，其他AI应用可以连接：

```
ChatGPT → 连接到你的Agent的MCP Server
    ↓
调用 knowledge_query 工具
    ↓
你的Agent执行RAG检索
    ↓
返回结果给ChatGPT
```


这样你的RAG能力就可以被多个AI应用复用。

---

## **六、MCP vs 普通Tool的区别**

| 维度         | 普通Tool（如GetWeatherTool） | MCP Tool               |
| ------------ | ---------------------------- | ---------------------- |
| **实现位置** | 本地Java代码                 | 远程服务               |
| **注册方式** | 实现InnerTool接口            | 通过MCP协议自动发现    |
| **执行位置** | 本地JVM                      | 远程服务器             |
| **通信方式** | 直接方法调用                 | HTTP/SSE               |
| **扩展性**   | 需要修改代码                 | 只需连接新的MCP Server |
| **适用场景** | 核心业务逻辑                 | 第三方服务集成         |

**本质理解：**
- 对Agent来说，**MCP Tool和本地Tool没有区别**，都是ToolCallback
- MCP的价值在于**标准化**和**解耦**

---

## **✅ MCP总结**

| 角色           | 作用         | 核心价值                        |
| -------------- | ------------ | ------------------------------- |
| **MCP Server** | 对外暴露能力 | 让你的工具可以被其他AI应用调用  |
| **MCP Client** | 连接外部服务 | 让你的Agent可以使用第三方的工具 |

**关键特性：**
1. **协议自动适配**：优先Streamable HTTP，失败回退SSE
2. **工具自动发现**：连接后自动获取远程工具列表
3. **透明集成**：远程工具转换为本地的ToolCallback，对LLM透明
4. **持久化恢复**：URL持久化，重启自动重连

---

## 🎯 验证理解

**场景：** 你连接了一个Slack MCP Server，它提供了`send_message`工具。

**问题1：** 当用户说"帮我在#general频道发一条消息：大家好"，整个流程是怎样的？

<details>
<summary>点击查看答案</summary>

1. **LLM决策**：看到`send_message`工具，决定调用
2. **Spring AI执行**：找到对应的ToolCallback（MCP代理）
3. **MCP Client转发**：通过HTTP/SSE发送请求到Slack MCP Server
4. **Slack Server执行**：调用Slack API发送消息
5. **结果返回**：返回发送结果（成功/失败）
6. **LLM生成回复**："消息已发送到#general频道"

</details>

---

**问题2：** 如果MCP Server宕机了，会发生什么？

<details>
<summary>点击查看答案</summary>

1. **调用时异常**：MCP Client发送请求超时或连接失败
2. **异常抛出**：ToolCallback的call方法抛出异常
3. **Spring AI处理**：捕获异常，可能重试或返回错误
4. **LLM看到错误**：收到工具执行失败的TOOL消息
5. **LLM生成回复**："抱歉，Slack服务暂时不可用"

**改进建议：**
- 在MCP Client中添加健康检查
- 定期检查连接状态，断线自动重连
- 提供fallback机制

</details>

---

## 🎯 下一步

现在你理解了MCP的双向支持机制，接下来：

**A. 进入SubAgent模块**（理解记忆隔离和子代理生命周期）

**B. 深入看MCP协议的细节**（看看tools/list、tools/call的具体格式）

**C. 回顾整体架构**（把所有模块串起来理解）

**D. 其他疑问**

你想选哪个？