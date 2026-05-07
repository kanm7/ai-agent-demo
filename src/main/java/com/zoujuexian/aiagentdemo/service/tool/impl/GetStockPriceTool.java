package com.zoujuexian.aiagentdemo.service.tool.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.zoujuexian.aiagentdemo.service.tool.ToolCallbackBuilder;
import com.zoujuexian.aiagentdemo.service.tool.InnerTool;
import com.zoujuexian.aiagentdemo.service.tool.ToolDomain;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 股票价格查询工具
 * <p>
 * 获取指定股票的当前价格和涨跌幅（当前为模拟数据）。
 */
@Component
public class GetStockPriceTool implements InnerTool {

    @Override
    public List<ToolCallback> loadToolCallbacks() {
        Map<String, Map<String, String>> properties = Map.of(
                "symbol", Map.of("type", "string", "description", "股票代码，例如：AAPL、GOOGL")
        );

        return Collections.singletonList(ToolCallbackBuilder.build(
                "get_stock_price",
                "获取指定股票的当前价格和涨跌幅",
                properties,
                List.of("symbol"),
                this::execute
        ));
    }

    @Override
    public ToolDomain getDomain() {
        return ToolDomain.STOCK;
    }

    private String execute(String argumentsJson) {
        JSONObject args = JSON.parseObject(argumentsJson);
        String symbol = args.getString("symbol");
        return "{\"symbol\": \"" + symbol + "\", \"price\": 150.25, \"change\": \"+2.5%\"}";
    }
}
