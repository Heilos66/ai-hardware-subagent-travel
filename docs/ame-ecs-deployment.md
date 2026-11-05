# AME 独立 ECS 服务

本分支从 `main / d7d93fb` 创建。Agent 仍使用 Java 21 / Spring AI 1.0，通过 HTTP 访问独立 AME；Agent 镜像不包含 Python、C++ 或知识数据。

当前新 ECS 为 Huawei Cloud EulerOS 3.0 / x86_64，已能联网，使用 [Git 部署指南](ame-ecs-git-deployment.md)。[OBS 离线部署指南](ame-private-ecs-obs-deployment.md) 保留为历史备用方案。下面记录的是已有 Ubuntu 评测 ECS 的部署，不代表新 HCE ECS 已完成验证。

## 本次部署

| 项目 | 值 |
| --- | --- |
| ECS | `1.95.196.125`，Ubuntu 22.04，x86_64 |
| 私网地址 | `192.168.0.161` |
| 服务 | `travel-ame.service`，开机启动、异常自动重启 |
| 发布目录 | `/opt/travel-ame/releases/20261104-ame-graph` |
| 当前版本链接 | `/opt/travel-ame/current` |
| 检索端点 | `http://1.95.196.125:18087/api/v1/retrieval`（公网规则待配置，见下文） |
| 健康检查 | `GET /health`，就绪 200，进程失效 503 |
| 知识库 ID | `ame-travel` |
| Token | ECS `/etc/travel-ame/token`；通过 systemd credential 注入 |
| Agent 本机配置 | `target/ame-ecs-deploy-20261104/agent.env`，权限 600，Git 忽略 |
| 知识数据 | 先前评测的 4 份文档、52 个切片；不是对 RAGFlow 的实时同步 |
| 资源上限 | CPU 1 核、内存 2 GiB；专用动态用户运行 |

