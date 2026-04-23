package com.zoujuexian.aiagentdemo.api.controller;

import com.zoujuexian.aiagentdemo.core.AgentCore;
import com.zoujuexian.aiagentdemo.api.controller.dto.ChatRequest;
import com.zoujuexian.aiagentdemo.api.controller.dto.ChatResponse;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * AI Agent 对话接口
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final AgentCore agentCore;

    public ChatController(AgentCore agentCore) {
        this.agentCore = agentCore;
    }

    /**
     * 与 Agent 对话（非流式）
     */
    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest request) {
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            return ChatResponse.fail("消息不能为空");
        }

        String sessionId = resolveSessionId(request.getSessionId());
        try {
            String reply = agentCore.chat(sessionId, request.getMessage());
            return ChatResponse.ok(reply);
        } catch (Exception exception) {
            return ChatResponse.fail(exception.getMessage());
        }
    }

    /**
     * 与 Agent 流式对话（SSE）
     * <p>
     * 使用 ServerSentEvent 明确控制 SSE 数据格式，避免 Spring 默认 JSON 序列化带引号。
     * 通过累积 token 到句子边界，改善终端显示效果。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@RequestBody ChatRequest request) {
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            return Flux.just(ServerSentEvent.<String>builder().data("[ERROR] 消息不能为空").build());
        }

        String sessionId = resolveSessionId(request.getSessionId());
        return agentCore.chatStream(sessionId, request.getMessage())
                .bufferUntil(token -> {
                    // 遇到句子边界或累积足够长度时发送
                    return token.matches(".*[。！？，,\\.!?]$") || token.length() >= 20;
                })
                .map(tokens -> {
                    String combined = String.join("", tokens);
                    return ServerSentEvent.<String>builder()
                            .data(combined.replace("\n", "\\n"))
                            .build();
                });
    }

    /**
     * 解析 sessionId，未传时生成默认值
     */
    private String resolveSessionId(String sessionId) {
        return (sessionId != null && !sessionId.isBlank()) ? sessionId : "default";
    }
}
