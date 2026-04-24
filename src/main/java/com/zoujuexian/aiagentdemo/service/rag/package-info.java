/**
 * @author czk
 * @description package-info
 * @create 2026/4/24 12:06
 */
package com.zoujuexian.aiagentdemo.service.rag;

/**
 * 
 * 
 * 这个包实现了 **RAG（检索增强生成）** 功能。
 *
 * ## 核心作用
 *
 * 让 Agent 能够**从外部知识库中检索相关信息**，再结合这些信息回答问题，解决大模型知识过时或缺乏私有数据的问题。
 *
 * ## 主要组件
 *
 * ### 1. **Document.java** - 文档模型
 * 存储文本内容及其元数据的基本单元。
 *
 * ### 2. **VectorStore.java** - 向量存储
 * 将文档转换为向量并存储，支持相似度检索。
 *
 * ### 3. **chunk/** - 文本分块
 * 将长文档切分成小块，便于向量化和检索：
 * - **definite/** - 确定性分块策略
 *   - `FixedSizeSplitter` - 固定大小切分
 *   - `SentenceSplitter` - 按句子切分
 *   - `ParagraphSplitter` - 按段落切分
 *   - `SlidingWindowSplitter` - 滑动窗口切分
 * - **intellegent/** - 智能分块策略
 *   - `SemanticChunkSplitter` - 语义分块
 *   - `PropositionSplitter` - 命题分块
 *   - `AgenticSplitter` - Agent 辅助分块
 *
 * ### 4. **retrieve/** - 检索器
 * 从向量库中查找相关文档：
 * - `Bm25Retriever` - 基于关键词的 BM25 算法检索
 * - `SemanticRetriever` - 基于语义相似度的向量检索
 * - `QueryRewriteRetriever` - 查询重写后检索
 * - `MultiRetriever` - 多路召回（组合多种检索策略）
 *
 * ### 5. **rerank/** - 重排序
 * - `LlmReranker` - 使用 LLM 对检索结果重新排序，提升相关性
 *
 * ### 6. **RagService.java** - RAG 服务入口
 * 整合分块、存储、检索、重排序的完整流程，对外提供统一的检索接口。
 *
 * ## 工作流程
 *
 * ```
 * 用户上传文档 → 分块(Chunk) → 向量化 → 存入VectorStore
 *                     ↓
 * 用户提问 → 检索(Retrieve) → 重排序(Rerank) → 返回相关片段
 *                     ↓
 * LLM 结合检索结果生成答案
 * ```
 *
 *
 * ## 在项目中的角色
 *
 * 被封装为 **RagTool**（在 `service/tool/impl/RagTool.java`），注册为 ToolCallback，让 LLM 可以自主决定何时调用知识库检索。
 * 
 * 
 */