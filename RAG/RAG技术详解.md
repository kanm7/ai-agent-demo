# RAG（检索增强生成）技术详解

## 一、RAG 是什么

LLM 的两个核心缺陷：
1. **知识截止**：训练数据有时间限制，不知道最新信息
2. **幻觉**：不知道的事情会编造答案

RAG 的解法：**不让模型凭记忆回答，而是先检索相关文档，再基于文档生成答案。**

```
传统 LLM：  用户问题 → LLM → 回答（可能幻觉）

RAG：       用户问题 → 检索器 → 相关文档
                              ↓
                    用户问题 + 相关文档 → LLM → 基于事实的回答
```

---

## 二、RAG 完整流程

```
原始文档
  ↓ 1. 文档加载（Document Loading）
  ↓ 2. 文本分块（Chunking）
  ↓ 3. 向量化（Embedding）
  ↓ 4. 存入向量库（Indexing）
         ↑——————————— 离线构建阶段（一次性）

用户提问
  ↓ 5. 查询向量化（Query Encoding）
  ↓ 6. 检索（Retrieval）
  ↓ 7. 重排序（Reranking）
  ↓ 8. 组装 Prompt（Prompt Construction）
  ↓ 9. 生成回答（Generation）
         ↑——————————— 在线推理阶段（每次查询）
```

---

## 三、离线构建阶段

### 3.1 文档加载（Document Loading）

把各种格式的原始文档读入内存，统一成纯文本。

**常见文档格式和处理方式：**

| 格式 | 工具 | 说明 |
|------|------|------|
| PDF | PyMuPDF / pdfplumber | 注意处理多栏、表格、图片 |
| Word | python-docx | 保留段落结构 |
| HTML | BeautifulSoup | 去掉 HTML 标签，保留正文 |
| Markdown | 直接读取 | 结构天然清晰 |
| 数据库 | SQLAlchemy | 把行记录转成文本 |

**你的项目：**
```python
# 直接加载预处理好的 JSON，每条已经是结构化文本
with open(demo_json_path, "r", encoding="utf-8") as f:
    self.demos = json.load(f)
# 每条格式：{"sentence": "...", "aspect": "food", "polarity": "positive"}
```

---

### 3.2 文本分块（Chunking）

**为什么要分块？**

- 模型 Embedding 有最大长度限制（如 512 token）
- 长文档整体向量会"稀释"关键信息，检索精度差
- 需要把文档切成语义完整的小片段

**常见分块策略：**

#### 固定大小分块（Fixed Size）
```
文档：[——————————————————————————————————]
切块：[——500词——][——500词——][——500词——]
重叠：         [←overlap→][←overlap→]
```
- 简单粗暴，但可能切断一个完整句子
- overlap（重叠）防止关键信息落在切割边界

```python
# LangChain 实现
from langchain.text_splitter import RecursiveCharacterTextSplitter
splitter = RecursiveCharacterTextSplitter(
    chunk_size=500,      # 每块最多500个字符
    chunk_overlap=50,    # 相邻块重叠50字符
)
chunks = splitter.split_text(document)
```

#### 语义分块（Semantic Chunking）
按段落、章节、标题自然边界切分，保留语义完整性：
```
# 按段落
chunks = document.split("\n\n")

# 按标题（Markdown）
## 第一章  ← 一个chunk
...内容...
## 第二章  ← 一个chunk
...内容...
```

#### 句子级分块（Sentence-level）
```python
import nltk
sentences = nltk.sent_tokenize(document)
# 把若干句子合成一个chunk，控制在最大长度内
```

**分块大小怎么选：**
```
太小（50词）：单个chunk语义不完整，上下文丢失
太大（2000词）：向量语义被稀释，检索精度下降
推荐（200-500词）：语义完整，检索精确
```

**你的项目：**
```
不需要分块。每条训练样本本身就是一个句子（已经是最小粒度），
直接以 "sentence [SEP] aspect" 为单位建库。
```

---

### 3.3 向量化（Embedding）

把文本转成稠密向量，使得语义相近的文本在向量空间里距离近。

**为什么要向量化？**

计算机不能直接比较文字，但可以计算向量之间的距离：
```
"food was great"  → [0.12, -0.03, 0.87, ..., 0.45]  (384维)
"meal was superb" → [0.11, -0.02, 0.85, ..., 0.43]  (384维)
                     ↑ 两个向量很接近 → 语义相似
```

**常见 Embedding 模型：**

| 模型 | 维度 | 特点 |
|------|------|------|
| all-MiniLM-L6-v2 | 384 | 轻量快速，适合通用场景 |
| all-mpnet-base-v2 | 768 | 精度更高，速度稍慢 |
| text-embedding-ada-002 | 1536 | OpenAI API，质量高，要付费 |
| BGE-large-zh | 1024 | 中文场景最强之一 |
| E5-large | 1024 | 多语言，指令微调 |

**L2 归一化：**
```python
# 归一化后向量模长=1，内积等价于余弦相似度
embeddings = model.encode(texts, normalize_embeddings=True)
# 向量每个值在 [-1, 1] 之间，模长=1
```

