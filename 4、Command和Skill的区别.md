好的！我们进入**Skill vs Command**模块。这是两种基于Markdown的Prompt模板机制，但设计理念完全不同。

---

## 🎯 核心区别一句话总结

- **Command**：用户主动告诉Agent做什么（快捷指令）
- **Skill**：Agent自己判断该做什么（智能工具）

---

## **一、Command：用户主动调用的快捷指令**

### **1. 文件格式**

看`src/main/resources/command/summarize.md`：

```markdown
请对以下文本进行摘要总结，提取核心要点，用简洁的语言概括主要内容：

{{input}}
```


**特点：**
- 纯Prompt模板，没有元数据
- 文件名就是命令名（`summarize.md` → 命令名叫`summarize`）
- `{{input}}`是占位符，运行时替换为用户输入

### **2. 加载机制**

[CommandManager](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/service/command/CommandManager.java)在启动时扫描：

```java
@Component
public class CommandManager {
    private final Map<String, String> commands = new ConcurrentHashMap<>();

    public CommandManager() {
        loadCommands();
    }

    private void loadCommands() {
        // 扫描 classpath:command/*.md
        Resource[] resources = resolver.getResources("classpath:command/*.md");
        
        for (Resource resource : resources) {
            String filename = resource.getFilename();
            String commandName = filename.replace(".md", "");  // 文件名即命令名
            String template = readResource(resource);
            
            commands.put(commandName, template);
            System.out.println("[Command] 已加载命令: " + commandName);
        }
    }
}
```


### **3. 执行流程**

用户通过API主动调用：

```bash
POST /api/command/execute
{
  "command": "summarize",
  "input": "这是一段需要总结的长文本..."
}
```


[CommandController](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/api/controller/CommandController.java)处理：

```java
@PostMapping("/execute")
public String execute(@RequestBody CommandRequest request) {
    // 1. 根据命令名获取模板
    String template = commandManager.getTemplate(request.getCommand());
    
    // 2. 替换占位符
    String prompt = template.replace("{{input}}", request.getInput());
    
    // 3. 调用LLM
    return chatClient.prompt().user(prompt).call().content();
}
```


**关键点：**
- ❌ **不注册为Tool**，LLM不知道它的存在
- ✅ **用户明确指定**命令名
- ✅ **直接调用LLM**，不走AgentCore的ReAct循环

---

## **二、Skill：LLM自主调用的工具**

### **1. 文件格式**

看`src/main/resources/skill/summarize.md`：

```markdown
---
name: summarize
description: 对用户提供的文本内容进行摘要总结
parameters:
  - name: text
    description: 需要总结的文本内容
    required: true
---

请对以下文本进行摘要总结，提取核心要点：

{{text}}
```


**特点：**
- 有YAML Front Matter（`---`包裹的元数据）
- 必须包含`name`和`description`
- 支持多参数定义（`parameters`列表）
- Prompt模板中使用`{{参数名}}`占位符

### **2. 加载机制**

[SkillManager](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/service/skill/SkillManager.java)扫描并解析：

```java
@Component
public class SkillManager {
    private final List<SkillDefinition> skillDefinitions = new ArrayList<>();

    public SkillManager() {
        loadSkills();
    }

    private void loadSkills() {
        // 扫描 classpath:skill/*.md
        Resource[] resources = resolver.getResources("classpath:skill/*.md");
        
        for (Resource resource : resources) {
            SkillDefinition definition = parseSkillFile(resource);
            if (definition != null) {
                skillDefinitions.add(definition);
            }
        }
    }

    private SkillDefinition parseSkillFile(Resource resource) {
        String content = readResource(resource);
        
        // 1. 解析Front Matter
        int endIndex = content.indexOf("---", 3);
        String frontMatter = content.substring(3, endIndex).trim();
        String promptTemplate = content.substring(endIndex + 3).trim();
        
        // 2. 提取name、description、parameters
        String name = extractName(frontMatter);
        String description = extractDescription(frontMatter);
        List<SkillParameter> parameters = parseParameters(frontMatter);
        
        return new SkillDefinition(name, description, promptTemplate, parameters);
    }
}
```


### **3. 注册为Tool**

