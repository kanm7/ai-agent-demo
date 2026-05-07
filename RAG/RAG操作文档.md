## 先读取

```java
1. public void upload() {
    	// 支持多种类型的后缀文件
        TikaDocumentReader reader = new TikaDocumentReader("./data/file.text");

        List<Document> documents = reader.get();
        List<Document> documentSplitterList = tokenTextSplitter.apply(documents);

        documents.forEach(doc -> doc.getMetadata().put("knowledge", "知识库名称"));
        documentSplitterList.forEach(doc -> doc.getMetadata().put("knowledge", "知识库名称"));

        pgVectorStore.accept(documentSplitterList);

        log.info("上传完成");
    }
2. public void read_rag() {
        String content = "第一部分：架构概览。在这个项目中，我们采用领域驱动设计（DDD）来组织代码。" +
                "第二部分：Agent核心引擎。我们实现了 Flow 和 Auto 两种执行策略，并且接入了 MCP 协议。" +
                "第三部分：向量检索。在千万级数据的知识库中，必须使用 HNSW 索引配合局部索引来解决多租户数据隔离问题。" +
                "第四部分：高并发调度。利用乐观锁和看门狗机制解决了集群部署下的任务防重和死锁问题。";

        // 3. 构建 Document 对象，务必带上 Metadata
        // 这里的 Metadata 就是我们上一个问题中提到的“预过滤（Pre-filtering）”的核心依赖
        Document document = new Document(content, Map.of(
                "tenant_id", "1001",
                "doc_type", "TECH_MANUAL"
        ));
        TokenTextSplitter splitter = new TokenTextSplitter();
        List<Document> chunks = splitter.apply(List.of(document));
        vectorStore.add(chunks);
    }
```

## 在查询

```java
public void testSimilaritySearch() {
        String userQuery = "项目中是怎么解决集群部署下的并发防重和死锁问题的？";
        // 2. 🌟 核心：构建带过滤条件的混合检索请求 (SearchRequest)
        // 这就是我们在面试题中重点探讨的"预过滤 (Pre-filtering)"落地代码
        SearchRequest request = SearchRequest.builder()
                .query(userQuery)
                .topK(2) // 召回距离最近的前 2 条数据 (Top-K)
                // 结合 Metadata 过滤，确保绝不会跨租户漏数据或串数据！
                .filterExpression("tenant_id == '1001' && doc_type == 'TECH_MANUAL'")
                .build();
        // 3. 执行检索
        List<Document> results = vectorStore.similaritySearch(request);
        // 4. 打印召回结果
        log.info("========== 检索召回结果展示 ==========");
        if (results.isEmpty()) {
            log.warn("没有检索到任何相关内容！请检查数据库是否为空，或者 tenant_id 过滤条件是否匹配。");
        } else {
            for (int i = 0; i < results.size(); i++) {
                log.info("匹配度排名第 {} 的内容: {}", i + 1, results.get(i).getText());
                log.info("该条数据的元数据: {}", results.get(i).getMetadata());
            }
        }
        log.info("======================================");
    }
```

## 结合大模型

```java
public void testRagGeneration() {
        String userQuery = "项目中是怎么解决集群部署下的并发防重和死锁问题的？";
        // 构建底层的聊天客户端
        ChatClient chatClient = ChatClient.builder(chatModel).build();
        // 🌟 1.0.0 核心适配一：强制采用 Builder 模式构建检索请求，语法更清爽
        SearchRequest searchRequest = SearchRequest.builder()
                .query(userQuery)
                .topK(2) // 注意：1.0.0 中所有的 with 前缀都被砍掉了
                .filterExpression("tenant_id == '1001' && doc_type == 'TECH_MANUAL'")
                .build();
        // 🌟 1.0.0 核心适配二：将构建好的 SearchRequest 喂给增强器进行开卷考试
        String answer = chatClient.prompt()
                .user(userQuery)
                .advisors(new RagAnswerAdvisor(vectorStore, searchRequest))
                .call()
                .content();
    }
```

