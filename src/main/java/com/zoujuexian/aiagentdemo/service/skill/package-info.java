/**
 * @author czk
 * @description package-info
 * @create 2026/4/24 12:00
 */
package com.zoujuexian.aiagentdemo.service.skill;


/**
 * 
 * SkillManager负责：
 * 自动扫描classpath:skill/*.md文件，将文件中的技能定义转换为SkillDefinition对象，并保存到SkillManager的skillDefinitions属性中。
 * 解析文件格式，提取技能的元数据（名称、描述、参数）和 Prompt 模板
 * 转换为工具定义，供 Agent 调用
 * 
 * 启动时 → 扫描 skill/*.md → 解析 front matter → 生成 SkillDefinition 列表

 * 
 * 每个 Skill 文件包含两部分：
 * Front Matter（--- 之间的 YAML 格式）：定义技能名称、描述、参数
 * Prompt 模板：实际执行的提示词，使用 {{参数名}} 占位符
 * Skill 是一种可配置的 AI 任务模板，与 Command 类似但更灵活。它会被转换成 ToolCallback，让大模型能够根据用户意图自动选择合适的技能执行。
 * 
 * SkillDefinition在SkillToll中被转换为ToolCallback，进而被Agent调用。
 */