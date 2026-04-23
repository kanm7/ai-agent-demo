## 📚 RAG 完整流水线

**RAG解决的问题：**
- LLM的训练数据有截止时间，不知道最新信息
- LLM没有你的私有数据（公司文档、内部知识库）

**解决方案：**
先把相关知识检索出来，拼接到Prompt中，让LLM基于这些知识回答。

---

### **RAG的7个步骤：**

```
1. 文档加载 → 2. 分块 → 3. 向量化 → 4. 存储
                                      ↓
7. LLM生成 ← 6. 拼接上下文 ← 5. 检索 ← 用户提问
```


我们逐个拆解。

---

## **步骤1-2：文档加载与分块**

看[RagService](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/service/rag/RagService.java)的构造函数和loadKnowledgeBase方法：

```java
public RagService(EmbeddingModel embeddingModel, ChatClient chatClient, ...) {
    this.vectorStore = new VectorStore(embeddingModel);
    this.chunkSplitter = new TextSplitter(CHUNK_SIZE, CHUNK_OVERLAP);  // 分块器
    // ...
    loadKnowledgeBase(KNOWLEDGE_DIR);  // 启动时加载知识库
}

public void loadKnowledgeBase(String knowledgeDir) {
    // 1. 扫描knowledge目录下的所有.txt文件
    List<Path> textFiles = Files.list(dirPath)
            .filter(path -> path.toString().endsWith(".txt"))
            .toList();

    for (Path filePath : textFiles) {
        String content = Files.readString(filePath, StandardCharsets.UTF_8);
        
        // 2. ⭐ 分块：将长文档切分成小块
        List<Document> chunks = chunkSplitter.split(content, fileName);
        
        // 3. 存入向量库
        vectorStore.addDocuments(chunks);
    }
}
```


### **为什么要分块？**

**原因1：向量检索精度**
```
❌ 整篇文档（10000字）作为一个向量
   → 检索时，即使命中，也包含大量无关内容
   
✅ 分成20个块（每块500字）
   → 检索时，只返回最相关的几个块，精准度高
```


**原因2：Token限制**
```
LLM的上下文窗口有限（如8K、32K）
如果检索到3篇长文档，可能直接超限
分块后，只取最相关的3-5个块，控制在范围内
```


### **TextSplitter的分块策略：**

你的项目默认使用[TextSplitter](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/service/rag/chunk/definite/TextSplitter.java)，它是**递归语义分块**：

```
优先级从高到低：
1. 按标题分割（# ## ###）
2. 按段落分割（连续换行）
3. 按句子分割（句号、问号等）
4. 按固定字符数分割（兜底）
```


**配置参数：**
```java
private static final int CHUNK_SIZE = 500;     // 每块500字符
private static final int CHUNK_OVERLAP = 50;   // 相邻块重叠50字符
```


**为什么需要重叠（Overlap）？**
```
文档："HashMap是Java中的哈希表实现。它基于数组和链表..."

如果不重叠：
块1: "HashMap是Java中的哈希表实现。"
块2: "它基于数组和链表..."
     ↑ "它"指代不明，丢失上下文

如果重叠50字符：
块1: "HashMap是Java中的哈希表实现。它基于数组..."
块2: "...它基于数组和链表..."
     ↑ 保留了关键上下文
```


---

## **步骤3：向量化（Embedding）**

**什么是向量化？**

将文本转换为高维向量（如768维、1536维），语义相似的文本，向量距离更近。

```
"Java是一种编程语言"     → [0.1, 0.5, -0.3, ..., 0.8]  (768维)
"Python也是一种编程语言"  → [0.2, 0.4, -0.2, ..., 0.7]  (768维)
                         ↑ 这两个向量很接近

"今天天气不错"           → [0.9, -0.1, 0.6, ..., -0.4] (768维)
                         ↑ 这个向量离上面两个很远
```


**在你的项目中：**

[VectorStore](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/service/rag/VectorStore.java)使用Spring AI的`EmbeddingModel`：

