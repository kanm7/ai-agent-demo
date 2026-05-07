package com.zoujuexian.aiagentdemo.core;

/**
 * 用户意图枚举
 * <p>
 * 用于意图识别阶段，区分用户输入的意图类型，以便 Agent 采取不同的处理策略。
 */
public enum Intent {

    /**
     * 知识库检索：用户的问题需要从 RAG 知识库中检索相关信息来回答
     */
    RAG,

    /**
     * 天气查询：用户询问天气信息
     */
    WEATHER,

    /**
     * 金融/股票查询：用户询问股票、金融相关信息
     */
    FINANCE,

    /**
     * 代码审查：用户请求代码审查、分析或解释
     */
    CODE_REVIEW,

    /**
     * 子任务处理：用户请求创建或使用 SubAgent 处理复杂任务
     */
    SUB_TASK,

    /**
     * 通用对话：闲聊、工具调用、代码生成等，交由大模型自主处理
     */
    GENERAL
}
