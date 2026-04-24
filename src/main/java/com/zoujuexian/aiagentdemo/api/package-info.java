/**
 * @author czk
 * @description package-info
 * @create 2026/4/24 12:44
 */
package com.zoujuexian.aiagentdemo.api;

/**
 * 
 * 
 * 这个包负责 **API 接口层**，提供对外的 HTTP 接口和 MCP 服务器功能。
 *
 * ## 主要组件
 *
 * ### **controller/** - REST API 控制器
 *
 * #### 1. **ChatController.java** - 聊天接口
 * 提供核心的对话功能：
 * - `POST /api/chat` - 发送消息给 Agent，获取回复
 * - 支持会话管理（sessionId）
 * - 处理用户输入、调用 AgentCore、返回响应
 *
 * #### 2. **CommandController.java** - 命令执行接口
 * 提供 Command 快捷指令的执行入口：
 * - `POST /api/command/execute` - 执行指定的 Command
 * - 接收命令名和文本内容
 * - 加载对应模板并调用 LLM
 *
 * #### 3. **ManagerController.java** - 管理接口
 * 提供系统管理功能：
 * - 连接/断开 MCP 服务器
 * - 切换模型配置
 * - 查看已连接的 MCP 服务列表
 * - 管理系统状态
 *
 * #### 4. **dto/** - 数据传输对象
 * 定义 API 的请求和响应格式：
 * - `ChatRequest` - 聊天请求参数
 * - `ChatResponse` - 聊天响应结果
 * - `McpConnectRequest` - MCP 连接请求
 * - `SwitchModelRequest` - 切换模型请求
 *
 * ### **mcpserver/** - MCP 服务器
 *
 * #### **SimpleMcpServer.java** - 简单 MCP 服务器
 * 让本项目**作为 MCP Server** 对外提供服务：
 * - 其他 MCP Client 可以连接本项目
 * - 暴露本项目的工具给外部系统使用
 * - 配置在 `application.properties` 中（`spring.ai.mcp.server.*`）
 *
 * ## 整体架构
 *
 * ```
 * 前端/客户端
 *     ↓ HTTP 请求
 * controller/ (REST API)
 *     ↓ 调用
 * core/AgentCore (核心逻辑)
 *     ↓ 使用
 * service/ (各种服务：RAG、Skill、MCP等)
 *
 * 同时：
 * 外部 MCP Client ←→ mcpserver/SimpleMcpServer (本项目作为 MCP Server)
 * ```
 *
 *
 * ## 在项目中的角色
 *
 * - **对内**：接收用户请求，调用 AgentCore 处理业务逻辑
 * - **对外**：提供标准的 REST API 和 MCP 协议接口
 * - **解耦**：将 HTTP 层与核心业务逻辑分离，便于维护和扩展
 */