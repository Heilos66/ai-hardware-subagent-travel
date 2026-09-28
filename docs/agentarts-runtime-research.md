# AgentArts 高代码运行时部署核实

核实日期：2026-09-28。仅使用华为云官方资料；未执行部署或真实服务调用。

## 结论与容器约束

可以使用自行实现 HTTP 路由的 FastAPI 容器：官方要求兼容 HTTP 入站协议，SDK 是推荐方案，并非强制依赖。这个结论是根据框架无关定位和镜像接口约束作出的实现判断。镜像必须为 **ARM64**；禁止把本地磁盘当成持久存储，沙箱释放后数据会丢失。[运行时概述](https://support.huaweicloud.com/highcode-agentarts/agentarts_10_029.html)

HTTP 服务监听 **0.0.0.0:8080**，提供 `POST /invocations`，支持 JSON 入参以及 JSON 或 SSE 出参。SSE 使用 `Content-Type: text/event-stream`。健康检查为 `GET /ping`，就绪返回 HTTP 200 和 `{"status":"Healthy"}`；初始化时可返回 `Initing`，执行后台长任务时可返回 `HealthyBusy`。无需为当前单向 SSE 实现可选的 `/ws`。[HTTP 入站协议](https://support.huaweicloud.com/highcode-agentarts/agentarts_10_070.html)

## 路由：避免把平台前缀当作容器路径

| 平台调用路径 | 容器路由 |
| --- | --- |
| `/runtimes/{name}/invocations` | `/invocations` |
| `/runtimes/{name}/invocations/stream` | `/stream` |
| `/runtimes/{name}/invocations/ping` | `/ping` |

后两项要求部署配置 `runtime.invoke_config.url_match_type: PREFIX_MATCH`（控制台“前缀匹配”）。自定义调用会剥离 `/runtimes/{name}/invocations/` 前缀，故只实现容器 `/invocations/stream` 无法承接平台的 `/invocations/stream` 请求。建议 `/invocations` 为标准入口，`/stream` 为 SSE 自定义别名；本地兼容路径可以另加。[自定义路径调用](https://support.huaweicloud.com/highcode-agentarts/agentarts_10_131.html)

## 鉴权、会话与业务协议

平台网关负责鉴权和路由，业务请求体不被修改，响应结构由应用决定；因此现有业务字段可以保留，不需要改成官方示例中的 `message`。入站认证可选 API Key、IAM AK/SK 签名或 OAuth 2.0。API Key/OAuth 使用 `Authorization: Bearer …`；IAM 由签名 SDK 生成授权头。[调用概述](https://support.huaweicloud.com/highcode-agentarts/agentarts_10_130.html)

调用方携带 `X-Hw-Agentarts-Session-Id`，仅允许英文、数字、连字符及下划线，长度不超过 64；同一会话重复使用。IAM 调用还需 `X-Sdk-Content-Sha256: UNSIGNED-PAYLOAD`。业务正文中的会话字段与平台请求头需由调用方协调；平台会话 ID 本身不等于模型历史持久化。[自定义路径调用](https://support.huaweicloud.com/highcode-agentarts/agentarts_10_131.html)

## 发布配置

官方支持手动制作镜像、推送 SWR、控制台创建运行时，也支持 SDK 自动部署。运行时监听端口与容器保持 8080，启动命令可由 Dockerfile 提供，服务参数和密钥通过运行时环境变量配置。须完成账号服务授权，并为 MobileNet、Koo Search 和模型接口配置可达的出网网络；公网/私网选择取决于实际服务地址。具体 SWR 地址、运行时名称、地区和身份认证凭据必须来自部署环境，不应猜测。[部署第一个 Agent](https://support.huaweicloud.com/highcode-agentarts/agentarts_10_183.html)

本项目实现与测试建议：本地验证健康接口、标准 SSE 入口、`/stream` 别名、业务错误的 SSE 结束行为；ARM64 镜像验证通过后再发布，并用真实运行时接口做一次跨网关 SSE 验收。此处为工程建议，尚未确认云端网络连通性及实际网关超时。