```java
public void addDocuments(List<Document> documents) {
    for (Document doc : documents) {
        // 1. 调用EmbeddingModel生成向量
        float[] embedding = embeddingModel.embed(doc.getContent());
        
        // 2. 存储：文本 + 向量 + 元数据
        vectors.add(new VectorEntry(doc.getId(), doc.getContent(), embedding));
    }
}
```


**EmbeddingModel从哪来？**

在`application.properties`中配置：
```properties
spring.ai.openai.embedding.options.model=embedding-3
```


Spring AI自动创建`EmbeddingModel` Bean，调用智谱的embedding-3接口生成向量。

---

## **步骤4：向量存储**

你的项目使用**内存向量库**[VectorStore](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/service/rag/VectorStore.java)：

```java
private final List<VectorEntry> vectors = new ArrayList<>();

public void addDocuments(List<Document> documents) {
    for (Document doc : documents) {
        float[] embedding = embeddingModel.embed(doc.getContent());
        vectors.add(new VectorEntry(doc.getId(), doc.getContent(), embedding));
    }
}
```


**生产环境应该用什么？**
- Milvus
- Pinecone
- Elasticsearch
- PostgreSQL (pgvector插件)

**为什么项目用内存实现？**
- 学习用途，无需额外部署
- 代码简单，便于理解原理

---

## **步骤5：多路召回（Multi-Retriever）**

