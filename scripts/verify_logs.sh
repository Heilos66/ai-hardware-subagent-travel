#!/usr/bin/env bash
# Local workflow timing and diagnostics verification. Compatible with macOS Bash 3.2.
set -euo pipefail

TRAVEL_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$TRAVEL_ROOT"

usage() {
  cat <<'HELP'
用法：./scripts/verify_logs.sh <命令> [参数]

  start [端口]              加载 .env，以 RAGFlow 配置前台启动 JAR 并保存日志
  capabilities [端口]       查询能力；应有 validate、total 两条成功日志
  request [端口] [IO文件]   发送 IO 请求，按业务分支及检索开关调用分类器、RAGFlow 和模型
  failure [端口]            发送非法 operation；应有 validate、total 两条失败日志
  logs [execution_id]       查看全部或指定请求的耗时和检索/模型诊断日志
  test [Maven参数...]       运行耗时和诊断日志测试，无需真实业务凭据
  build [Maven参数...]      重新构建 JAR（跳过测试，可先执行 test）
  help                     显示帮助

默认端口：8081，优先使用命令参数，也可设置 TRAVEL_LOG_PORT。
默认日志：target/local-logs/application.log，追加写入。
可设置 TRAVEL_LOG_FILE 修改日志路径，TRAVEL_LOG_ENV_FILE 修改 .env 路径。
Java：优先 JAVA_HOME，否则查找 macOS JDK 21、PATH、此前临时下载的 JDK 21。
Maven：沿用 MAVEN_USER_HOME；可用 MAVEN_REPO_LOCAL 指定依赖缓存目录。

两个终端示例：
  ./scripts/verify_logs.sh start
  ./scripts/verify_logs.sh capabilities
  ./scripts/verify_logs.sh request
  ./scripts/verify_logs.sh logs

日志判定：
  文本：validate → search_text → model → total
  高置信度图片：validate → classify → search_image → model → total
  低置信度图片：validate → classify → model → total
  检索记录实际调用耗时（包含重试与提示词准备），同次请求 execution_id 相同。
  workflow_ttft 从工作流开始计时，到模型首个正文/思考片段时打印一次。
  stages_sum_ms + overhead_ms ≈ ttft_ms；model_to_first_token_ms 只包含模型首片前的耗时。
  ragflow_result 的 kept_chunks/empty_result 区分有资料与空结果，ragflow_failed 记录失败原因。
  .env 设置 RAGFLOW_LOG_CONTENT=true、MODEL_LOG_REQUEST=true 后重启，可查看脱敏正文和请求体。
  start 默认使用真实 RAGFlow；.env 设置 RAGFLOW_ENABLED=false 可跳过检索继续模型调用。
  跳过时记录 ragflow_skipped，耗时/TTFT 日志显示 search_mode=disabled，无需知识库网络或凭据。
  请求尚未结束时在客户端按 Ctrl+C，可检查当前阶段及 total 的 cancelled 日志。
  start 终端按 Ctrl+C 停止服务。代码变更后先 build，再重启 start。
HELP
}

die() { printf '%s\n' "$*" >&2; exit 1; }

require_python() {
  command -v python3 >/dev/null 2>&1 || die '需要 Python 3，请先安装。'
}

