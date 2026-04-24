/**
 * @author czk
 * @description package-info
 * @create 2026/4/24 12:27
 */
package com.zoujuexian.aiagentdemo.service.extrenal;


/**
 * 这个包负责 **MCP（Model Context Protocol）客户端**功能。
 *
 * ## 核心作用
 *
 * **连接外部的 MCP 服务器**，将远程服务提供的工具集成到 Agent 中，让 LLM 能够调用外部系统的能力。
 *
 * ## 主要组件
 *
 * ### **McpClient.java** - MCP 客户端管理器
 * 
 * MCP 服务器列表存储在 mcp-servers.json 文件中：
 *
 * 负责：
 *
 * 1. **建立连接** - 通过 URL 连接到 MCP 服务器
 * 2. **获取工具** - 从 MCP 服务器发现并拉取可用的工具列表
 * 3. **转换为 ToolCallback** - 将远程工具包装成 Spring AI 的 ToolCallback
 * 4. **连接管理** - 维护多个 MCP 服务器的连接状态，支持断开、重连
 * 5. **日志代理** - 包装远程工具调用，记录入参、出参、耗时等信息
 *
 * ## 工作流程
 *
 * ```
 * 配置 MCP 服务器 URL → 建立连接 → 获取远程工具列表 
 *        ↓
 * 包装为 ToolCallback → 注册到 AgentCore
 *        ↓
 * LLM 调用时 → McpClient 转发请求到远程服务器 → 返回结果
 * ```
 *
 *
 * ## 在项目中的角色
 *
 * 被封装为 **McpTool**（在 `service/tool/impl/McpTool.java`），实现 `InnerTool` 接口。启动时自动读取配置文件中的 MCP 服务器列表，批量连接并注册所有远程工具。
 *
 * ## 典型应用场景
 *
 * - 连接钉钉 MCP 服务器，调用钉钉 API
 * - 连接数据库 MCP 服务器，执行 SQL 查询
 * - 连接文件系统 MCP 服务器，读写文件
 * - 任何遵循 MCP 协议的外部服务
 * 
 * MCP URL 初始化为 ToolCallback 的完整流程
 * 
 * 启动时自动恢复（关键代码在 McpTool.java）
 * 
 * 应用启动
   ↓
McpTool (实现 InnerTool 接口) 被 Spring 扫描
   ↓
调用 loadToolCallbacks() 方法
   ↓
mcpClient.getSavedUrls() 读取 mcp-servers.json 文件
   ↓
遍历每个 URL，调用 mcpClient.connect(url)
   ↓
【connect 方法内部】
   ├─ 建立 MCP 连接（Streamable HTTP 或 SSE）
   ├─ 调用 SyncMcpToolCallbackProvider.getToolCallbacks() 获取远程工具
   ├─ 包装日志代理 wrapWithLogging()
   └─ 返回 ToolCallback[] 数组
   ↓
收集所有 ToolCallback 返回列表
   ↓
AgentCore 统一注册这些 ToolCallback

 * 
 * 
 * 
 */