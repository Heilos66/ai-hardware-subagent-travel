# AME 集成镜像交付（2026-10-26）

本镜像用于 AgentArts 验证 AME 本地知识库方案，包含 Java Agent、AME C++ 引擎、中文词典和本次对比使用的 52 个知识片段。镜像内容来自 `codex/ame-knowledge-latency` 工作区（包含未提交的 AME 集成文件），上游 AME 固定为 `26ce43e2b34be958b84edb4dcf834291ffa820b6`。

## AgentArts 配置

- 镜像：`swr.cn-southwest-2.myhuaweicloud.com/asr-test2/travel-agent:ame-20261026-01`。
- 架构：Linux ARM64；端口：8080；健康检查：`GET /ping`。
- 使用镜像默认启动入口 `python3 /app/start.py`，不要用 `java -jar` 覆盖，否则不会启动 AME。
- `AME_ENABLED=true`（默认）：先加载 AME，再启动 Java；检索地址及临时 Token 自动配置，忽略原有外部 RAGFlow 地址、数据集、Token 和重排配置。`RAGFLOW_TOP_K` 默认 5，已有平台值可覆盖。
- `AME_ENABLED=false`：不启动 AME，Java 使用原有环境配置；接回外部知识库需提供有效的 `RAGFLOW_URL`、`RAGFLOW_DATASET_ID`、`RAGFLOW_API_TOKEN` 等。
- 对话模型仍使用请求的 `model_config`；图像分类器仍使用原有平台环境配置。AME 只替换检索链路。
- AME 监听容器内回环地址 18087，不对外开放。临时索引在 `/tmp/ame-runtime`，容器重启重新生成；知识库更新需要重新打包。

当前模式不调用 CSS、MaaS embedding 或 MaaS rerank，不能视为保留这些组件后的同口径优化。尚未在 AgentArts 上发布或测量线上 P95；此前本机结果与适用边界见 [AME 验证报告](ame-knowledge-latency-20261026.md)。

## 可复现构建

```bash
python3 tools/ame-benchmark/container/prepare.py \
  --source /path/to/ame-dev-2.0 --out target/ame-release-context-new

docker buildx build --platform linux/arm64 \
  --provenance=false --sbom=false \
  --output type=docker,oci-mediatypes=false \
  -t travel-agent:ame-20261026-01 target/ame-release-context-new

python3 tools/ame-benchmark/container/smoke.py
```

Linux GCC 构建通过 `-include cstdint` 补齐上游遗漏的标准整数头声明，不修改上游源码。构建阶段安装 `cmake`、`g++` 和 `make`；运行阶段仅保留 JRE、Python 及运行库。

准备脚本校验上游提交，并构造独立构建上下文，不拷贝 `.env`。全部输入哈希见 `artifacts/ame-image-20261026/build-inputs.json`。镜像包含知识库正文，因此共享镜像也会共享这份知识库。

## 验证与发布记录

发布前 Maven 回归：254 项，253 通过、1 项需要真实 AME 服务的专项测试默认跳过，0 失败。容器验证使用真实 AME 与本地模拟对话模型，验证知识片段实际进入模型提示词；不产生 MaaS 计费请求。

最终推送摘要、容器验证结果及远端清单保存在 `artifacts/ame-image-20261026/`。

发布已完成：SWR 推送成功、远端 Docker V2 清单确认、ARM64 重新拉取成功。镜像 digest：`sha256:073f92e0719409db4b18313f1fc74f00fb27c5bd1a0a5d22ecf8a675ecc59222`。

容器冒烟测试通过：非 root 运行、健康检查、AME 开关、真实本地检索片段进入模型提示词、SSE 流式输出及诊断日志。模型使用本地模拟服务，本次未发布 AgentArts 运行时。测试输出中的 `ragflow` 是 Java 原有客户端的日志名称，启用模式实际访问容器内 AME。
