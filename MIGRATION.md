# 文旅 Agent 高代码迁移记录

## 已确认范围

- 用户确认部署到 AgentArts 高代码运行时。
- 用户确认保留原 MobileNet 分类服务，以及置信度 ≥ 0.7 时检索、否则视觉问答的策略。
- Koo Search 接口已自行查明，采用当前 `SearchInKnowledgeRepo`，支持 AppCode 与 IAM Token。
- 请求与 SSE 外层以本地 `io_spec.md` 为兼容依据。

## 当前交付

已迁移为 `src/main/java/com/heilos/travel` 下的 Spring Boot 4.1.1 + Spring AI 2.0.1 服务，提供 Maven Wrapper、ARM64 Dockerfile、Java 回归测试以及部署说明。原始 JSONL、HTML、IO 文档保持原样，不作为运行时依赖，也不进入镜像。

完整运行方式与配置见 [README.md](README.md)。接口依据见 [Koo Search 研究](docs/koosearch-research.md) 和 [AgentArts 研究](docs/agentarts-runtime-research.md)。

## 保留与修正

| 原流程行为/问题 | 高代码处理 |
| --- | --- |
| 纯文本检索、图片高置信度检索、视觉兜底 | 三条路径全部保留 |
| 文本与图片使用不同知识库 | 两个独立 Koo Search repo_id 配置，也可同库 |
| top_k=3、原召回阈值 0.3/0.1 | 保留 top_k；默认使用新知识库阈值，避免跨评分尺度照搬 |
| 数组只取第一张图片 | 保留并明确说明 |
| 文本长度判断与提示矛盾 | 统一允许最多 1000 字符 |
| URL 与 Base64 冲突规则不一致 | 入口统一拒绝同时提供 |
| Base64 校验与视觉请求格式不一致 | 统一规范化后提供分类与视觉模型 |
| `1%` 被转换为 1.0 | 正确转换为 0.01；不提前四舍五入影响 0.7 边界 |
| 数字成功码 0 被当成错误 | 支持数字 0 和字符串 "0" |
| body / 能力查询类型不一致 | 内部保持对象，只在 HTTP/SSE 边界序列化 |
| endpoint 域名被固定网关覆盖 | 使用调用方配置的完整 endpoint |
| 构造失败仍调用模型 | 直接返回业务错误并结束 SSE |
| 分类 400/401/5xx 映射不准确 | 分别映射；HTTP 失败不转视觉模型 |
| 图像匹配但检索为空时丢失展品名称 | 保留分类候选名称，并要求谨慎作答 |
| 日文仅假名提问被覆盖为中文默认问题 | 保留非空原始提问语言 |

## 明确的兼容边界

- 保留 start/message/finished/end 事件和 `responseContent` 字符串结构；过程节点数量和 ID 对应新的高代码步骤。
- 模型请求设置 `stream=true`，将上游文本增量实时写入 `message.data.text`；最终汇总仍为 `responseContent` JSON 字符串，不重复发送全文。能力查询保留单帧 JSON。
- 上游普通 JSON 响应兼容为单个文本片段；流中错误或提前断开时，最终结果标为失败，不将错误内容混入文本流。
- 思考参数由调用方在 `model_config` 的 `chat_template_kwargs` / `thinking` 提供，解析对象或 JSON 字符串后透传；不再强制关闭。上游 `reasoning_content` 单独透传至 SSE 同名字段，最终讲解结果只汇总正文。
- 示例只给出成功的外层状态，失败扩展为 `code=-1, desc=failed`；内层 code 为业务结果。
- 维持原模型未使用会话历史的单轮行为，明确返回 `enable_history=false`。平台会话 ID 不等于应用历史存储。
- 保留文旅提示词、语言和篇幅要求；这些是模型指令，不是硬截断输出。
- 图片 URL 仍按扩展名校验，Base64 仍按文件头校验；完整图像解码由分类服务完成。

## 验证与部署边界

Java 回归测试使用本机模拟 HTTP 服务验证三条业务路径、官方检索请求字段、SSE 顺序、失败结果、整次调用超时、Token 文件轮换及 Reactor 取消传播。测试不会调用真实模型或读取原始资料中的凭证。

2026-10-08 本地验证：Maven Wrapper `verify` 成功，106 项测试全部通过；可执行 JAR 的实际 HTTP/SSE 冒烟测试通过健康检查、原 IO 客户端能力查询、三条业务分支的实时分片输出，以及客户端断开后取消上游连接。构建使用临时 JDK 21，未修改本机全局 Java 环境；日常开发仍需配置 `JAVA_HOME`。当时 Docker 引擎未启动，未验证 ARM64 镜像或云端部署。

真实联调需要部署配置中的 Koo Search 实例地址、认证、两个知识库 ID，以及分类服务可用密钥。未创建云资源、推送镜像或部署运行时。是否完成 Docker 镜像验证以实际交付报告为准。

## 2026-10-08：Python → Spring AI 2.0

- 应用改为 Java 21、Spring Boot WebFlux 与 Spring AI ChatClient；环境变量名称和已有 IO 协议兼容。服务不依赖巫山框架或 AgentArts SDK。
- 模型通过自定义 `CompatibleChatModel` 实现 Spring AI `ChatModel` SPI。保留两种上游协议和精简 SSE/业务 JSON 兼容；未强行要求历史网关改成完整 OpenAI 数据结构。
- 思考内容在自定义适配器中保持增量，外部 SSE 不重复思考片段；这与调研中官方 `OpenAiChatModel` 的累计元数据不同。
- 移除 Python 服务、Python 测试和 uv 构建配置，历史实现仍可从 Git 提交 `2ce0303` 查阅。保留只依赖标准库的 `scripts/test_io_spec.py`，用于读取本地 IO 示例测试 Java 服务。
- 修复普通 JSON 回退对正文首尾空白的裁剪；其输出现在与 SSE 一致。上游单事件大小上限也覆盖尚未结束的长行。
- 镜像由 Maven 构建可执行 JAR 后放入 JRE 21，仍要求 `linux/arm64`、8080、`/invocations` 和 `/ping`。部署验证独立于本地测试，不将本地测试通过视为云端验收完成。
