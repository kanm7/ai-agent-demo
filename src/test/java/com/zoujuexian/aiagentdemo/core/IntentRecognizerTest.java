package com.zoujuexian.aiagentdemo.core;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import jakarta.annotation.Resource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 意图识别器测试类
 */
@SpringBootTest
public class IntentRecognizerTest {

    @Resource
    private IntentRecognizer intentRecognizer;

    @Test
    public void testRecognizeRagIntent() {
        // 测试应该识别为RAG意图的问题
        String userInput = "Spring Boot的自动配置原理是什么？";
        String knowledgeTopics = "Java、Spring Boot、Maven、设计模式等技术文档";
        
        Intent intent = intentRecognizer.recognize(userInput, knowledgeTopics);
        
        assertEquals(Intent.RAG, intent, "技术问题应该被识别为RAG意图");
    }

    @Test
    public void testRecognizeGeneralIntent() {
        // 测试应该识别为GENERAL意图的问题
        String userInput = "今天天气怎么样？";
        String knowledgeTopics = "Java、Spring Boot、Maven、设计模式等技术文档";
        
        Intent intent = intentRecognizer.recognize(userInput, knowledgeTopics);
        
        assertEquals(Intent.GENERAL, intent, "非技术问题应该被识别为GENERAL意图");
    }

    @Test
    public void testRecognizeWithDifferentKnowledgeTopics() {
        // 测试不同知识库主题下的意图识别
        String userInput = "Python的装饰器怎么用？";
        String javaTopics = "Java、Spring Boot、Maven、设计模式等技术文档";
        String pythonTopics = "Python、Django、Flask等Python相关技术文档";
        
        Intent intentWithJavaTopics = intentRecognizer.recognize(userInput, javaTopics);
        Intent intentWithPythonTopics = intentRecognizer.recognize(userInput, pythonTopics);
        
        // 当知识库是Java主题时，Python问题应该是GENERAL
        assertEquals(Intent.GENERAL, intentWithJavaTopics, 
            "Python问题在Java知识库中应该被识别为GENERAL意图");
        
        // 当知识库是Python主题时，Python问题应该是RAG
        assertEquals(Intent.RAG, intentWithPythonTopics, 
            "Python问题在Python知识库中应该被识别为RAG意图");
    }

    @Test
    public void testBackwardCompatibility() {
        // 测试向后兼容的单参数方法
        String userInput = "什么是单例模式？";
        
        Intent intent = intentRecognizer.recognize(userInput);
        
        assertNotNull(intent, "向后兼容的方法应该正常工作");
    }
}
