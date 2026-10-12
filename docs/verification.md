# 验证指南

命令从工程根目录执行。验证依次覆盖自动化回归、本地真实调用、镜像、SWR 和 AgentArts；前一阶段成功不表示后一阶段已验收。

## 自动化回归

配置 JDK 21 后执行：

```bash
./mvnw verify
```

预期 `BUILD SUCCESS`，210 项测试，failures/errors/skipped 均为 0。测试使用本机随机端口模拟服务，不读取实际业务凭据。覆盖三条路由、0.7 边界、模型参数透传、SSE 分片和结束标记、思考增量、超时/取消及日志隔离；59 项 RAGFlow 专项用例覆盖检索协议、片段过滤、错误、重试总时限、取消和 Token 文件轮换。另有 4 项配置回归和 21 项诊断用例，验证缺失变量定位、结果与空结果区分、实际模型请求体、凭据/图片脱敏、日志截断、并发请求关联和日志失败隔离。

TTFT 另增加 12 项用例，并扩展原有路由/并发/日志故障测试：覆盖首个正文或思考片段、过滤空片段、在完整响应前打点、各阶段与 TTFT 的加总关系、JSON 回退及首片前后失败/超时/取消。

仅检查日志相关改动可以执行 `./scripts/verify_logs.sh test`。Maven 可通过 `MAVEN_USER_HOME` 和 `MAVEN_REPO_LOCAL` 指定缓存目录，缓存齐全时使用 `test -o` / `build -o`。

## 本地启动与请求

```bash
# 终端一：打包后启动；默认 8081，使用 .env 中的 RAGFlow 配置
./scripts/verify_logs.sh build
./scripts/verify_logs.sh start

# 终端二：以下两个命令不访问业务服务
./scripts/verify_logs.sh capabilities
./scripts/verify_logs.sh failure

# 使用 references/io_spec.md 的真实模型与图片配置
./scripts/verify_logs.sh request
./scripts/verify_logs.sh logs
```

`request` 会按业务分支调用实际分类器、RAGFlow 和模型；文本/高置信度图片链路需要可用的知识库网络和凭据。默认读取本地 IO 文件，不打印或转发文档中的网关鉴权头；先用 `python3 scripts/test_io_spec.py --check` 校验格式。自定义端口/文件使用 `request 8082 /实际路径/io_spec.md`。更多参数见 `./scripts/verify_logs.sh help`。

验证点：