AME 固定到 [arrayforward/ame 的提交 df68cbf](https://github.com/arrayforward/ame/tree/df68cbf1f2742095901ec97434507d76cba2f61d)，使用 graph + 分词词典，`lex_topk=30`、`soft_min_sim=0.2`、depth=2。没有在线 embedding、远端 rerank 或 cheap rerank。源码文件校验与语料 SHA-256 保存在发布目录 `manifest.json` / `upstream-sha256.json`。许可证见 [AME-LICENSE](../deploy/ame/AME-LICENSE)。

当前源数据来自本地 `artifacts/ame-quality-20261030/corpus.json` 和 ECS 既有评测数据；发布包单独携带语料，不将业务知识写入源代码仓库。修改语料后需新建版本并重启导入。每次启动在内存中重建图，词典缓存位于 `/var/lib/travel-ame/`。

## 网络状态与鉴权

已验证 ECS 本机访问和本机 SSH 隧道访问。当前共享安全组未放行 18087；用户选择限定来源 IP/CIDR，等待提供 AgentArts 的实际 NAT 出口地址。未添加全网入站规则，不能将上面的公网 URL 视为已可用。

收到来源地址后，应创建仅作用于该 ECS 的独立安全组，新增 TCP 18087、来源为指定 IP/CIDR 的入站规则，再附加到 ECS，保留原共享安全组。可参考华为云的 [安全组规则 API](https://support.huaweicloud.com/api-vpc/vpc_sg01_0005.html)。本机测试的出口 IP 与 AgentArts 出口可能不同，需分别验证。

接口使用 `Authorization: Bearer <AME Token>`。Token 与 RAGFlow Token 无关，部署时随机生成，代码、日志和镜像不包含凭据。当前端点为 HTTP，Bearer 和正文未做传输加密；面向正式使用应接入可信 HTTPS 证书/网关，或使用私网。不得用禁用证书校验代替可信 HTTPS。

## Agent 配置

```dotenv
KNOWLEDGE_BACKEND=ame
RAGFLOW_ENABLED=true
RAGFLOW_URL=http://1.95.196.125:18087/api/v1/retrieval
RAGFLOW_DATASET_ID=ame-travel
RAGFLOW_RETRIES=0
RAGFLOW_TOP_K=3
RAGFLOW_TIMEOUT=10
```

另配置 `RAGFLOW_API_TOKEN`，或者挂载 Token 文件并配置 `RAGFLOW_API_TOKEN_FILE`。不要将 ECS 文件路径直接当成 AgentArts 容器内的路径；必须在调用方实际可读。含 Token 的本机配置文件可用于核对，不应提交或打包。

`RAGFLOW_ENABLED=false` 仍表示跳过检索。AME 模式忽略 KNN、rerank 及 RAGFlow 相似度阈值；协议 `similarity_threshold=0`，客户端保留有限数值分数，不套用 0～1 的过滤。切换默认后端不影响分类阈值 0.7、模型参数透传或 SSE。详见 [环境变量文档](environment-variables.md#独立-ame-后端)。

部署包含本分支改动的 Agent 镜像并配置环境变量后，还须使用新的 AgentArts Session ID 做云端验证；本次没有修改 AgentArts 运行时版本。

## 服务构建与发布

ECS 需有 Python 3.10+、CMake 3.16+、C++17 编译器及 systemd。复制 `deploy/ame/` 到一个新的 `/opt/travel-ame/releases/<版本>`，提供 `corpus.json`（数组，每项包含非空 `id`、`content`、`document`）和固定 AME 源码。不要覆盖运行中的发布目录。

```bash
# 在新发布目录执行，首次准备源码可使用下面命令。
git clone https://github.com/arrayforward/ame.git upstream
git -C upstream checkout df68cbf1f2742095901ec97434507d76cba2f61d
bash build.sh ./upstream
python3 -B -m unittest discover -s tests -v
# corpus.json 必须预先放好；install.sh 只管理 travel-ame 服务。
sudo bash install.sh "$PWD"
curl --fail http://127.0.0.1:18087/health
```

首次导入可能需数十秒；systemd 显示 active 不等于 HTTP 已就绪。`build.sh` 要求 git 版本和已跟踪文件一致。本次复用了 ECS 已有上游源码，逐一比较 285 个文件 SHA-256 后构建。CMake 使用 `-include cstdint` 补足固定上游版本的缺失头文件，不修改上游源码。

服务最多接受 32 个连接、8 个等待/执行中的检索；单 native worker 串行处理，不能据此宣称支持 8 路并行计算。单请求服务端预算 8 秒，繁忙返回 503；native worker 超时会被终止，避免旧响应被后续请求误用，systemd 会重启服务并重新导入。客户端取消 HTTP 后，正在执行的 native 查询未必立即停止，但受服务端时限限制。

## 验证结果与运维

2026-11-04 实测：

- Java：309 项测试通过，包括现有 RAGFlow 回归、AME 请求契约、负分处理、关闭检索、分类分流及 TTFT。
- Python adapter：本机与 ECS 各 9 项测试通过，包括错误 Token、请求边界、队列满、worker 超时和失效健康检查。
- 重启恢复：服务重新就绪，内存约 876 MiB；原有模型、检索和 Docker 服务仍 active。
- ECS 真实 AME：正常检索 200 / 3 个切片；10000 字输入 200；10001 字 400；错误 Token 401；错误知识库 ID 400。
- JAR → SSH 隧道 → 真实 AME：文本、高置信度图片、低置信度图片三条路径通过；检索知识进入文本模型请求，流式输出及 TTFT 正常。分类器与模型为本地 mock，不代表实际模型质量或 AgentArts 网络已验收。
- 单次短查询服务端召回约 23 ms；这是冒烟样本，不是并发性能基准。现有评测中库外问题也可能返回片段，因此接通服务不等于业务召回质量验收。

本地验证材料位于 `target/ame-ecs-verification/` 和 `target/ame-ecs-deploy-20261104/`；其中 `agent.env` 含 Token，其余报告不包含 Token。

```bash
# ECS 上查看服务及日志
systemctl status travel-ame --no-pager
journalctl -u travel-ame -f
# 停止该服务（不影响其他检索/模型服务）
sudo systemctl stop travel-ame
# 更新语料、升级或回滚时，将 current 切到对应完整发布目录后重启
sudo systemctl restart travel-ame
```

服务端日志仅含状态、切片数和耗时。Agent 的 `ame_result` 含召回/排队耗时，`search_mode=ame` 保留在阶段与 TTFT 日志；详细规则见 [日志文档](logging.md#ame-独立检索日志)。
