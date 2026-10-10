# AgentArts 部署

本项目使用 Java 21、Spring Boot 3.5.15、Spring AI 1.0.9，通过自定义 ARM64 镜像部署。启动命令由 Dockerfile 提供，容器以 UID 10001 运行，监听 `0.0.0.0:8080`，健康接口为 `GET /ping`。不需要 Python AgentArts SDK。[Java 迁移说明](https://support.huaweicloud.com/highcode-agentarts/agentarts_10_223.html)、[HTTP 入站协议](https://support.huaweicloud.com/highcode-agentarts/agentarts_10_070.html)

## 构建、验证和推送 SWR

先按 [验证指南](verification.md) 完成自动化测试。每次发布使用新标签，下面的标签只是示例；镜像需要更新时，应构建、推送并切换运行时版本。

```bash
TRAVEL_TAG=release-001
TRAVEL_IMAGE="swr.cn-southwest-2.myhuaweicloud.com/asr-test2/travel-agent:${TRAVEL_TAG}"

docker buildx build --platform linux/arm64 \
  --provenance=false --sbom=false \
  --output type=docker,oci-mediatypes=false \
  -t "travel-agent:${TRAVEL_TAG}" .

docker image inspect "travel-agent:${TRAVEL_TAG}" \
  --format '{{.Os}}/{{.Architecture}} {{.Config.User}}'
docker run --rm -p 8081:8080 --env-file .env "travel-agent:${TRAVEL_TAG}"
```

在另一终端执行 `./scripts/verify_logs.sh capabilities 8081`。验证完成后，在容器终端按 Ctrl+C 停止，再推送：

```bash
# 先执行 SWR 控制台“总览 → 登录指令”提供的命令，确认 Login Succeeded。
docker tag "travel-agent:${TRAVEL_TAG}" "$TRAVEL_IMAGE"
docker push "$TRAVEL_IMAGE"
docker pull --platform linux/arm64 "$TRAVEL_IMAGE"
docker buildx imagetools inspect --raw "$TRAVEL_IMAGE"
```

SWR 基础版需要 Docker V2 清单，本机曾用 `--load` 导出 OCI index，导致各层已上传但最终报 `Invalid image, fail to parse 'manifest.json'`。上述参数已实际验证，避免只以层的 `Pushed` 判断成功：需确认命令退出码、最终 digest、远端清单和重新拉取。[SWR 说明](https://support.huaweicloud.com/usermanual-swr/swr_01_0011.html)、[Docker 导出参数](https://docs.docker.com/build/exporters/oci-docker/)

镜像构建上下文仅含构建配置和 `src/main`；`.env`、`references/`、测试、文档和脚本不会进入镜像。Dockerfile 构建时跳过测试，不能替代发布前的 `./mvnw verify`。

## 运行时配置与调用

在 AgentArts 选择实际区域和 SWR 镜像，为运行时配置：

- 镜像默认 Java 启动命令、8080 端口和 `/ping` 健康检查。
- `.env.example` 中需要的环境变量；分类器地址为 `http://1.95.152.66:16000/mobilenetv3large`，使用可用的 `CLASSIFIER_API_KEY`。
- 知识库统一使用 RAGFlow，配置 `RAGFLOW_URL`、`RAGFLOW_DATASET_ID`、`RAGFLOW_API_TOKEN` 及重排模型，具体见 [RAGFlow 接入](ragflow-travel-agent-integration.md#本工程接入与验证)。Token 在运行时注入，不能依赖本机 .env 随镜像上传。
- 运行时需能访问 `172.16.0.154:9380`，私网配置见 [网络说明](agentarts-knowledge-network.md)；同时验证 MobileNet、模型 endpoint 的出站网络。
- 入站认证、日志记录及 LTS 授权，日志查看见 [日志说明](logging.md)。

上述平台配置应来自当前运行时；已有环境变量优先于镜像默认值。[部署说明](https://support.huaweicloud.com/highcode-agentarts/agentarts_10_031.html)

| 平台路径 | 容器路径 | 条件 |
| --- | --- | --- |
| `/runtimes/{name}/invocations` | `/invocations` | 标准入口 |
| `/runtimes/{name}/invocations/stream` | `/stream` | 开启 `PREFIX_MATCH` |
| `/runtimes/{name}/invocations/ping` | `/ping` | 开启 `PREFIX_MATCH` |

前缀路由会去掉平台路径前缀。本项目同时保留本地 `/invocations/stream` 别名。[自定义路径说明](https://support.huaweicloud.com/highcode-agentarts/agentarts_10_131.html)

API Key 鉴权调用示例；变量取自实际部署配置，`request.json` 按 [README](../README.md#请求与流式响应) 填写：

```bash
curl -N "${AGENTARTS_GATEWAY}/runtimes/${RUNTIME_NAME}/invocations?endpoint=Latest" \
  -H "Authorization: Bearer ${AGENTARTS_API_KEY}" \
  -H "X-Hw-Agentarts-Session-Id: ${SESSION_ID}" \
  -H 'Content-Type: application/json' \
  --data-binary @request.json
```

IAM 模式按平台签名规则调用。Session ID 是平台会话标识，不等于模型历史记录；本应用保持单轮问答。发布后选择正确版本，用新的合法 Session ID 验证。[调用说明](https://support.huaweicloud.com/highcode-agentarts/agentarts_10_130.html)

## IAM 与 API Key 支持情况

核对日期：2026-10-09。AgentArts 高代码运行时支持以下两种入站认证；认证由平台网关完成，再将请求转发给容器。[官方调用概述](https://support.huaweicloud.com/highcode-agentarts/agentarts_10_130.html)

| 运行时配置 | 调用方携带内容 | 官方指导 |
| --- | --- | --- |
| `authorizer_type: API_KEY` | `Authorization: Bearer <运行时 API Key>` | [API Key 调用实践](https://support.huaweicloud.com/bestpractice-agentarts/agentarts_06_0042.html) |
| `authorizer_type: IAM` | 使用调用方的 AK/SK，经签名 SDK 生成 `Authorization` | [IAM 调用实践](https://support.huaweicloud.com/bestpractice-agentarts/agentarts_06_0063.html) |

这里的“两种都支持”是平台提供两种选项。创建接口的 `identity_configuration.authorizer_type` 是单个字符串，因此不能据此认为同一个运行时默认同时接受 IAM 和 API Key。[创建运行时 API](https://support.huaweicloud.com/api-agentarts/CreateCoreRuntime.html)

IAM 实践使用 `V11-HMAC-SHA256` 和实际区域（本项目为 `cn-southwest-2`）；调用运行时数据面时设置 `X-Sdk-Content-Sha256: UNSIGNED-PAYLOAD`，由 SDK 生成 `X-Sdk-Date` 和签名头，同时携带会话 ID。签名应随请求生成，不能用固定 Bearer 字符串替代。API Key 则从运行时详情的权限与访问控制入口，进入 AgentIdentity 获取。[IAM 签名示例](https://support.huaweicloud.com/bestpractice-agentarts/agentarts_06_0063.html)、[API Key 获取方式](https://support.huaweicloud.com/bestpractice-agentarts/agentarts_06_0042.html)

文旅服务的 [AgentController](../src/main/java/com/heilos/travel/AgentController.java) 不做入站验签，符合网关先鉴权的托管方式；仅为选择 IAM 或 API Key，无需引入巫山或重新构建镜像。直接访问本地容器时没有这层平台认证。运行时 API Key、`CLASSIFIER_API_KEY` 和 `model_config.auth_token` 分别用于平台入口、图片分类器和模型，互不替代。

音频工程的 IAM 代码属于同仓库附带的调用、部署工具，不代表业务服务端自行验签。2026-10-10 按同一固定提交补充核对：

- [app.py](https://github.com/PartnerEcoDep/ai-hardware-subagent-audio-transcription/blob/d7202f1600185bd541b5d29327702de77a9e422e/app.py#L26-L55) 是云端业务入口：用 `AgentArtsRuntimeApp` 接收请求，转交 `handle_invocation`，没有导入调用脚本或自行 IAM 验签；`context.user_id` 仅用于任务归属检查。[main.py](https://github.com/PartnerEcoDep/ai-hardware-subagent-audio-transcription/blob/d7202f1600185bd541b5d29327702de77a9e422e/main.py#L2-L21) 是本地转写测试入口。
- [invoke.py](https://github.com/PartnerEcoDep/ai-hardware-subagent-audio-transcription/blob/d7202f1600185bd541b5d29327702de77a9e422e/invoke.py#L35-L68)、[test_agent_iam.sh](https://github.com/PartnerEcoDep/ai-hardware-subagent-audio-transcription/blob/d7202f1600185bd541b5d29327702de77a9e422e/test_agent_iam.sh#L36-L39)、[postman_proxy.py](https://github.com/PartnerEcoDep/ai-hardware-subagent-audio-transcription/blob/d7202f1600185bd541b5d29327702de77a9e422e/postman_proxy.py#L27-L65) 是调用机上的脚本/代理，读取调用方 AK/SK，通过 `RuntimeClient` 的 V11 签名调用远端运行时。
- [scripts/deploy.sh](https://github.com/PartnerEcoDep/ai-hardware-subagent-audio-transcription/blob/d7202f1600185bd541b5d29327702de77a9e422e/scripts/deploy.sh#L91-L97) 中的 AK/SK 用于部署机执行 `agentarts configure/launch`，指定服务入口 `app:app`。

同一个 `agentarts-sdk` 同时提供服务端应用和客户端调用能力。文旅工程提供独立的 [IAM 测试脚本](../scripts/test_agentarts_iam.py)，使用官方 APIG SDK 签名，并实时读取本服务的 SSE；服务端业务保持现状。不能直接照搬上述聚合 JSON 的 Postman 代理，否则会丢失当前 SSE 增量返回方式。安装、凭据配置及正反向测试见 [IAM 验证步骤](verification.md#iam-运行时验证)。

用户提供的音频仓库在固定提交 `d7202f1` 内存在矛盾：[构建部署指导手册](https://github.com/PartnerEcoDep/ai-hardware-subagent-audio-transcription/blob/d7202f1600185bd541b5d29327702de77a9e422e/docs/构建部署指导手册.md#L166-L180) 给出 IAM 默认、API Key 可选的配置，[DEPLOY.md](https://github.com/PartnerEcoDep/ai-hardware-subagent-audio-transcription/blob/d7202f1600185bd541b5d29327702de77a9e422e/docs/DEPLOY.md#L157-L170) 使用 Bearer 调用，[接口测试报告](https://github.com/PartnerEcoDep/ai-hardware-subagent-audio-transcription/blob/d7202f1600185bd541b5d29327702de77a9e422e/docs/接口测试报告.md#L3-L29) 记录 API Key 运行时返回 200；但 [接口调用指导手册](https://github.com/PartnerEcoDep/ai-hardware-subagent-audio-transcription/blob/d7202f1600185bd541b5d29327702de77a9e422e/docs/接口调用指导手册.md#L377-L408) 又称必须 IAM，且列出两个不同运行时。不能把该排错建议解释为平台不支持 API Key，应以官方文档和目标运行时实际配置为准。

还需区分运行时执行与资源管理接口：当前区域的 [认证分类表](https://support.huaweicloud.com/api-agentarts/agentarts_07_0005.html) 将运行时管理列为 AK/SK，将运行时执行列为 API Key、AK/SK、OAuth 2.0。旧版华北北京四文档中的 `X-Auth-Token` 不能直接套用到这里的 `/runtimes/{name}/invocations`。

客户端已通过本地模拟网关测试，尚未执行真实文旅运行时 IAM 签名联调。若要验收 IAM，可使用同一镜像另外创建 IAM 运行时，验证合法签名成功、无效签名被网关拒绝，并验证真实问答的流式响应；保留现有 API Key 运行时，避免影响当前调用。

## 对接与安全边界

AgentArts 网关负责入口认证，当前 Java 服务不校验外层平台 Token。下游分类器使用 `X-API-Key`，模型凭据来自每次请求，RAGFlow 使用 Bearer API Token。检索 Token 文件每次查询重读，但没有自动申请或续期；未集成巫山、CCMS/CSMS、密码加解密或 CTS 业务审计。平台加密的 `E1-` 值不能直接作为分类器或 RAGFlow 的凭据。若部署需要这些能力，应单独明确并验收。

用户提供的音频工程文档在同一固定提交中存在接口差异，最终对接前仍需确认：

| 项目 | 当前实现与原 IO 示例 | 音频工程设计规格 |
| --- | --- | --- |
| 能力字段 | `chat_completions/create_response/fetch_response` | `chat_completions/responses_api/responses_get_fetch` |
| 能力查询返回 | SSE 内的 JSON 字符串 | 直接 JSON |
| 未知/不支持的 operation | HTTP 200 SSE，内层 code="400" | HTTP 400，E4001/E4007 |

这些差异是对接契约问题，不是 AgentArts 强制所有 Java 应用使用某种业务响应格式。本次目录清理保留现有协议。[固定参考提交](https://github.com/PartnerEcoDep/ai-hardware-subagent-audio-transcription/tree/d7202f1600185bd541b5d29327702de77a9e422e/docs)

运行时本地磁盘不能作为长期日志归档；平台日志保存在 LTS，持久文件需显式配置外部存储。[运行时说明](https://support.huaweicloud.com/highcode-agentarts/agentarts_10_029.html)
