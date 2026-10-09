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
- 临时检索使用 `KOOSEARCH_MODE=stub`，不需要知识库地址或认证；恢复真实检索见 [Koo Search](koosearch.md)。
- 到 MobileNet、模型 endpoint 的出站网络；使用 `live` 时还需连通 Koo Search。
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

## 对接与安全边界

AgentArts 网关负责入口认证，当前 Java 服务不校验外层平台 Token。下游分类器使用 `X-API-Key`，模型凭据来自每次请求，真实 Koo Search 支持 AppCode 或 IAM Token。Token 文件每次检索重读，但没有自动申请或续期；未集成巫山、CCMS/CSMS、密码加解密或 CTS 业务审计。平台加密的 `E1-` 值不能直接作为分类器密钥。若部署需要这些能力，应单独明确并验收。

用户提供的音频工程文档在同一固定提交中存在接口差异，最终对接前仍需确认：

| 项目 | 当前实现与原 IO 示例 | 音频工程设计规格 |
| --- | --- | --- |
| 能力字段 | `chat_completions/create_response/fetch_response` | `chat_completions/responses_api/responses_get_fetch` |
| 能力查询返回 | SSE 内的 JSON 字符串 | 直接 JSON |
| 未知/不支持的 operation | HTTP 200 SSE，内层 code="400" | HTTP 400，E4001/E4007 |

这些差异是对接契约问题，不是 AgentArts 强制所有 Java 应用使用某种业务响应格式。本次目录清理保留现有协议。[固定参考提交](https://github.com/PartnerEcoDep/ai-hardware-subagent-audio-transcription/tree/d7202f1600185bd541b5d29327702de77a9e422e/docs)

运行时本地磁盘不能作为长期日志归档；平台日志保存在 LTS，持久文件需显式配置外部存储。[运行时说明](https://support.huaweicloud.com/highcode-agentarts/agentarts_10_029.html)
