package com.zoujuexian.aiagentdemo.service.tool.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.zoujuexian.aiagentdemo.service.tool.ToolCallbackBuilder;
import com.zoujuexian.aiagentdemo.service.tool.InnerTool;
import com.zoujuexian.aiagentdemo.service.tool.ToolDomain;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Collections;

/**
 * 天气查询工具
 * <p>
 * 获取指定城市某一天的天气信息（当前为模拟数据）。
 */
@Component
public class GetWeatherTool implements InnerTool {

    @Override
    public List<ToolCallback> loadToolCallbacks() {
        Map<String, Map<String, String>> properties = Map.of(
                "location", Map.of("type", "string", "description", "城市名称，例如：北京、上海"),
                "date", Map.of("type", "string", "description", "日期，格式 yyyy-MM-dd，例如：2026-04-04")
        );

        return Collections.singletonList(ToolCallbackBuilder.build(
                "get_weather",
                "获取指定城市某一天的天气信息",
                properties,
                List.of("location", "date"),
                this::execute
        ));
    }

    @Override
    public ToolDomain getDomain() {
        return ToolDomain.WEATHER;
    }

    private String execute(String argumentsJson) {
        JSONObject args = JSON.parseObject(argumentsJson);
        String location = args.getString("location");
        String date = args.getString("date");
        return location + " " + date + " 的天气是：晴天，气温 22°C，微风";
    }
}
