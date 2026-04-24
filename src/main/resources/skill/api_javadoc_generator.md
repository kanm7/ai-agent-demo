---
name: api_javadoc_generator.md
description: 根据Java代码生成标准的API文档，包括接口说明、参数、返回值等
parameters:
  - name: code
    description: Java代码片段
    required: true
  - name: format
    description: 文档格式，可选：markdown、html、json
    required: false
    default: markdown
---

请根据以下Java代码生成{{format}}格式的API文档：

{{code}}

要求：
1. 清晰说明接口的功能
2. 列出所有参数的类型和含义
3. 说明返回值的结构
4. 提供使用示例
