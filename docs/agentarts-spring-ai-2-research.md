# AgentArts 高代码运行时与 Spring AI 2.0 兼容性调研

核实日期：2026-10-08。依据华为云、Spring 官方文档及 Spring AI 固定版本源码；未改造现有 Python 应用，未构建 Java 镜像或执行云端部署。

实施补充：上述为调研时状态。当前工程已采用 Spring AI 2.0.1 的 ChatClient / ChatModel SPI 迁移为 Java 服务，具体实现为自定义 `CompatibleChatModel`，它保留上游思考增量；下文关于累计 `reasoningContent` 的结论仅针对官方 `OpenAiChatModel`。实现与验证方式见 [README](../README.md) 和 [迁移记录](../MIGRATION.md)。

## 结论

**可以采用 Spring Boot + Spring AI 2.0 开发，并通过自定义容器镜像部署到 AgentArts 高代码运行时。** 华为云已明确提供 Java / Spring Boot 迁移示例，支持已有应用在不使用 AgentArts SDK 重写的情况下迁移。该模式的交付物是符合运行时协议的镜像；Spring Boot 可执行 JAR 是镜像内部的应用文件，不是本次文档确认的直接上传交付物。[华为云：已有 Agent 应用迁移](https://support.huaweicloud.com/highcode-agentarts/agentarts_10_223.html)

需要区分证据层次：**Java / Spring Boot 容器迁移有华为云官方明确文档；Spring AI 2.0 的兼容性来自上述运行方式和 Spring 官方能力的交叉判断。** 本次未找到华为云针对 Spring AI 2.0 的单独版本认证或兼容性矩阵，不能把文档推导写成实际部署验证。

## Spring 版本与打包能力

| 项目 | 核实结果 | 来源 |
| --- | --- | --- |
| Spring AI 2.0 状态 | 2.0.0 于 2026-06-12 GA；本次查阅的稳定参考文档为 2.0.1 | [GA 公告](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/)、[Getting Started](https://docs.spring.io/spring-ai/reference/getting-started.html) |
| Spring Boot | Spring AI 2.0.x 支持 Boot 4.0.x、4.1.x | [Getting Started](https://docs.spring.io/spring-ai/reference/getting-started.html) |
| Java | Boot 4.1.1 最低 Java 17；Spring AI v2.0.1 源码 POM 的运行时语言目标也是 17。建议本项目选 Java 21 作为统一构建、运行版本，此为工程选型建议，并非 AgentArts 强制要求 | [Boot 系统要求](https://docs.spring.io/spring-boot/system-requirements.html)、[v2.0.1 POM](https://github.com/spring-projects/spring-ai/blob/v2.0.1/pom.xml#L221-L233) |
| 可执行 JAR | Boot Maven 插件 `repackage` 将依赖打入可执行归档，通过 `java -jar` 启动 | [可执行归档打包](https://docs.spring.io/spring-boot/maven-plugin/packaging.html) |
| 容器镜像 | Boot Maven 插件支持 `spring-boot:build-image` 生成 OCI 镜像；可指定 `spring-boot.build-image.imagePlatform=linux/arm64`，需所选 builder/run image 支持该架构 | [OCI 镜像打包](https://docs.spring.io/spring-boot/maven-plugin/build-image.html) |

Spring AI BOM 和 Spring Boot 版本分别管理；建议固定稳定依赖版本，避免直接照搬早期 2.0 milestone 的依赖/API 配置。[Getting Started](https://docs.spring.io/spring-ai/reference/getting-started.html)

## AgentArts 接入条件

| 项目 | 当前项目迁移时应满足的条件 |
| --- | --- |
| 架构与监听 | ARM64 容器；按 HTTP 入站协议监听 `0.0.0.0:8080` |
| 健康检查 | `GET /ping`，应用就绪时 HTTP 200，正文 `{"status":"Healthy"}` |
| 标准业务入口 | `POST /invocations`，JSON 输入，JSON 或 SSE 输出；本项目继续 SSE，响应类型 `text/event-stream` |
| 自定义路径 | 如保留 `/stream` 或新增 `/chat`，使用前缀匹配路由，平台剥离 `/runtimes/{name}/invocations/` 后转发 |
| 镜像上传 | 上传 SWR，镜像区域与 AgentArts 区域一致 |
| 环境配置 | 可配置环境变量、启动命令、监听端口以及公网/私网出网方式 |

上表协议约束来自 [HTTP 入站协议](https://support.huaweicloud.com/highcode-agentarts/agentarts_10_070.html)；自定义路径、Spring Boot 示例和 SWR 区域要求来自 [迁移说明](https://support.huaweicloud.com/highcode-agentarts/agentarts_10_223.html)；环境和网络选项来自 [控制台部署](https://support.huaweicloud.com/highcode-agentarts/agentarts_10_031.html)。普通 Spring Boot Actuator 健康接口不应直接替代 `/ping`：必须匹配平台要求的路径及 `Healthy` 状态值。

可采用“构建可执行 JAR → 放入含 ARM64 JRE 的镜像 → 上传 SWR → 创建运行时”的流程，或者使用 Boot Buildpacks 生成符合要求的 ARM64 镜像。无需为了托管而将业务逻辑改写为 Python SDK。

## 对现有文旅 Agent 的影响

以下为结合本仓库既有需求的迁移设计建议：

- MobileNet 仍作为远程 HTTP 分类服务；Java 应用保留 `X-API-Key` 配置及置信度 `>= 0.7` 分支规则，不需要在 Java 镜像内部加载 MobileNet 权重。
- Koo Search 继续调用既有检索 HTTP API。Spring AI 可负责模型消息和调用部分，知识库接入不必改成另一套向量库。
- 保留当前 `model_config` 请求协议，包括每次请求的模型、地址、认证和扩展配置；将 JSON 字符串形式的配置解析为对象后再传递。
- OpenAI 兼容端点可使用 Spring AI `OpenAiChatModel`。现有 `temp_token/action/model_request` 包装协议属于另一种业务协议，需保留独立 HTTP 适配器，不能仅通过更换 `baseUrl` 认为完成适配。
- 保留现有 SSE 事件封装、错误结束事件、心跳和取消传播；模型的流式输出不等于项目所需的完整对外事件协议。

Spring 官方文档确认 `ChatClient` 同时支持同步与流式模型，`.stream().content()` 返回 `Flux<String>`，也可使用 `.stream().chatResponse()` 保留元数据；支持程序化创建多个模型实例连接不同兼容端点。[Chat Client](https://docs.spring.io/spring-ai/reference/api/chatclient.html)

### 思考参数与 reasoning_content

`OpenAiChatOptions.extraBody(Map<String,Object>)` 会把扩展字段放到请求 JSON 顶层，因此可承接本项目 `chat_template_kwargs`、`thinking` 对象。模型服务本身仍需支持对应参数。自定义地址、API Key 和 HTTP 头也有配置入口。[OpenAI 兼容模型配置](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html)

Spring AI 将返回的 `reasoning_content` 映射为 AssistantMessage 的 `reasoningContent` 元数据。迁移时应消费 `ChatResponse`，不能只取 `.content()`，否则拿不到独立思考字段。[reasoning 文档](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html#_reasoning_content_from_reasoning_models)

**固定 v2.0.1 源码还显示一个需要适配的细节：流式 `reasoningContent` 是截至当前分片的累计字符串。** 若继续使用本项目对外的增量 `reasoning_content`，需按 choice 跟踪已发送值并提取新增部分，不能把每片累计值再次追加输出。此结论来自源码；应通过模拟多片思考响应验证，避免照搬文档中逐片 `append` 的示例。[v2.0.1 OpenAiChatModel.java，第 315–326 行](https://github.com/spring-projects/spring-ai/blob/v2.0.1/models/spring-ai-openai/src/main/java/org/springframework/ai/openai/OpenAiChatModel.java#L315-L326)

同一版本源码确认模型流式路径使用 SDK `createStreaming` 并注册取消时关闭响应；扩展请求字段转换为 additional body properties。[流式实现](https://github.com/spring-projects/spring-ai/blob/v2.0.1/models/spring-ai-openai/src/main/java/org/springframework/ai/openai/OpenAiChatModel.java#L282-L295)、[扩展参数实现](https://github.com/spring-projects/spring-ai/blob/v2.0.1/models/spring-ai-openai/src/main/java/org/springframework/ai/openai/OpenAiChatModel.java#L851-L858)

## 落地前需验证的范围

本次是文档与源码调研，尚未实测 Java 应用、目标模型端点或 AgentArts 部署。最小验证范围建议为：ARM64 镜像启动与 `/ping`；三条业务路径；两个思考配置字段原样透传；首片及时返回及正文/思考增量；客户端断开后的上游取消；经 AgentArts 网关调用的 SSE 连续性；MobileNet、Koo Search、模型端点的出网连通性。

若进入迁移实施，推荐以 **Spring AI 2.0.1 + Spring Boot 4.1.x + Java 21 + ARM64 自定义镜像** 建立验证版本，同时保持现有调用协议与分类策略。此组合基于已查到的 Spring 支持范围及 AgentArts 容器契约，最终兼容性以部署和接口验收为准。