1. `/ping` 返回 HTTP 200、`{"status":"Healthy"}`。
2. HTTP 200 SSE 内包含完整 `workflow_finished` 和 `end`；解析 `responseContent` 字符串后业务 `code` 为 `"0"`，不能只看 HTTP 状态。
3. `message.data.text` 是正文增量，分别拼接 `reasoning_content`；模型阶段至少有多个实时片段时，才验证了增量传输。最终正文只汇总 text。
4. 按 SSE 的 `execution_id` 查日志，每个实际阶段和 total 均有记录；错误和取消对应 failed/cancelled。真实调用未完成时中断客户端，检查上游取消和日志结果。
5. 文本为 `validate → search_text → model → total`；高置信度且名称非空的图片增加 classify/search_image；视觉分支为 `validate → classify → model → total`。
6. 在模型首个非空正文/思考片段到达时应有一条 `workflow_ttft`，无需等生成结束。核对此前各阶段 `_ms` 与阶段日志一致，`stages_sum_ms + overhead_ms ≈ ttft_ms`。模型阶段只取 `model_to_first_token_ms` 加总，不能取生成结束后的完整 model 耗时。字段与例子见 [TTFT 说明](logging.md#工作流-ttft)。

能力查询只产生 validate、total。`failure` 故意发送非法 operation，收到预期业务错误时脚本返回 0。日志在 `target/local-logs/application.log`，按需执行 `logs 实际execution_id`。各阶段计时范围和云端查询见 [日志说明](logging.md)。在启动终端按 Ctrl+C 停止服务。

检索诊断验证：在 `.env` 设置 `RAGFLOW_LOG_CONTENT=true`、`MODEL_LOG_REQUEST=true` 后重启。发送一次文本或高置信度图片请求，按同一 execution_id 找 `ragflow_result`、`ragflow_content` 和 `model_request`：比对返回/保留数量、片段正文和 `body.messages` 中的知识上下文（代理协议为 `body.model_request.messages`）。`truncated=true` 时看到的是预览，可将 `DIAGNOSTIC_LOG_MAX_CHARS` 调高到最多 65536 后重新调用；实际发出的请求不受日志截断影响。视觉分支不检索，只有 model_request。验证完可关闭两个正文开关并重启。

若 AgentArts 返回“RAGFlow地址或知识库ID未配置”，先按 [配置排查](logging.md#ragflow-地址或知识库-id-未配置) 补齐运行时环境变量。新版本在启动日志 `ragflow_config` 和失败日志 `ragflow_failed` 输出配置是否存在；`attempts=0` 表示尚未请求 RAGFlow，不能按网络或知识库服务异常处理。

2026-10-10 诊断改动已通过全部 177 项 Java 回归；最后的日志故障隔离调整另行通过 23 项配置/诊断回归并重新打包。最终 JAR 使用本机模拟 RAGFlow 与模型启动，分别验证缺少配置和配置正确两种场景：健康接口、SSE、真实 Logback 异步控制台输出、execution_id 关联及脚本筛选均通过，日志中的模型请求体与模拟服务实际收到的请求一致。本次诊断改动尚未构建或发布新镜像，也未修改 AgentArts 环境变量。

2026-10-11 TTFT 改动已通过全部 189 项 Java 回归并打包。用最终 JAR 对接本机模拟 RAGFlow 和延迟流式模型，实际控制台仅产生一条首思考片段 TTFT；阶段合计与额外开销之和吻合，打点早于模型完整输出，日志筛选脚本能同时查看 TTFT、阶段和诊断日志。本次 TTFT 改动尚未构建/推送镜像或修改 AgentArts 运行时。

## IAM 运行时验证

使用 [test_agentarts_iam.py](../scripts/test_agentarts_iam.py) 在调用电脑上验证已部署的 **IAM 运行时**。AK/SK 属于调用方，不配置到文旅容器；Java 服务和镜像无需因此修改。先在控制台确认入站认证为 IAM、区域及目标 endpoint 正确，并为调用身份授予相应权限。API Key 运行时仍按 [部署说明](deployment.md#运行时配置与调用) 使用 Bearer 调用。[官方 IAM 调用实践](https://support.huaweicloud.com/bestpractice-agentarts/agentarts_06_0063.html)

### 1. 准备官方签名 SDK

需要 Python 3.10+。脚本使用官方 APIG SDK 的 `V11-HMAC-SHA256` 签名器和 Python 标准库传输，无需安装 requests 或 AgentArts SDK。首次从 [官方 Python 签名 SDK 指南](https://support.huaweicloud.com/devg-apisign/api-sign-sdk-python.html) 提供的地址下载：

```bash
mkdir -p target/tools
curl --fail --location \
  'https://obs.cn-north-1.myhuaweicloud.com/apig-sdk/ApiGateway-python-sdk.zip' \
  --output target/tools/ApiGateway-python-sdk.zip
unzip -q -o target/tools/ApiGateway-python-sdk.zip -d target/tools
export APIG_SDK_PATH="$PWD/target/tools/ApiGateway-python-sdk-2.0.7"
```

2026-10-10 下载到的目录为 `ApiGateway-python-sdk-2.0.7`；若官方更新版本，将路径替换为解压后**直接包含 `apig_sdk/` 的目录**。也可用 `--sdk-dir /实际目录` 指定已有 SDK。SDK 存在被 Git 忽略的 `target/` 下，不进入镜像；清理 target 后需要重新准备。

### 2. 配置调用方凭据和地址

在执行脚本的同一终端设置以下环境变量，占位值替换为实际值：

```bash
export HUAWEICLOUD_SDK_AK='调用方的 Access Key'
export HUAWEICLOUD_SDK_SK='调用方的 Secret Key'
export AGENTARTS_REGION='cn-southwest-2'
export AGENTARTS_RUNTIME_URL='https://实际网关.cn-southwest-2.huaweicloud-agentarts.com/runtimes/实际IAM运行时/invocations?endpoint=Latest'
```

使用控制台复制的实际 HTTPS 调用 URL；路径中的运行时名称、区域和 endpoint 都应属于待验收部署。脚本保留查询参数参与签名与发送，不擅自移除 `endpoint`。每次调用默认生成新的 Session ID，可用 `--session-id` 指定。当前支持长期 AK/SK；临时 AK/SK 加 Security Token 的方式尚未实现。

不要将 AK/SK 填入 `io_spec.md`、请求体或运行时环境变量，也不要将真实值提交到 Git。脚本不打印请求体、AK/SK、Authorization 或网关错误正文；终端会实时显示应用返回的 SSE 内容。

### 3. 依次验证配置、合法签名和错误签名

```bash
# 只解析配置并生成签名，不联网；不能证明 AK/SK 有效或有调用权限
python3 scripts/test_agentarts_iam.py --check

# 合法签名：默认查询能力，不调用分类器、检索或模型
python3 scripts/test_agentarts_iam.py --capabilities

# 反向测试：能力请求的签名被故意破坏，预期网关返回 HTTP 401/403
python3 scripts/test_agentarts_iam.py --expect-auth-rejection
```

合法调用应返回 HTTP 200、SSE `workflow_finished` 中 `status.code=1`、能力结果 `chat_completions=true`，并以 `end` 完整结束，脚本退出码为 0。反向测试只有收到 HTTP 401/403 才返回 0；若错误签名仍被接受、出现网络错误或 HTTP 5xx，则失败。必须先完成同一运行时的合法签名测试：单独得到 403 也可能是权限不足，不能据此证明签名验收通过。

### 4. 验证真实业务和流式返回

```bash
# 使用现有本地 IO 示例；仅取 JSON 请求体，不转发其中的 Bearer 鉴权头
python3 scripts/test_agentarts_iam.py --spec references/io_spec.md --check
python3 scripts/test_agentarts_iam.py --spec references/io_spec.md

# 或使用自备 JSON 请求文件（含 inputs，格式见 README）
python3 scripts/test_agentarts_iam.py --json /实际路径/request.json
```

这一步会调用实际业务服务，分类器密钥及模型 `auth_token` 仍分别配置。脚本按行即时输出 SSE，保留 text 和 reasoning_content；应在工作流完成前看到增量。完整成功同时要求 `workflow_finished.data.status.code=1`、`responseContent` 内业务 `code="0"` 和 `end`。HTTP 200 内的业务失败、格式错误或提前断流都会返回非零。按返回的 execution_id 在 LTS 核对同次请求日志。`--timeout` 默认 150 秒，是连接/单次读取等待上限，不是工作流总时限；Ctrl+C 终止客户端。

| 退出码 | 意义与排查 |
| --- | --- |
| 0 | 普通调用完整成功；或 `--check` 本地配置检查通过；或反向测试收到预期拒绝，具体见命令输出 |
| 1 | 网络/HTTP/业务/SSE 失败；业务错误查看 responseContent，结合运行时日志排查 |
| 2 | 本机参数、凭据缺失、SDK 或请求文件配置错误，尚未发出请求 |
| 3 | 普通调用收到 HTTP 401/403，检查 IAM 模式、AK/SK、区域、权限和本机时间 |
| 130 | 用户按 Ctrl+C 中止 |

### 5. 本地自动化与验收边界

配置好 `APIG_SDK_PATH` 后执行，不需要真实 AK/SK：

```bash
python3 -m unittest discover -s scripts/tests -p 'test_*.py' -v
```

2026-10-10 本地 14 项测试通过。模拟网关监听本机随机端口，使用测试凭据及官方 SDK 校验实际 HTTP 请求，覆盖签名/查询参数、中文正文、错误签名、认证与业务错误区分、实时 SSE、断流/超时、重定向拒绝和凭据不回显。该结果验证客户端行为，**尚未验证真实 AgentArts IAM 权限与网关接入**；需按上述步骤在实际 IAM 运行时完成正反向调用后验收。

## 真实检索、镜像及云端验收

- 真实检索：按 [RAGFlow 接入](ragflow-travel-agent-integration.md#本工程接入与验证) 配置知识库地址、ID 与 Token，用日志脚本、加载了环境变量的 JAR 或容器启动。分别验证纯文本、高置信度图片的检索链路和低置信度图片的视觉链路。
- 镜像与 SWR：按 [部署说明](deployment.md) 构建 ARM64 Docker V2 镜像，核对非 root 用户、健康和三条业务路径，再推送、读回 digest 并实际拉取。
- AgentArts：确认实际运行版本、入站鉴权及出站网络；对云端接口验证能力、文本/图片路由、SSE 首片、思考增量、超时和断连。用一次请求的 execution_id 在 LTS 找到相同日志。
- IAM、凭据轮转、CTS 或其他公司安全要求独立验收，不能以一次问答成功替代。

### RAGFlow 接入验证（2026-10-10）

依据本地 `ragflow-travel-agent-integration.md`，已实现异步检索接入，知识检索统一使用 RAGFlow，本地 .env 和环境模板仅保留所需配置。初次接入时 154 项 Java 测试全部通过，JAR 构建成功；后续已推送 RAGFlow 镜像 `swr.cn-southwest-2.myhuaweicloud.com/asr-test2/travel-agent:ragflow-20261010-1556-3cff43e`，它不包含本次新增诊断日志。真实 API 的前置验证与接入后复测均未收到 HTTP 响应，不能将模拟测试通过视为知识库验收通过。

| 检查 | 本机实测结果 |
| --- | --- |
| TCP 连接 | 约 5ms 建立；这不足以证明远端 RAGFlow HTTP 服务可用 |
| 带文档凭据的检索 POST | 查询“兵马俑是什么时候发现的”，使用文档的 dataset、Top3、0.2 阈值及 rerank；在收到 HTTP 响应前连接被关闭，`RemoteDisconnected` |
| 不带凭据的 HTTP 对照 | curl 返回 52 / `Empty reply from server`，HTTP 状态为 000，约 5 秒结束 |
| 本机目标路由 | 经 `utun24`，网关 `172.18.0.1`；尚未证实通往目标 VPC 的实际链路 |
| 接入后的真实检索复测 | 加载本地 RAGFLOW 配置发送同一中文查询，5007ms 后 RemoteDisconnected，仍未收到 HTTP 响应 |

凭据、知识原文未写入验证日志。当前结果不能证明 Token 无效，也不能断言知识库服务本身损坏。下一步需要从同 VPC 的 ECS/运行时执行相同查询，或提供本机可用的访问通道；验收应分别检查 HTTP 状态、业务 code=0、有效 chunks、四类文物的相关性、中文问题召回、无命中和无效认证场景。

工程接入复用了异步 WebClient、现有系统提示词和流式模型调用，仅适配检索请求及 chunks。相比参考示例，已应用整个检索过程的超时、仅对临时故障重试、明确区分业务失败与空结果，并保留上下文长度限制；没有引入同步 RestClient、`.call()` 或“回答带来源文档”的提示词。

另外，文档将 keyword 描述为“true=BM25、false=纯向量、不传=混合”并不准确。官方 v0.27.2 文档将其描述为关键词匹配开关，默认 false；向量与词项相似度占比由 `vector_similarity_weight` 控制。不能仅凭 keyword 的布尔值判定纯向量或混合检索。[官方检索参数](https://github.com/infiniflow/ragflow/blob/v0.27.2/docs/references/http_api_reference.md#retrieve-chunks)

### 候选数与重排开关验证（2026-10-12）

按用户新提供的接入说明，新增 `RAGFLOW_USE_RERANK`、`RAGFLOW_KNN_TOP_K`、`RAGFLOW_KNN_NUM_CANDIDATES`、`RAGFLOW_RERANK_CANDIDATES_COUNT`。默认 true 和 64/128/32；重排仅在开关为 true 且 ID 非空时启用，显式空值/null 时省略对应候选字段。新增 19 项检索用例和 2 项诊断用例，覆盖默认/自定义参数、三条业务路径、重排关闭、ID 缺失、独立省略/整体回滚、非法输入及日志与实际请求一致；保持现有重试、超时、取消、SSE 和 TTFT。

全部 210 项 Java 回归通过，最终 JAR 打包成功。使用该 JAR 对接本机模拟 RAGFlow 和流式模型，分别以默认推荐值、关闭重排、显式省略全部候选数启动，三种配置均通过环境变量绑定、实际检索请求体、启动诊断日志、SSE 完整返回和 TTFT 关联检查。本次未验证真实 RAGFlow 的召回与性能，也未构建或发布新镜像。

在目标运行时验收时，用同一批查询对比默认推荐值、候选数全部省略、重排关闭三种配置。每次更改配置后启动新实例/版本，在 `ragflow_config` 核对实际参数，再比对检索 Top3、顺序、正文与无命中情况，结合 search_text/search_image、TTFT、total 评估耗时。提供方的 56-chunk 库结果不能代替当前部署的召回与性能验收。本次不修改服务端索引、语料、模型或云端运行时配置。

## 历史验证基线（2026-10-09，旧版）

以下记录早期未接真实知识库的版本，只作为历史样本，不是当前 RAGFlow 实现的验收结果。

| 项目 | 结果 |
| --- | --- |
| Java 回归 | 116 项通过 |
| ARM64 容器 | 三条 mock 业务分支、流式响应、异步耗时日志通过；Java 21、UID 10001 |
| 本地文本模型真实调用 | deepseek-v4-flash；22 个正文、32 个思考片段，业务 code="0" |
| 本地图片真实调用 | 分类服务成功后走 deepseek-v4.1-flash 视觉分支；10 个正文、137 个思考片段，业务 code="0" |
| 正文一致性 | 两次真实调用的增量拼接均与最终答案一致 |
| 文本耗时样本 | 检索 166.341ms、模型 3505.288ms、total 3693.020ms |
| 图片耗时样本 | 分类 649.743ms、模型 21943.841ms、total 22595.319ms |
| 后续待验证 | 当前 RAGFlow 真实链路、高置信度图片的完整真实链路、AgentArts 部署/网关及 LTS 实际采集 |

已推送并重新拉取的镜像：

```text
swr.cn-southwest-2.myhuaweicloud.com/asr-test2/travel-agent:timing-stub-20261009-1500
sha256:3aa6922914d83ea415db1986cb3b316f32a3b75b90bb0ee19d55f96afd706f71
```

该镜像包含分类器地址更新、打点和检索占位；后续提示词及源码清理需在下次发布时重新构建。耗时是单次样本，不代表均值或 P95。目录整理已清理历史临时日志和构建产物，此处保留验证摘要；以后构建和本地验证会重新生成 `target/`。
