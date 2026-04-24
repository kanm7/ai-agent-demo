/**
 * @author czk
 * @description package-info
 * @create 2026/4/24 12:41
 */
package com.zoujuexian.aiagentdemo.service.command;

/**
 * 
 * 我来查看这个包的内容。
 *
 *
 *
 *
 * **已查看文件** *CommandManager.java*
 *
 *
 * 这个包负责 **Command（命令）管理**功能。
 *
 * ## 核心作用
 *
 * 管理用户**主动调用的快捷指令**，与 Skill 不同，Command **不会注册为 ToolCallback**，而是通过 REST API 直接执行。
 *
 * ## 主要功能
 *
 * ### **CommandManager.java** - 命令管理器
 *
 * 1. **自动扫描** `classpath:command/*.md` 目录下的所有 Markdown 文件
 * 2. **加载命令模板**，文件名即为命令名（去掉 `.md` 后缀）
 * 3. **提供查询接口**，根据命令名查找对应的 Prompt 模板
 * 4. **构建最终 Prompt**，将模板中的 `{{input}}` 替换为用户输入
 *
 * ## 文件格式
 *
 * Command 文件是**纯 Prompt 模板**，没有 Front Matter：
 *
 * ```markdown
 * 请对以下代码进行 Code Review，从代码质量、潜在 Bug、性能、可读性等维度给出改进建议：
 * {{input}}
 * ```
 *
 *
 * - 文件名：`code_review.md` → 命令名：`code_review`
 * - 占位符：`{{input}}` 会被替换为用户实际输入的内容
 *
 * ## 与 Skill 的区别
 *
 * | 特性 | Command | Skill |
 * |------|---------|-------|
 * | **调用方式** | 用户通过 API 主动指定命令名 | LLM 自主决定何时调用 |
 * | **注册形式** | 不注册为 ToolCallback | 注册为 ToolCallback |
 * | **文件格式** | 纯 Prompt 模板 | YAML Front Matter + Prompt 模板 |
 * | **参数支持** | 仅支持单个 `{{input}}` | 支持多参数自定义 |
 * | **决策权** | 用户控制 | LLM 控制 |
 *
 * ## 在项目中的角色
 *
 * 被 [CommandController](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/api/controller/CommandController.java) 调用，用户通过 `POST /api/command/execute` 接口传入命令名和文本内容，系统加载对应模板并调用 LLM 执行。
 */