**你的项目（build_atsc_retrieval_supar_fix_2hop.py）：**
```python
model = SentenceTransformer("all-MiniLM-L6-v2")
# 检索键：sentence + aspect 拼接，让向量同时编码句子语义和情感对象
text = f"{sentence} [SEP] {aspect}"
vector = model.encode(text, normalize_embeddings=True).tolist()
# 存入 JSON，shape=(384,)，float32
```

---

### 3.4 存入向量库（Indexing / Vector Store）

把所有文档向量存起来，并建立索引结构，支持快速检索。

**存储方式对比：**

#### 内存 numpy 矩阵（你的项目）
```python
# 加载时直接读进内存
self.demo_embeddings = np.asarray([d["vector"] for d in demos], dtype=np.float32)
# shape: (N, 384)，约 N×384×4 字节
# 5000条 ≈ 7.3MB，极小，全内存操作
```
优点：零依赖，实现最简单  
缺点：重启后要重新加载，不支持动态更新

#### FAISS（Facebook AI Similarity Search）
```python
import faiss
index = faiss.IndexFlatIP(384)   # 精确内积索引
index.add(embeddings)            # 加入向量
faiss.write_index(index, "index.faiss")  # 持久化到磁盘
```
优点：纯内存，检索极快，支持 IVF/HNSW 等 ANN 索引  
缺点：不支持元数据过滤，不支持多租户

#### 向量数据库（Milvus / Qdrant / Weaviate）
```python
# 以 Qdrant 为例
from qdrant_client import QdrantClient
client = QdrantClient("localhost", port=6333)
client.upsert(
    collection_name="docs",
    points=[
        {"id": i, "vector": vec, "payload": {"text": text, "source": "wiki"}}
        for i, (vec, text) in enumerate(zip(vectors, texts))
    ]
)
```
优点：持久化、支持 payload 过滤、水平扩展、多并发  
缺点：运维成本高，小规模杀鸡用牛刀

**规模选型：**
```
< 1万条    → numpy 矩阵（你的项目）
< 100万条  → FAISS IndexFlatIP 或 IndexIVFFlat
< 1亿条    → FAISS IndexIVFPQ 或向量数据库
> 1亿条    → Milvus / Qdrant 分布式
```

---

## 四、在线推理阶段

### 4.1 查询向量化（Query Encoding）

用和建库时**完全相同的模型**对用户问题编码：

```python
query = f"{sentence} [SEP] {aspect}"   # 你的项目：同样的拼接格式
query_emb = model.encode(
    query,
    normalize_embeddings=True           # 必须和建库时一致
).reshape(1, -1)                        # shape: (1, 384)
```

**关键：query 和文档必须用同一个模型编码，否则向量空间不对齐，相似度没有意义。**

---

### 4.2 检索（Retrieval）

在向量库中找出和 query 最相似的 top-k 文档。

#### 粗排（First-stage / Bi-encoder）

速度优先，大范围召回候选：

```python
# 你的项目：余弦相似度全扫描
sims = cosine_similarity(query_emb, self.demo_embeddings)[0]  # (N,)
k0 = top_k * 10            # 先召回10倍候选
cand_idx = np.argsort(sims)[::-1][:k0]
```

Bi-encoder 的特点：query 和文档**分别编码**，相似度计算快（矩阵乘法），但精度有限，因为没有交叉注意力。

#### 去重（Deduplication）

```python
# 你的项目：同一句话+同一aspect只保留一条
seen = set()
for i in cand_idx:
    key = (demos[i]["sentence"], demos[i]["aspect"])
    if key not in seen:
        seen.add(key)
        unique.append(i)
```

#### 稀疏检索（BM25）

传统关键词匹配，适合精确名词、产品型号等：
```python
from rank_bm25 import BM25Okapi
corpus = [doc.split() for doc in documents]
bm25 = BM25Okapi(corpus)
scores = bm25.get_scores(query.split())
```

#### 混合检索（Hybrid）

BM25 + Dense 各召回一批，RRF 融合排名：
```python
# RRF 公式：排名越靠前，得分越高
def rrf_score(bm25_rank, dense_rank, k=60):
    return 1/(bm25_rank + k) + 1/(dense_rank + k)
```

---

### 4.3 重排序（Reranking）

粗排速度快但精度有限，精排精度高但速度慢，分两阶段：

#### Rule Score（领域规则打分）——你的项目独有

```python
# 4个维度加权
total = 0.4 * aspect_score    # aspect 是否匹配
      + 0.3 * opinion_score   # opinion 词是否重叠（需要 supar 依存解析）
      + 0.2 * pattern_score   # 句法模式是否相同（amod/acomp/neg...）
      + 0.1 * polarity_score  # 极性是否兼容

# 融合向量相似度和规则分数
final = (1 - rule_alpha) * cosine_score + rule_alpha * rule_total

# aspect_gate 硬过滤：aspect 不匹配的直接丢弃
if aspect_score < 0.5:
    continue
```

#### CrossEncoder 精排

