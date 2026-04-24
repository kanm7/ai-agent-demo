/**
 * @author czk
 * @description package-info
 * @create 2026/4/24 12:52
 */
package com.zoujuexian.aiagentdemo.core;

/*

这个包是项目的**核心引擎层**，包含 AI Agent 的核心业务逻辑。

## 主要组件

### **1. AgentCore.java** - Agent 核心控制器
整个系统的**大脑**，负责：
- 接收用户输入
- 识别意图（是否需要 RAG、调用工具等）
- 管理对话记忆
- 调用 LLM 生成回复
- 协调各种工具（Skill、RAG、MCP、SubAgent）的执行
- 维护所有会话的状态

### **2. ChatMemory.java** - 对话记忆管理
解决 LLM 无状态问题，负责：
- 存储和管理对话历史
- **三层压缩策略**：
  - 摘要压缩（超过 15 条消息时总结早期对话）
  - Assistant 消息裁剪（只保留最近 3 条）
  - 滑动窗口兜底（防止无限增长）
- 按 sessionId 隔离不同用户的对话上下文

### **3. Intent.java & IntentRecognizer.java** - 意图识别
判断用户输入的意图类型：
- `Intent.RAG` - 需要检索知识库
- `Intent.NORMAL` - 普通对话
- `Intent.TOOL` - 需要调用工具
- 决定后续的处理流程

### **4. ModelConfig.java** - 模型配置管理
管理 LLM 的参数配置：
- temperature（温度）
- maxTokens（最大 token 数）
- topP（采样参数）
- 支持运行时动态切换

### **5. SubAgent.java & SubAgentManager.java** - 子代理系统
实现**多 Agent 协作**：
- `SubAgent` - 拥有独立记忆的专用 Agent（如代码审查专家、翻译助手）
- `SubAgentManager` - 管理 SubAgent 的生命周期（创建、对话、销毁）
- 主 Agent 可以通过工具创建 SubAgent 处理复杂子任务

## 核心工作流程

```
用户输入
   ↓
AgentCore.chat()
   ↓
IntentRecognizer 识别意图
   ↓
┌─ RAG 意图 → RagService 检索知识库 → 拼接上下文
├─ 普通意图 → 直接使用用户输入
└─ 工具意图 → 注册 ToolCallbacks
   ↓
ChatMemory 获取/更新对话历史
   ↓
调用 LLM（带工具和上下文）
   ↓
LLM 可能触发 Function Calling
   ↓
执行工具（Skill/MCP/RAG/SubAgent）
   ↓
LLM 基于工具结果生成最终回复
   ↓
返回给用户
```


## 在项目中的角色

这是整个 Agent 系统的**中枢神经**：
- **向上**：接收 API 层的请求
- **向下**：调用各种 Service（RAG、Skill、MCP 等）
- **横向**：管理记忆、意图、模型参数、子代理

所有核心 AI 能力都在这里编排和调度。


 */