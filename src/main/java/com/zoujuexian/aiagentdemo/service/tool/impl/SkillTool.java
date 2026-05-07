package com.zoujuexian.aiagentdemo.service.tool.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.zoujuexian.aiagentdemo.service.skill.SkillManager;
import com.zoujuexian.aiagentdemo.service.tool.InnerTool;
import com.zoujuexian.aiagentdemo.service.tool.ToolCallbackBuilder;
import com.zoujuexian.aiagentdemo.service.tool.ToolDomain;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Skill 技能工具
 * <p>
 * 将 SkillManager 封装为 InnerTool，启动时自动加载所有 .md 技能文件。
 */
@Component
public class SkillTool implements InnerTool {

    @Resource
    private SkillManager skillManager;

    @Resource
    private ChatClient chatClient;

    @Override
    public List<ToolCallback> loadToolCallbacks() {
        List<ToolCallback> callbacks = new ArrayList<>();

        for (SkillManager.SkillDefinition definition : skillManager.getSkillDefinitions()) {
            // 根据 parameters 动态构建参数 schema
            Map<String, Map<String, String>> properties = new java.util.LinkedHashMap<>();
            List<String> required = new ArrayList<>();

            for (SkillManager.SkillParameter param : definition.parameters()) {
                properties.put(param.name(), Map.of(
                        "type", "string",
                        "description", param.description()
                ));
                if (param.required()) {
                    required.add(param.name());
                }
            }

            callbacks.add(ToolCallbackBuilder.build(
                    definition.name(),
                    definition.description(),
                    properties,
                    required,
                    argumentsJson -> {
                        JSONObject args = JSON.parseObject(argumentsJson);
                        String prompt = definition.promptTemplate();

                        // 替换所有参数占位符
                        for (SkillManager.SkillParameter param : definition.parameters()) {
                            String value = args.getString(param.name());
                            if (value == null || value.isBlank()) {
                                value = param.defaultValue() != null ? param.defaultValue() : "";
                            }
                            prompt = prompt.replace("{{" + param.name() + "}}", value);
                        }

                        return chatClient.prompt().user(prompt).call().content();
                    }
            ));
        }

        return callbacks;
    }

    @Override
    public ToolDomain getDomain() {
        return ToolDomain.SKILL;
    }
}
