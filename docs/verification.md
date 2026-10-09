# 验证指南

命令从工程根目录执行。验证依次覆盖自动化回归、本地真实调用、镜像、SWR 和 AgentArts；前一阶段成功不表示后一阶段已验收。

## 自动化回归

配置 JDK 21 后执行：

```bash
./mvnw verify
```

预期 `BUILD SUCCESS`，116 项测试，failures/errors/skipped 均为 0。测试使用本机随机端口模拟服务，不读取实际业务凭据。覆盖三条路由、0.7 边界、模型参数透传、SSE 分片和结束标记、思考增量、超时/取消、日志隔离及 150ms 非阻塞检索占位。

仅检查日志相关改动可以执行 `./scripts/verify_logs.sh test`。Maven 可通过 `MAVEN_USER_HOME` 和 `MAVEN_REPO_LOCAL` 指定缓存目录，缓存齐全时使用 `test -o` / `build -o`。

## 本地启动与请求

```bash
# 终端一：打包后启动；默认 8081，固定 stub 检索
./scripts/verify_logs.sh build
./scripts/verify_logs.sh start

# 终端二：以下两个命令不访问业务服务
./scripts/verify_logs.sh capabilities
./scripts/verify_logs.sh failure

# 使用 references/io_spec.md 的真实模型与图片配置
./scripts/verify_logs.sh request
./scripts/verify_logs.sh logs
```

`request` 会调用实际分类器或模型。默认读取本地 IO 文件，不打印或转发文档中的网关鉴权头；先用 `python3 scripts/test_io_spec.py --check` 校验格式。自定义端口/文件使用 `request 8082 /实际路径/io_spec.md`。更多参数见 `./scripts/verify_logs.sh help`。

验证点：

1. `/ping` 返回 HTTP 200、`{"status":"Healthy"}`。
2. HTTP 200 SSE 内包含完整 `workflow_finished` 和 `end`；解析 `responseContent` 字符串后业务 `code` 为 `"0"`，不能只看 HTTP 状态。
3. `message.data.text` 是正文增量，分别拼接 `reasoning_content`；模型阶段至少有多个实时片段时，才验证了增量传输。最终正文只汇总 text。
4. 按 SSE 的 `execution_id` 查日志，每个实际阶段和 total 均有记录；错误和取消对应 failed/cancelled。真实调用未完成时中断客户端，检查上游取消和日志结果。
5. 文本为 `validate → search_text → model → total`；高置信度且名称非空的图片增加 classify/search_image；视觉分支为 `validate → classify → model → total`。

能力查询只产生 validate、total。`failure` 故意发送非法 operation，收到预期业务错误时脚本返回 0。日志在 `target/local-logs/application.log`，按需执行 `logs 实际execution_id`。各阶段计时范围和云端查询见 [日志说明](logging.md)。在启动终端按 Ctrl+C 停止服务。

## 真实检索、镜像及云端验收

- 恢复真实检索：按 [Koo Search 配置](koosearch.md) 设置 `KOOSEARCH_MODE=live`，用加载了环境变量的 JAR 或容器启动；日志验证脚本的 start 固定为 stub。分别验证文本库和图片库，不能用空资料占位代替检索验收。
- 镜像与 SWR：按 [部署说明](deployment.md) 构建 ARM64 Docker V2 镜像，核对非 root 用户、健康和三条业务路径，再推送、读回 digest 并实际拉取。
- AgentArts：确认实际运行版本、入站鉴权及出站网络；对云端接口验证能力、文本/图片路由、SSE 首片、思考增量、超时和断连。用一次请求的 execution_id 在 LTS 找到相同日志。
- IAM、凭据轮转、CTS 或其他公司安全要求独立验收，不能以一次问答成功替代。

## 已验证的基线（2026-10-09）

| 项目 | 结果 |
| --- | --- |
| Java 回归 | 116 项通过 |
| ARM64 容器 | 三条 mock 业务分支、流式响应、异步耗时日志通过；Java 21、UID 10001 |
| 本地文本模型真实调用 | deepseek-v4-flash；22 个正文、32 个思考片段，业务 code="0" |
| 本地图片真实调用 | 分类服务成功后走 deepseek-v4.1-flash 视觉分支；10 个正文、137 个思考片段，业务 code="0" |
| 正文一致性 | 两次真实调用的增量拼接均与最终答案一致 |
| 文本耗时样本 | 检索 166.341ms、模型 3505.288ms、total 3693.020ms |
| 图片耗时样本 | 分类 649.743ms、模型 21943.841ms、total 22595.319ms |
| 仍待验证 | 真实 Koo Search、高置信度图片的完整真实链路、AgentArts 部署/网关及 LTS 实际采集 |

已推送并重新拉取的镜像：

```text
swr.cn-southwest-2.myhuaweicloud.com/asr-test2/travel-agent:timing-stub-20261009-1500
sha256:3aa6922914d83ea415db1986cb3b316f32a3b75b90bb0ee19d55f96afd706f71
```

该镜像包含分类器地址更新、打点和检索占位；后续提示词及源码清理需在下次发布时重新构建。耗时是单次样本，不代表均值或 P95。目录整理已清理历史临时日志和构建产物，此处保留验证摘要；以后构建和本地验证会重新生成 `target/`。