[SkillTool](file:///D:/chen/aiagentdemo/src/main/java/com/zoujuexian/aiagentdemo/service/tool/impl/SkillTool.java)将每个Skill转换为ToolCallback：

```java
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
            // 1. 根据parameters构建JSON Schema
            Map<String, Map<String, String>> properties = new LinkedHashMap<>();
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

            // 2. ⭐ 构建ToolCallback
            callbacks.add(ToolCallbackBuilder.build(
                definition.name(),           // 工具名
                definition.description(),    // 描述（LLM根据这个决定是否调用）
                properties,                  // 参数Schema
                required,                    // 必填参数
                argumentsJson -> {
                    // 3. 执行逻辑：替换占位符，调用LLM
                    JSONObject args = JSON.parseObject(argumentsJson);
                    String prompt = definition.promptTemplate();

                    // 替换所有参数占位符 {{text}} → 实际值
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
}
```


### **4. 执行流程**

用户正常对话：

```
用户："帮我总结一下这段代码的功能"
    ↓
AgentCore.chat() 正常流程
    ↓
LLM分析：用户想总结代码，看到summarize技能的description是"对文本进行摘要总结"
    ↓
LLM决定调用summarize工具
    ↓
LLM返回：{"tool_calls": [{"name": "summarize", "arguments": {"text": "代码内容"}}]}
    ↓
Spring AI执行SkillTool的execute方法
    ↓
替换prompt中的{{text}}，调用LLM
    ↓
返回总结结果
    ↓
LLM基于结果生成最终回复
```


**关键点：**
- ✅ **注册为Tool**，LLM知道它的存在
- ✅ **LLM自主决策**是否调用
- ✅ **走ReAct循环**，可能连续调用多个Skill

---

## **三、对比表格**

| 维度                | Command                         | Skill                                        |
| ------------------- | ------------------------------- | -------------------------------------------- |
| **设计理念**        | 用户快捷指令                    | LLM可调用的工具                              |
| **文件格式**        | 纯Prompt模板                    | Front Matter + Prompt模板                    |
| **是否注册为Tool**  | ❌ 不注册                        | ✅ 注册为ToolCallback                         |
| **调用触发方**      | 用户主动指定命令名              | LLM根据description自主决策                   |
| **执行路径**        | 用户 → Controller → 直接调用LLM | 用户 → AgentCore → LLM决策 → SkillTool → LLM |
| **是否走ReAct循环** | ❌ 否                            | ✅ 是                                         |
| **适用场景**        | 用户明确知道需要什么功能        | 需要LLM理解上下文后智能判断                  |
| **参数支持**        | 只支持`{{input}}`               | 支持多参数定义                               |
| **灵活性**          | 低（固定入口）                  | 高（LLM动态决策）                            |

---

## **四、实际使用场景对比**

### **场景1：Code Review**

**用Command：**
```bash
POST /api/command/execute
{
  "command": "code_review",
  "input": "public class UserService { ... }"
}
```

- 用户明确知道要Code Review
- 快速执行，不走Agent逻辑

**用Skill：**
```
用户："我写了一个UserService类，你帮我看下有没有问题"
    ↓
LLM看到code_review技能的description是"对代码进行审查，从质量、Bug、性能等维度给出建议"
    ↓
LLM自主决定调用code_review技能
    ↓
传入代码，得到审查结果
    ↓
LLM生成友好回复："我帮你审查了代码，发现以下几个问题..."
```

- LLM理解用户意图
- 可以结合上下文（比如之前讨论过的项目规范）

---

### **场景2：翻译**

**用Command：**
```bash
POST /api/command/execute
{
  "command": "translate",
  "input": "Hello World"
}
```


**用Skill：**
```
用户："把这句话翻译成中文：Hello World"
    ↓
LLM识别到翻译意图，调用translate技能
```


---

## **五、为什么需要两种机制？**

**Command的优势：**
1. **确定性高**：用户明确指定，不会误判
2. **速度快**：不走ReAct循环，少一次LLM调用
3. **适合批量处理**：可以用脚本批量调用Command

**Skill的优势：**
1. **智能化**：LLM根据上下文自主判断
2. **组合能力强**：可以和其他Tool配合使用
3. **用户体验好**：用户用自然语言表达即可

**互补关系：**
- Command提供**确定性的快捷入口**
- Skill提供**智能化的能力扩展**

---

## **六、动手实践：创建一个新Skill**

假设我们要创建一个"生成API文档"的Skill。

### **步骤1：创建Markdown文件**

`src/main/resources/skill/api_doc_generator.md`：

```markdown
---
name: api_doc_generator
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
```


### **步骤2：重启应用**

SkillManager会自动扫描并加载：
```
[Skill] 已加载技能: api_doc_generator（参数: [code, format]）
[Skill] 共加载 8 个技能
```


### **步骤3：测试**

```
用户："我有一个UserController，帮我生成API文档"
    ↓
LLM看到api_doc_generator的description
    ↓
LLM调用工具：{"name": "api_doc_generator", "arguments": {"code": "...", "format": "markdown"}}
    ↓
SkillTool执行：替换{{code}}和{{format}}，调用LLM生成文档
    ↓
返回API文档
```


---

## **七、常见问题**

### **Q1：如果Skill的description写得不好会怎样？**

**答：** LLM可能不会调用它，或者在不该调用时调用。

**错误示例：**
```yaml
description: 处理文本
```

太模糊，LLM不知道什么时候用。

**正确示例：**
```yaml
description: 对用户提供的代码进行审查，从代码质量、潜在Bug、性能优化、可读性等维度给出改进建议
```

清晰说明功能和适用场景。

---

### **Q2：Skill可以有多个参数吗？**

**答：** 可以！看上面的`api_doc_generator`示例，有`code`和`format`两个参数。

LLM会根据参数定义生成JSON：
```json
{
  "code": "public class UserController { ... }",
  "format": "markdown"
}
```


---

### **Q3：Command和Skill可以同时存在同名文件吗？**

**答：** 可以，它们互不影响。

- `command/summarize.md` → Command叫`summarize`
- `skill/summarize.md` → Skill叫`summarize`

用户可以选择：
- 用Command：`POST /api/command/execute {"command": "summarize", ...}`
- 用Skill：自然语言对话，LLM自主决定是否调用

---

## **✅ 总结**

| 特性     | Command            | Skill                |
| -------- | ------------------ | -------------------- |
| **本质** | Prompt模板快捷方式 | Function Calling工具 |
| **触发** | 用户主动           | LLM自主              |
| **注册** | 不注册             | 注册为Tool           |
| **适用** | 确定性任务         | 智能化场景           |

**设计规律：**
- 需要**快速执行、确定性高**的任务 → 用Command
- 需要**LLM理解上下文、智能决策**的场景 → 用Skill

---