这是RAG最核心的部分。看[RagService.query()](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/service/rag/RagService.java#L121-L144)：

```java
public String query(String question) {
    // 1. ⭐ 多路召回：三路检索，共返回9个候选
    List<Document> candidates = multiRecaller.retrieve(question, RECALL_CANDIDATE_COUNT);
    
    // 2. Rerank重排：从9个中选最相关的3个
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


### **为什么需要多路召回？**

单一检索策略总有盲区：

| 检索方式       | 擅长场景           | 不擅长场景     |
| -------------- | ------------------ | -------------- |
| **向量检索**   | 语义相似但措辞不同 | 精确关键词匹配 |
| **BM25关键词** | 精确术语匹配       | 语义理解       |
| **查询改写**   | 扩大覆盖面         | -              |

**举例：**
```
用户问："HashMap怎么扩容？"

向量检索：
  ✅ 找到"HashMap的resize机制详解"
  ❌ 可能漏掉"Java集合框架中的动态数组扩展"

BM25检索：
  ✅ 找到包含"扩容"关键词的文档
  ❌ 可能召回很多不相关的"扩容"（如服务器扩容）

查询改写：
  LLM将问题改写为：
  - "HashMap的resize原理"
  - "HashMap容量扩展机制"
  - "HashMap如何增加容量"
  然后分别做向量检索，扩大覆盖面
```


### **三路召回的实现：**

看[RagService构造函数](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/service/rag/RagService.java#L65-L70)：

```java
List<Retriever> retrievers = List.of(
    new SemanticRetriever(vectorStore),           // 向量检索
    new Bm25Retriever(vectorStore),               // BM25关键词
    new QueryRewriteRetriever(vectorStore, chatClient)  // 查询改写+向量
);
this.multiRecaller = new MultiRetriever(retrievers);
```


#### **第1路：SemanticRetriever（向量检索）**

```java
public List<Document> retrieve(String query, int topK) {
    // 1. 将查询向量化
    float[] queryEmbedding = embeddingModel.embed(query);
    
    // 2. 计算余弦相似度，返回最相似的topK个
    return vectorStore.search(queryEmbedding, topK);
}
```


#### **第2路：Bm25Retriever（关键词匹配）**

```java
public List<Document> retrieve(String query, int topK) {
    // 使用BM25算法计算每个文档的相关性分数
    // BM25是TF-IDF的改进版，考虑了文档长度等因素
    Map<String, Double> scores = bm25.score(query);
    
    // 返回分数最高的topK个
    return getTopKDocuments(scores, topK);
}
```


#### **第3路：QueryRewriteRetriever（查询改写）**

```java
public List<Document> retrieve(String query, int topK) {
    // 1. 让LLM将问题改写为3种不同表达
    String rewrittenQueries = chatClient.prompt()
        .user("将以下问题改写为3种不同的表达方式：\n" + query)
        .call().content();
    
    // 2. 对每种改写分别做向量检索
    List<Document> allResults = new ArrayList<>();
    for (String rewrittenQuery : parseQueries(rewrittenQueries)) {
        List<Document> results = vectorStore.search(embeddingModel.embed(rewrittenQuery), topK / 3);
        allResults.addAll(results);
    }
    
    return allResults;
}
```


---

### **RRF融合算法**

三路召回后，每路返回3个文档，共9个候选。如何合并？

看[MultiRetriever](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/service/rag/retrieve/MultiRetriever.java)的核心逻辑：

```java
public List<Document> retrieve(String query, int topK) {
    Map<String, Double> rrfScores = new HashMap<>();
    Map<String, Document> keyToDocument = new LinkedHashMap<>();
    
    for (Retriever retriever : retrievers) {
        // 每路召回3个文档
        List<Document> results = retriever.retrieve(query, PER_ROUTE_CANDIDATE_COUNT);
        
        // ⭐ RRF累加分数
        accumulateRrfScores(results, rrfScores, keyToDocument);
    }
    
    // 按RRF分数排序，取前topK个
    return rrfScores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(topK)
            .map(entry -> keyToDocument.get(entry.getKey()))
            .toList();
}

private void accumulateRrfScores(List<Document> results, 
                                  Map<String, Double> rrfScores,
                                  Map<String, Document> keyToDocument) {
    for (int i = 0; i < results.size(); i++) {
        String docId = results.get(i).getId();
        int rank = i + 1;  // 排名（从1开始）
        
        // ⭐ RRF公式：score += 1 / (k + rank)，k=60
        double score = 1.0 / (60 + rank);
        rrfScores.merge(docId, score, Double::sum);
        
        keyToDocument.putIfAbsent(docId, results.get(i));
    }
}
```


**RRF公式解析：**
```
score(document) = Σ 1 / (k + rank_i)
                  i∈所有召回路

其中：
- rank_i: 在第i路中的排名（1, 2, 3...）
- k: 平滑常数，通常取60

举例：
文档A在第1路排第1，在第2路排第3
score(A) = 1/(60+1) + 1/(60+3) = 0.0164 + 0.0159 = 0.0323

文档B只在第3路排第1
score(B) = 1/(60+1) = 0.0164

所以 A > B，A排在前面
```


**为什么用RRF而不是直接相加相似度分数？**

因为不同算法的分数范围不同：
- 向量相似度：0~1
- BM25分数：可能0~100
- 无法直接比较

RRF只看**排名**，不看绝对分数，天然适合融合不同算法。

---

## **步骤6：Rerank重排**

多路召回后有9个候选文档，用专用的Rerank模型精排。

看[LlmReranker](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/service/rag/rerank/LlmReranker.java)：

```java
public List<Document> rerank(String query, List<Document> documents, int topK) {
    // 1. 构造请求：query + 9个候选文档
    RerankRequest request = buildRerankRequest(query, documents);
    
    // 2. 调用Rerank API（智谱的rerank模型）
    RerankResponse response = rerankModel.rerank(request);
    
    // 3. 按相关性分数排序，取前topK个
    return response.getResults().stream()
            .sorted((a, b) -> Double.compare(b.getRelevanceScore(), a.getRelevanceScore()))
            .limit(topK)
            .map(result -> documents.get(result.getIndex()))
            .toList();
}
```


**为什么需要Rerank？**
- 召回阶段追求速度，用简单算法快速筛选
- 重排阶段追求精度，用专用模型精细排序
- Rerank模型专门训练用于判断(query, document)的相关性

---

## **步骤7：LLM生成最终答案**

回到[AgentCore.chat()](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/core/AgentCore.java#L203-L215)：

```java
// 如果是RAG意图，先检索知识库
if (intent == Intent.RAG && ragService.isKnowledgeLoaded()) {
    String ragContext = ragService.query(userInput);  // 检索得到参考资料
    
    // ⭐ 将参考资料拼接到用户输入中
    String enrichedInput = "以下是从知识库中检索到的相关参考资料，"
            + "请结合这些资料回答用户的问题：\n\n"
            + ragContext + "\n\n用户问题：" + userInput;
    
    memory.addMessage(new UserMessage(enrichedInput));
} else {
    memory.addMessage(new UserMessage(userInput));
}

// 然后正常调用LLM
List<Message> messages = memory.getMessages();
Prompt prompt = new Prompt(messages, buildChatOptions());
String response = chatClient.prompt(prompt).call().content();
```


**最终发送给LLM的Prompt长这样：**
```
System: 你是一个智能助手...

User: 以下是从知识库中检索到的相关参考资料，请结合这些资料回答用户的问题：

【参考资料 1】
HashMap的扩容机制：当元素数量超过阈值（capacity * loadFactor）时，
会触发resize操作，创建一个新的数组，容量是原来的2倍...

【参考资料 2】
HashMap在JDK1.8之后采用数组+链表+红黑树的结构...

用户问题：HashMap怎么扩容？
```


LLM看到这些参考资料后，就能基于最新、最准确的知识回答问题。

---

### **✅ RAG完整流程总结：**

| 步骤            | 做什么                | 关键技术                 |
| --------------- | --------------------- | ------------------------ |
| **1. 文档加载** | 读取.txt文件          | Java IO                  |
| **2. 分块**     | 切成500字符的小块     | TextSplitter递归分割     |
| **3. 向量化**   | 文本→768维向量        | EmbeddingModel           |
| **4. 存储**     | 存入向量库            | 内存List（生产用Milvus） |
| **5. 召回**     | 三路检索，返回9个候选 | 向量+BM25+查询改写       |
| **6. 融合**     | RRF算法合并结果       | 只看排名，不看分数       |
| **7. 重排**     | Rerank模型精排，取3个 | 专用Rerank API           |
| **8. 拼接**     | 组装成Prompt          | 字符串拼接               |
| **9. 生成**     | LLM基于知识回答       | ChatClient               |

---

## 🎯 验证理解

**场景：** 用户问"Spring Boot的自动配置原理是什么？"

**问题1：** 意图识别会返回什么？

<details>
<summary>点击查看答案</summary>

`Intent.RAG`

因为这个问题涉及Spring Boot技术知识，属于知识库范畴。

</details>

---

**问题2：** 三路召回分别会找到什么类型的文档？

<details>
<summary>点击查看答案</summary>

**向量检索：**
- "Spring Boot自动配置机制详解"
- "@EnableAutoConfiguration注解的作用"

**BM25检索：**
- 包含"自动配置"关键词的文档
- 包含"AutoConfiguration"的文档

**查询改写：**
LLM可能改写为：
- "Spring Boot如何实现自动配置"
- "@EnableAutoConfiguration工作原理"
- "Spring Boot starter自动装配机制"

然后分别检索，可能找到更多相关文档。

</details>

---

**问题3：** 如果我把`RECALL_CANDIDATE_COUNT`从9改成3，会有什么影响？

<details>
<summary>点击查看答案</summary>

**影响：**
- 每路只返回1个文档（3路共3个）
- RRF融合后只有3个候选
- Rerank从3个中选3个（相当于不做选择）

**优点：**
- 检索速度快
- Token消耗少

**缺点：**
- 可能漏掉相关文档
- 容错性差（如果某路召回质量差，没机会补救）

**建议：**
- 小规模知识库（<100个文档）：可以用3-5个
- 大规模知识库（>1000个文档）：保持9个或更多

</details>

---