把 query 和每个候选文档**拼在一起**输入模型，模型内部做交叉注意力，精度远高于 Bi-encoder：

```python
# query 和候选拼成 pair
pairs = [(query, demo_text) for demo_text in candidates]
# CrossEncoder 同时看 query 和文档，计算相关性分数
scores = reranker.predict(pairs)
# 重新排序
candidates = sorted(zip(scores, candidates), reverse=True)
```

**Bi-encoder vs CrossEncoder 对比：**
```
Bi-encoder（粗排）：
  query → [Encoder] → q_vec
  doc   → [Encoder] → d_vec
  score = q_vec · d_vec
  速度：快（预计算doc向量）
  精度：中（无交叉注意力）

CrossEncoder（精排）：
  [query, doc] → [Encoder] → score
  速度：慢（每个pair都要过一遍模型）
  精度：高（query和doc充分交互）
```

#### MMR 多样性重排

防止返回的文档都在说同一件事：

```python
# MMR = 相关性 - 冗余度
mmr_score = lambda * sim(doc, query) - (1-lambda) * max(sim(doc, selected))
# lambda=1：完全按相关性排（不考虑多样性）
# lambda=0.5：相关性和多样性各占一半
```

---

### 4.4 组装 Prompt（Prompt Construction）

把检索到的文档和用户问题拼成 LLM 的输入：

**通用 RAG Prompt 模板：**
```
你是一个问答助手。请根据以下参考文档回答问题。
如果文档中没有答案，请说"我不知道"，不要编造。

参考文档：
[文档1]: ...
[文档2]: ...
[文档3]: ...

问题：{用户问题}
回答：
```

**你的项目（ABSA 场景）：**
```
# 检索到的样例作为 few-shot 示例
示例1：
  句子：The food was amazing
  Aspect：food
  情感：positive

示例2：
  句子：Service was slow and rude
  Aspect：service
  情感：negative

现在请判断：
  句子：{sentence}
  Aspect：{aspect}
  情感：
```

RAG 和 few-shot 在你的项目里融合在一起：检索到的样例即是 few-shot 示范。

---

### 4.5 生成回答（Generation）

把组装好的 Prompt 送给 LLM 生成最终回答：

```python
from transformers import AutoModelForCausalLM, AutoTokenizer

# Qwen3 ChatML 格式
messages = [
    {"role": "system", "content": "你是一个情感分析专家"},
    {"role": "user", "content": prompt}  # 包含检索到的文档
]

# 应用模板
text = tokenizer.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
outputs = model.generate(inputs, max_new_tokens=50)
```

---

## 五、高级优化技术

### 5.1 查询改写（Query Rewriting）

用户的原始问题可能措辞不佳，先用 LLM 改写成更适合检索的形式：
```
原始：这手机续航怎么样？
改写：iPhone 15 电池续航时间 battery life
```

### 5.2 HyDE（假设文档嵌入）

让 LLM 先生成一个假设性回答，用这个回答的向量去检索（比问题向量更接近真实文档）：
```
问题 → LLM 生成假设答案 → 对假设答案编码 → 用此向量检索
```

### 5.3 父子分块（Parent-Child Chunking）

小块用于精确检索，大块用于生成（提供更多上下文）：
```
父块（1000词）：整个段落，送给 LLM
  └── 子块（100词）：用于向量检索，定位精确
```

### 5.4 多向量检索（Multi-vector）

一个文档存多个向量（摘要向量 + 原文向量），提高召回率。

---

## 六、你的项目 RAG 流程总结

```
离线构建（build_atsc_retrieval_supar_fix_2hop.py）：
  训练集 JSON
    → supar 解析每条句子的依存树
    → 提取 aspect 中心的 1-hop/2-hop 依存子图
    → SentenceTransformer 编码 "sentence [SEP] aspect"
    → 存入 enhanced_retrieval_library.json
      （每条含：vector, aspect_dep, opinion_candidates）

在线检索（retriever.py ATSCRetriever.retrieve()）：
  输入：sentence + aspect
    ↓ 编码 query = "sentence [SEP] aspect"
    ↓ cosine_similarity 全扫描 → top-k×10 候选
    ↓ 去重（同sentence+aspect只保留一条）
    ↓ [可选] rule_score 重排
    │   supar 解析 query 依存树
    │   4维加权：aspect(0.4)+opinion(0.3)+pattern(0.2)+polarity(0.1)
    │   aspect_gate=0.5 硬过滤
    ↓ [可选] CrossEncoder 精排
    ↓ [可选] MMR 多样性重排
    ↓ 返回 top-k 样例
    ↓ 组装 few-shot prompt → Qwen3 生成情感标签
```

**与标准 RAG 的核心差异：**
- 检索键不是纯文本，而是 `sentence [SEP] aspect`（任务感知）
- 多了 rule_score（领域规则）和依存树特征（结构信息）
- 检索目标不是"找相关文档"，而是"找相同情感模式的样例"（few-shot）

---

*文档生成时间：2026-04-24*
*项目路径：/usr/2Tusr/zhuyvbo/EMGF-main/rag*