select_java() {
  local candidate version detected_home
  local candidates=()
  if [[ -n "${JAVA_HOME:-}" ]]; then
    candidates+=("$JAVA_HOME/bin/java")
  else
    if [[ -x /usr/libexec/java_home ]]; then
      detected_home="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
      [[ -z "$detected_home" ]] || candidates+=("$detected_home/bin/java")
    fi
    candidate="$(command -v java || true)"
    [[ -z "$candidate" ]] || candidates+=("$candidate")
    for candidate in /private/tmp/travel-java-tools/jdk-21*/Contents/Home/bin/java; do
      [[ ! -x "$candidate" ]] || candidates+=("$candidate")
    done
  fi
  for candidate in "${candidates[@]}"; do
    [[ -x "$candidate" ]] || continue
    version="$("$candidate" -version 2>&1)" || continue
    if [[ "$version" =~ version\ \"21[.\"+-] ]]; then
      detected_home="$("$candidate" -XshowSettings:properties -version 2>&1 |
        sed -n 's/^[[:space:]]*java.home = //p')"
      [[ -x "$detected_home/bin/java" ]] || continue
      export JAVA_HOME="$detected_home"
      return
    fi
  done
  die '未找到可用的 JDK 21。请设置 JAVA_HOME 为 JDK 21 的目录后重试。'
}

port_for() {
  local requested="${1:-${TRAVEL_LOG_PORT:-8081}}"
  [[ "$requested" =~ ^[0-9]{1,5}$ ]] || die '端口必须是 1～65535 的整数。'
  requested=$((10#$requested))
  (( requested >= 1 && requested <= 65535 )) || die '端口必须在 1～65535 之间。'
  printf '%s\n' "$requested"
}

command_name="${1:-help}"
if (( $# > 0 )); then shift; fi
log_file="${TRAVEL_LOG_FILE:-$TRAVEL_ROOT/target/local-logs/application.log}"

case "$command_name" in
  help|-h|--help)
    usage
    ;;
  start)
    (( $# <= 1 )) || die '用法：verify_logs.sh start [端口]'
    run_port="$(port_for "${1:-}")"
    [[ -f target/travel-agent.jar ]] || die '缺少 JAR，请先执行 ./scripts/verify_logs.sh build。'
    env_file="${TRAVEL_LOG_ENV_FILE:-$TRAVEL_ROOT/.env}"
    if [[ -f "$env_file" ]]; then
      set -a
      source "$env_file"
      set +a
    elif [[ -n "${TRAVEL_LOG_ENV_FILE:-}" ]]; then
      die '指定的环境配置文件不存在。'
    fi
    select_java
    umask 077
    mkdir -p "$(dirname "$log_file")"
    printf '启动已有 JAR：http://127.0.0.1:%s，知识库 RAGFlow\n日志：%s\n' "$run_port" "$log_file"
    printf '源码改动后请先执行 build；等到 Started 日志出现，再在另一终端发送请求。\n'
    PORT="$run_port" \
      "$JAVA_HOME/bin/java" -jar target/travel-agent.jar 2>&1 | tee -a "$log_file"
    ;;
  capabilities|request)
    require_python
    if [[ "$command_name" == capabilities ]]; then
      (( $# <= 1 )) || die '用法：verify_logs.sh capabilities [端口]'
      python3 scripts/test_io_spec.py --port "$(port_for "${1:-}")" --capabilities
    else
      (( $# <= 2 )) || die '用法：verify_logs.sh request [端口] [IO文件]'
      python3 scripts/test_io_spec.py --port "$(port_for "${1:-}")" \
        --spec "${2:-$TRAVEL_ROOT/references/io_spec.md}"
    fi
    ;;
  failure)
    (( $# <= 1 )) || die '用法：verify_logs.sh failure [端口]'
    require_python
    python3 - "$(port_for "${1:-}")" <<'PY'
import json
import sys
import urllib.request

request = urllib.request.Request(
    f"http://127.0.0.1:{sys.argv[1]}/invocations",
    data=b'{"inputs":{"operation":"invalid"}}',
    headers={"Content-Type": "application/json", "Accept": "text/event-stream"},
)
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
finished = ended = False
try:
    with opener.open(request, timeout=15) as response:
        for raw in response:
            line = raw.decode("utf-8")
            print(line, end="", flush=True)
            if not line.startswith("data:"):
                continue
            frame = json.loads(line[5:].strip())
            if frame.get("event") == "workflow_finished":
                data = frame["data"]
                result = json.loads(data["outputs"]["responseContent"])
                finished = data["status"]["code"] == -1 and str(result.get("code")) == "400"
            elif frame.get("event") == "end":
                ended = True
except (OSError, ValueError, KeyError, TypeError):
    sys.exit("调用失败，请确认服务端口、健康状态和 SSE 格式。")
if not finished or not ended:
    sys.exit("未收到完整的预期失败结果，验证未通过。")
print("预期业务错误已验证；请执行 logs，检查 validate 和 total 的 status=failed。")
PY
    ;;
  logs)
    (( $# <= 1 )) || die '用法：verify_logs.sh logs [execution_id]'
    [[ -f "$log_file" ]] || die "日志不存在，请先执行 start：$log_file"
    if command -v rg >/dev/null 2>&1; then
      matches="$(rg --no-heading --color never 'workflow_(timing|ttft|diagnostic) ' "$log_file" || true)"
    else
      matches="$(grep -E 'workflow_(timing|ttft|diagnostic) ' "$log_file" || true)"
    fi
    if [[ -n "${1:-}" ]]; then
      matches="$(printf '%s\n' "$matches" | grep -F -- " execution_id=$1 " || true)"
    fi
    if [[ -n "$matches" ]]; then
      printf '%s\n' "$matches"
    else
      printf '尚无匹配的耗时或诊断日志；请先发送请求，异步日志可能稍晚到达。\n'
    fi
    ;;
  test|build)
    select_java
    maven_args=(-B)
    if [[ -n "${MAVEN_REPO_LOCAL:-}" ]]; then
      maven_args+=("-Dmaven.repo.local=$MAVEN_REPO_LOCAL")
    fi
    if [[ "$command_name" == test ]]; then
      ./mvnw "${maven_args[@]}" -Dtest=WorkflowTimingTest,WorkflowDiagnosticsTest "$@" test
    else
      ./mvnw "${maven_args[@]}" -DskipTests "$@" package
    fi
    ;;
  *)
    usage >&2
    die "未知命令：$command_name"
    ;;
esac
