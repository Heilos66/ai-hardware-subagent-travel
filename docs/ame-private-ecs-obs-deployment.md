# 无公网 IP 的 ECS：通过 OBS 部署独立 AME

> 2026-11-05 部署方式已调整：当前 ECS 为 Huawei Cloud EulerOS 3.0 / x86_64，能够联网，请使用 [Git 部署指南](ame-ecs-git-deployment.md)。本文保留为旧离线方案及已交付离线包的说明，其中 Ubuntu 的 apt / .deb 步骤不适用于当前 HCE ECS。

适用版本：`codex/ame-ecs-integration` 分支；编写日期：2026-11-04。

本文使用源码部署：在可联网电脑准备源码包，通过同区域 OBS 送入 ECS，在 ECS 本机编译并安装 `travel-ame.service`。ECS 不需要公网 IP，AME 不需要联网调用 embedding 或 rerank。AgentArts 上的文旅 Agent 使用独立的 Java 镜像，通过私网 HTTP 请求 AME。

本指南以 **Ubuntu 22.04、x86_64、systemd** 为已验证基线。建议先准备 4 vCPU、8 GiB 内存、至少 5 GiB 可用磁盘作为小规模部署起点；这不是容量承诺。当前语料为 4 篇文档、52 个切片。AME 进程首次启动实际约 0.9 GiB，systemd 内存上限为 2 GiB。目标 ECS 的系统、架构和网络尚未提供，因此本文不代表已在新 ECS 完成部署。

## 1. 两条链路分别准备

```text
有网络的电脑 ──上传──> 同区域私有 OBS 桶
                            │ HTTPS + 内网 DNS
                            ▼
                   无公网 IP 的 ECS
                   AME :18087 / Bearer
                            ▲
                            │ VPC 私网连通 + 安全组
                      AgentArts 文旅 Agent
```

OBS 负责传递部署包，AgentArts 不通过 OBS 调用 AME。OBS 下载成功也不能证明 AgentArts 已能访问 ECS。

华为云官方要求 ECS 与 OBS 资源位于同一区域，并通过该区域内网 DNS 解析 OBS 域名，才能走内网访问；无公网 IP 本身不会自动建立这条链路。[官方：ECS 内网访问 OBS](https://support.huaweicloud.com/bestpractice-ecs/ecs_05_3301.html)

### 部署前填写

| 项目 | 填写内容或示例 |
| --- | --- |
| ECS 区域 | 例如 `cn-southwest-2`，以新 ECS 为准 |
| ECS 系统、架构 | 推荐 Ubuntu 22.04 / x86_64；执行下面的检查命令确认 |
| ECS 私网 IP | 新 ECS 的实际私网地址，例如 `192.168.0.25`；不要照抄旧 ECS 地址 |
| VPC、子网 | 新 ECS 所属 VPC / 子网，以及 AgentArts 运行时连接的 VPC / 子网 |
| 登录方式 | 控制台远程登录、云堡垒机，或已有可达该 VPC 的管理机 |
| OBS 桶 | 与 ECS 同区域的私有桶 |
| 对象路径 | 例如 `ame/releases/20261104/ame-offline-20261104.tar.gz` |
| AME 监听端口 / ID | 默认 `18087` / `ame-travel` |
| AgentArts 镜像 | 本次发布的支持 `KNOWLEDGE_BACKEND=ame` 的文旅 Agent 镜像 |

本机没有到 VPC 的路由时，不能直接 SSH 到 ECS 私网地址。先使用已具备的控制台远程登录或堡垒机进入系统。

## 2. 核对系统与依赖

在 **目标 ECS** 执行：

```bash
cat /etc/os-release
uname -m
getconf GNU_LIBC_VERSION
free -h
df -h /opt
systemd --version
python3 --version
cmake --version
g++ --version
make --version
git --version
curl --version
```

| 组件 | 要求 | 用途 |
| --- | --- | --- |
| Python | 3.10 或更高 | HTTP adapter，仅使用标准库，不需要 pip 安装依赖 |
| CMake | 3.16 或更高 | 构建 AME worker |
| g++ / clang++ | 支持 C++17 | 编译本机架构的二进制 |
| make、git | 可用 | 构建；从离线 Git bundle 恢复固定源码 |
| systemd | 247 或更高 | 本项目使用 `LoadCredential`；Ubuntu 22.04 自带版本符合要求 |
| curl、CA 证书 | 可用 | HTTPS 下载 OBS 文件及健康检查 |
| tar、gzip、sha256sum | 可用 | 解包和完整性校验 |

`x86_64` 对应 amd64；`aarch64` 对应 arm64。源码包通用，编译出的 worker 与编译机架构一致。本文没有验证 ARM64 ECS 的 systemd 部署；不要把 Mac 二进制或 ARM64 Agent 镜像当成 x86_64 AME 服务安装包。HCE、EulerOS、CentOS 的依赖安装和 systemd 版本需单独核对，不能照搬下方 Ubuntu 的 apt 命令。

### 情况 A：有可用的软件源

“没有公网 IP”不等于“完全无法出网”：可能配置了 NAT 或内网软件镜像。只有确认软件源可达时，才执行：

```bash
sudo apt-get update
sudo apt-get install --no-install-recommends \
  build-essential cmake python3 git curl ca-certificates file
```

不要假设华为云内网 DNS 能让任意 GitHub、Docker Hub 或 Ubuntu 公网源变成内网可达。

### 情况 B：目标 ECS 无可用软件源

优先用已预装上述工具的企业镜像创建 ECS。如果现有 ECS 缺少工具，在**同 Ubuntu 发行版、同架构的联网准备机**上收集离线包，再一起上传 OBS。以下示例为 Ubuntu 22.04 / x86_64；不能用于其他发行版或 ARM64：

```bash
# 在可联网、安装了 Docker 的准备机执行。
mkdir -p target/ame-os-deps/ubuntu22-amd64

docker run --rm --platform linux/amd64 \
  -v "$PWD/target/ame-os-deps/ubuntu22-amd64:/packages" \
  ubuntu:22.04 bash -ec '
    apt-get update
    mkdir -p /packages/partial
    apt-get -y --download-only --no-install-recommends \
      -o Dir::State::status=/dev/null \
      -o Dir::Cache::archives=/packages \
      install build-essential cmake python3 git curl ca-certificates file
  '

tar -czf target/ame-os-deps-ubuntu22-amd64.tar.gz \
  -C target/ame-os-deps ubuntu22-amd64

# 同时生成校验文件；此写法兼容准备机上的 Python 3。
python3 - <<'PY'
import hashlib, pathlib
path = pathlib.Path('target/ame-os-deps-ubuntu22-amd64.tar.gz')
path.with_name(path.name + '.sha256').write_text(
    hashlib.sha256(path.read_bytes()).hexdigest() + '  ' + path.name + '\n')
PY
```

`Dir::State::status=/dev/null` 让下载步骤把依赖也纳入收集，而不依赖准备机已安装的包。该包不包含内核、完整操作系统或 systemd 部署环境；目标 ECS 仍需符合上表基线。此命令是离线依赖准备方法，本次没有针对你的新 ECS 运行，版本差异导致缺包时需回准备机补齐，不能用 `--force-depends` 强行安装。

在 ECS 下载、校验该依赖包后执行：

```bash
mkdir -p /tmp/ame-os-deps
tar -xzf ame-os-deps-ubuntu22-amd64.tar.gz -C /tmp/ame-os-deps
# --no-download 禁止安装阶段临时去外网补包；先查看 apt 的变更清单。
sudo apt-get --no-download install /tmp/ame-os-deps/ubuntu22-amd64/*.deb
```

再次运行依赖检查。若目标连 curl 都没有，可用已有 Python 3 的标准库下载 OBS 临时 URL，见第 5 节；若这些工具也均不存在，应先通过镜像或管理通道补齐。

## 3. 准备独立 AME 源码包

本次已生成的文件位于工程 `target/ame-offline-20261104/`：

- `ame-offline-20261104.tar.gz`：服务源码、固定 AME 上游 Git bundle、当前 52 个切片、部署文档。
- `ame-offline-20261104.tar.gz.sha256`：整个压缩包的 SHA-256。

该包**不包含**操作系统依赖、编译后的 worker、Docker 镜像、RAGFlow/AME Token、SSH 密码或云 AK/SK。新 ECS 会生成自己的 AME Token。不要上传整个工程目录，也不要上传 `target/ame-ecs-deploy-20261104/agent.env`。

如果知识数据或源码发生变化，在联网电脑的工程根目录重新打包：

```bash
python3 scripts/package_ame_offline.py \
  --ame-source target/ame-v3/source \
  --corpus artifacts/ame-quality-20261030/corpus.json \
  --output target/ame-offline-new/ame-offline-new.tar.gz
```

`--ame-source` 必须是固定到 `df68cbf1f2742095901ec97434507d76cba2f61d` 且无已跟踪文件修改的 AME Git 仓库。当前目录已有该源码；换一台准备机时可先联网获取：

```bash
git clone https://github.com/arrayforward/ame.git ame-source
git -C ame-source checkout --detach df68cbf1f2742095901ec97434507d76cba2f61d
```

语料格式：

```json
[
  {"id":"unique-chunk-id","document":"展品资料.md","content":"经审核的知识正文"}
]
```

ID 必须唯一，三个字段均为非空字符串。当前资料是长信宫灯、秦始皇兵马俑、商青铜人面具、唐三彩载乐骆驼俑共 52 个切片；不会自动从原 RAGFlow 同步后续更新。打包工具会检查重复 ID，并生成 `manifest.json` 和内部 `SHA256SUMS`。

## 4. 上传到 OBS

在本机华为云控制台操作：

1. 进入 OBS，选择与新 ECS 相同的区域。
2. 创建或选择一个**私有桶**，无需改成公共读。
3. 建立版本目录，例如 `ame/releases/20261104/`。
4. 上传源码包 `.tar.gz` 和 `.sha256`；需要离线依赖时，也上传对应依赖包及校验文件。
5. 在对象列表确认文件名、大小一致，记录对象路径。

一次部署推荐使用**单个对象的 HTTPS 临时下载 URL**：选择文件 → 分享 → 设置足够本次下载的有效期 → 复制链接。源码包和校验文件分别生成链接。选“文件分享”，不要把“文件夹分享页面 + 提取码”当成文件直链。[官方：分享对象](https://support.huaweicloud.com/usermanual-obs/obs_03_0046.html)

链接包含临时访问凭据，过期后需重新生成。保持桶默认域名、HTTPS、路径及查询串完整，不将域名替换为 IP，也不要把 URL 粘贴到日志或提交仓库。使用临时 URL 下载不需要在 ECS 配置长期 AK/SK。[官方：临时 URL 访问](https://support.huaweicloud.com/perms-cfg-obs/obs_40_0009.html)

## 5. ECS 通过内网下载 OBS 文件

### 5.1 DNS 和网络检查

把以下变量改成实际区域和桶名；本节命令在 ECS 执行：

```bash
OBS_REGION=cn-southwest-2
OBS_BUCKET=你的私有桶名称
OBS_HOST="${OBS_BUCKET}.obs.${OBS_REGION}.myhuaweicloud.com"

cat /etc/resolv.conf
resolvectl status
getent ahostsv4 "$OBS_HOST"
curl --connect-timeout 5 --max-time 10 -I "https://${OBS_HOST}/"
```

Ubuntu 的 `/etc/resolv.conf` 显示 `127.0.0.53` 是本地 DNS stub，应结合 `resolvectl status` 检查上游 DNS。按[区域 DNS 官方表](https://support.huaweicloud.com/dns_faq/dns_faq_002.html)配置 VPC 子网/网卡的内网 DNS，不要把其他区域的 DNS 硬编码过来。临时修改 `/etc/resolv.conf` 可能被 DHCP 或 systemd-resolved 覆盖。[官方：Linux ECS 内网访问 OBS](https://support.huaweicloud.com/bestpractice-obs/obs_05_0430.html)

未签名的桶请求出现 403，只能说明 DNS/TCP/TLS/HTTP 通了，不能说明对象权限已正确。无公网出口时若解析到公网地址并连接超时，应先修复 DNS/区域/路由；不要把桶改成公共读来解决网络问题。

安全组、网络 ACL 和主机策略需允许访问区域内网 DNS（UDP/TCP 53）及 OBS HTTPS（TCP 443）。也要检查桶策略是否对来源网络做了限制。确认走内网可参考 [官方判断方法](https://support.huaweicloud.com/obs_faq/obs_faq_0148.html)；若该文档入口调整，从上面的最佳实践进入“如何判断是否内网访问 OBS”。

### 5.2 使用临时 URL 下载，避免先安装 obsutil

在 ECS 的 Bash 中执行下面函数。使用 `read -s` 接收 URL，通过 curl 标准输入传配置，URL 不进入命令历史或 curl 的进程参数；不使用 `-k` 关闭证书校验：

```bash
mkdir -p "$HOME/ame-download"
cd "$HOME/ame-download"
umask 077

obs_download() {
  local output="$1" obs_url
  read -r -s -p "粘贴该文件的 OBS HTTPS 临时 URL: " obs_url
  printf '\n'
  if [[ "$obs_url" != https://* || "$obs_url" == *'"'* || "$obs_url" == *'\'* ]]; then
    printf '%s\n' 'URL 格式错误，请复制原始 HTTPS 对象链接。' >&2
    return 1
  fi
  printf 'url = "%s"\n' "$obs_url" |
    curl --config - --fail --show-error --silent \
      --connect-timeout 10 --max-time 900 \
      --output "${output}.part" && mv "${output}.part" "$output"
  local result=$?
  unset obs_url
  return "$result"
}

obs_download ame-offline-20261104.tar.gz
obs_download ame-offline-20261104.tar.gz.sha256
sha256sum -c ame-offline-20261104.tar.gz.sha256
```

应显示 `ame-offline-20261104.tar.gz: OK`。403 时检查链接有效期、是否遗漏 `&` 后的参数及桶策略；不要使用 `curl -v` 将临时 URL 写入共享日志。校验失败则停止解包，重新下载并与准备机校验值核对。

**只有 Python 3、没有 curl 时**，先用下面方法下载离线依赖包：

```bash
python3 - <<'PY'
import getpass, pathlib, shutil, urllib.request
url = getpass.getpass('OBS HTTPS 临时 URL: ')
if not url.startswith('https://'):
    raise SystemExit('必须是 HTTPS URL')
# 按实际下载对象修改；下载校验文件时改为相应的 .sha256 文件名。
name = 'ame-os-deps-ubuntu22-amd64.tar.gz'
if pathlib.Path(name).name != name:
    raise SystemExit('只填写文件名')
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None
try:
    opener = urllib.request.build_opener(NoRedirect)
    with opener.open(url, timeout=60) as response, open(name + '.part', 'wb') as output:
        shutil.copyfileobj(response, output)
    pathlib.Path(name + '.part').replace(name)
except Exception:
    raise SystemExit('下载失败；检查 URL 有效期、DNS、网络、证书和权限。') from None
PY
```

安装企业 CA 时遵循公司的证书配置方式，不关闭 TLS 校验。若使用 obsutil，需先在联网电脑获取对应 Linux amd64/arm64 工具，再经同样的 OBS 文件直链引入；官方说明工具本身需从公网获取。[官方：obsutil 下载](https://support.huaweicloud.com/utiltg-obs/obs_11_0003.html)

## 6. 解包、校验并离线编译

以下以本次包名为例。使用一个全新的发布目录：

```bash
AME_RELEASE=20261104-ame-private
AME_RELEASE_DIR="/opt/travel-ame/releases/${AME_RELEASE}"

sudo mkdir -p /opt/travel-ame/releases
sudo mkdir "$AME_RELEASE_DIR"
sudo tar -xzf ame-offline-20261104.tar.gz \
  -C "$AME_RELEASE_DIR" --strip-components=1
sudo chmod -R a+rX "$AME_RELEASE_DIR"
cd "$AME_RELEASE_DIR"
sha256sum -c SHA256SUMS

# 仅从本地 bundle 恢复源码，不访问 GitHub。
sudo git init upstream
sudo git -C upstream fetch ../ame-upstream.bundle HEAD
sudo git -C upstream checkout --detach FETCH_HEAD
sudo git -C upstream rev-parse HEAD
# 必须输出 df68cbf1f2742095901ec97434507d76cba2f61d

sudo bash build.sh ./upstream
file build/ame_worker
ldd build/ame_worker
python3 -B -m unittest discover -s tests -v
```

`file` 应显示与 ECS 一致的 Linux 架构；`ldd` 不应出现 `not found`；测试预期 9 项通过。CMake 会给固定上游版本补充 `-include cstdint`，无需自行改上游头文件。`build.sh` 默认并行 2 个编译任务，可通过 `sudo env BUILD_JOBS=1 bash build.sh ./upstream` 降低构建时资源压力。

bundle、编译器和依赖准备完成后，这一步无需外网，也不需要 Docker。C++ worker 不依赖 Java 或 Spring；Java Agent 的镜像架构不限制这个远端 AME ECS 的架构。

## 7. 安装 systemd 服务并生成新 Token

先检查端口，避免与已有服务冲突：

```bash
sudo ss -ltnp 'sport = :18087'
sudo systemctl status travel-ame --no-pager
```

首次部署时 service 不存在是正常的；若已有同名服务，先核对它是否就是要升级的 AME，不能直接覆盖别的应用。

```bash
sudo systemd-analyze verify "$AME_RELEASE_DIR/travel-ame.service"
sudo bash install.sh "$AME_RELEASE_DIR"
sudo systemctl status travel-ame --no-pager
```

安装脚本完成以下动作：

- 仅管理 `travel-ame.service`，建立 `/opt/travel-ame/current` 指向新发布目录。
- 首次生成 `/etc/travel-ame/token`；目录权限 700、Token 文件 600；已有 Token 保留。
- 通过 `LoadCredential` 将凭据交给服务专用动态用户，令牌不会出现在 systemd 启动参数中。
- 设置开机启动、失败后重启；工作目录只读，词典缓存存入 `/var/lib/travel-ame/`。

默认监听 `0.0.0.0:18087`，表示接收 ECS 各网卡的流量，**不会自动给无公网 IP 的 ECS 分配公网入口**。如果需要固定只监听私网网卡，在首次启动前或启动后设置 drop-in；示例 IP 必须改成新 ECS 的地址：

```bash
sudo mkdir -p /etc/systemd/system/travel-ame.service.d
printf '[Service]\nEnvironment=AME_BIND=192.168.0.25\n' |
  sudo tee /etc/systemd/system/travel-ame.service.d/network.conf >/dev/null
sudo systemctl daemon-reload
sudo systemctl restart travel-ame
```

绑定特定私网 IP 后，下面所有 `127.0.0.1` 健康检查需相应改成这个私网 IP。更改 `AME_PORT` 或 `AME_DATASET_ID` 也可通过 drop-in 完成，并同步调整安全组和 Agent 连接配置。

## 8. 先在 ECS 本机验证

首次导入需要数十秒。systemd 显示 active 不代表 HTTP 已就绪；下面最多尝试 60 次，每次请求最长 2 秒、失败后间隔 2 秒：

```bash
AME_BASE=http://127.0.0.1:18087
ready=false
for attempt in $(seq 1 60); do
  if curl --fail --silent --max-time 2 "$AME_BASE/health"; then
    ready=true
    break
  fi
  sleep 2
done
[[ "$ready" == true ]] || { sudo journalctl -u travel-ame -n 50 --no-pager; exit 1; }
```

预期 `{"ready": true}`。如果进程启动失败，按第 11 节排查，不要反复重新生成 Token。

以下真实检索测试从受限文件读取 Token，不输出 Token，也不把它放入命令参数：

```bash
sudo python3 - <<'PY'
import json, pathlib, urllib.request, urllib.error
base = 'http://127.0.0.1:18087'  # 若改了 AME_BIND，此处改为 ECS 私网 IP。
token = pathlib.Path('/etc/travel-ame/token').read_text().strip()
body = json.dumps({'dataset_ids': ['ame-travel'], 'question': '长信宫灯有什么特点',
                   'page': 1, 'page_size': 3, 'similarity_threshold': 0}).encode()
req = urllib.request.Request(base + '/api/v1/retrieval', body,
    {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token})
try:
    with urllib.request.urlopen(req, timeout=12) as response:
        data = json.load(response)
    assert data['code'] == 0
    chunks = data['data']['chunks']
    assert chunks, '预期当前示例语料有检索结果'
    print('success, chunks =', len(chunks))
    print('timings =', data.get('meta', {}))
    print('first_document =', chunks[0]['document_keyword'])
except urllib.error.HTTPError as error:
    raise SystemExit('检索 HTTP ' + str(error.code)) from None
PY

# 无 Token 应返回 HTTP 401。
curl --silent --output /dev/null --write-out '%{http_code}\n' \
  -H 'Content-Type: application/json' \
  --data '{"dataset_ids":["ame-travel"],"question":"长信宫灯"}' \
  "$AME_BASE/api/v1/retrieval"
```

服务端日志与资源检查：

```bash
sudo journalctl -u travel-ame -f
sudo systemctl show travel-ame \
  -p ActiveState -p SubState -p NRestarts -p MemoryCurrent -p MemoryMax
```

## 9. 配置 AgentArts 私网访问

高代码运行时的官方部署配置提供“私网访问”，可选择 VPC、子网和安全组。应以实际区域/租户控制台可用项为准；新 ECS 无公网 IP 时，不能仅把 `RAGFLOW_URL` 改成私网地址而省略网络配置。[官方：运行时部署](https://support.huaweicloud.com/highcode-agentarts/agentarts_10_031.html)

按顺序核对：

1. 运行时的网络连接选择可达 AME 的 VPC、子网。优先同区域、同 VPC；跨 VPC 需对等连接等已有路由，跨区域另行设计。
2. AME ECS 安全组入方向允许 **TCP 18087，来源为 AgentArts 运行时实际私网来源/子网或适用的安全组**。不要填写主 Agent 调用网关的公网域名，也不需要放行 `0.0.0.0/0`。
3. 运行时安全组允许出方向访问 ECS 私网 IP 的 18087；同时检查网络 ACL、主机防火墙和双向路由。
4. 从同网络的管理机访问 `http://<ECS私网IP>:18087/health`，再测试带 Token 的检索。这只能验证该管理机链路，最终仍须从真实 AgentArts 调用验证。
5. 若模型/分类器使用公网地址，单独保留其可用出口。配置私网知识库不能以牺牲模型或分类器连通性为代价。

截至 2026-11-03 的官方部署说明，镜像来源支持 ARM64；本次 Agent 镜像也是 `linux/arm64`。独立 AME 可以在 x86_64 ECS 上运行，两者通过 HTTP 互通。

本次已推送并完成远端拉取校验的镜像：

```text
swr.cn-southwest-2.myhuaweicloud.com/asr-test2/travel-agent:release-20261104-173718-arm64-ame-ecs-d7d93fb-wip-84f088
```

远端 digest：`sha256:ccda8ab808dc5ec979bb43dae096053f24271852d6ea2473fc0d2d49dd3e168d`。该镜像来自当前分支的工作区代码，标签中的 `wip` 表示尚未提交的改动；默认知识库仍为 RAGFlow，需明确设置下面的 `KNOWLEDGE_BACKEND=ame`。

在 **AgentArts 运行时环境变量** 中配置：

```dotenv
KNOWLEDGE_BACKEND=ame
RAGFLOW_ENABLED=true
RAGFLOW_URL=http://192.168.0.25:18087/api/v1/retrieval
RAGFLOW_DATASET_ID=ame-travel
RAGFLOW_API_TOKEN=填写新ECS生成的AME令牌
RAGFLOW_RETRIES=0
RAGFLOW_TOP_K=3
RAGFLOW_TIMEOUT=10
```

IP 和 Token 必须替换。通过受控管理会话获取 `/etc/travel-ame/token` 并保存到运行时的机密配置；不要复制旧 RAGFlow Token，也不要把 Token 写进本部署文档。若使用 `RAGFLOW_API_TOKEN_FILE`，该路径必须是 **AgentArts 容器内实际挂载的文件**，不能直接填写 ECS 的 `/etc/travel-ame/token`。

旧配置中的 `RAGFLOW_API_TOKEN_FILE` 优先级高于 `RAGFLOW_API_TOKEN`；若改用环境 Token，须清空旧文件路径，避免继续读旧凭据。`RAGFLOW_*` 中的 KNN、rerank、相似度阈值在 AME 模式会被忽略，不必为 AME 指定 rerank ID。其余分类器和模型配置沿用现有工程。

在 AgentArts 选择本次发布的 Java Agent 镜像，保存并发布/切换运行时版本，使用新的 Session ID 调用。验证这三种路径：

| 请求 | 预期 |
| --- | --- |
| 纯文本“长信宫灯有什么特点” | `ame_result` 后进入文本模型，最终成功 |
| 分类名称非空、置信度 ≥ 0.7 的图片 | 分类 → AME → 文本模型 |
| 置信度 < 0.7 的图片 | 分类 → 视觉模型，不请求 AME |

日志应出现 `search_mode=ame`、`ame_result` 的 `returned_chunks/kept_chunks`，以及 `workflow_ttft`。查看实际知识正文可短时设置 `RAGFLOW_LOG_CONTENT=true`；需要查看模型请求正文时设置 `MODEL_LOG_REQUEST=true`，验证后关闭。HTTP 200/SSE 建立不等于工作流成功，还需确认最后的 `workflow_finished.data.status.code=1` 和业务结果 code=`0`。

## 10. 更新、回滚、Token 轮换

**更新知识或代码：** 重新生成带新版本名的源码包 → OBS 上传 → ECS 下载校验 → 新发布目录编译测试 → 运行该目录的 `install.sh`。不要覆盖正在运行目录中的 `corpus.json`；服务启动时才导入语料，改文件不会自动更新内存图。

**回滚：** 在升级前记录 `readlink -f /opt/travel-ame/current`。需要回滚时，对完整的旧发布目录重新执行其 `install.sh`，然后等待健康检查；Token 位于独立目录，不会随版本链接回滚。对当前单实例切换会有冷启动间隔，需要无中断时另行部署双实例。

```bash
# 示例：替换为升级前记录的旧版本路径。
OLD_RELEASE=/opt/travel-ame/releases/旧版本目录
sudo bash "$OLD_RELEASE/install.sh" "$OLD_RELEASE"
```

**轮换 AME Token：** 先在 ECS 安全生成新值并替换 `/etc/travel-ame/token`（保持 600），同步更新 AgentArts 的令牌，然后重启 `travel-ame`，再发布/切换 AgentArts 配置。AME 的 Token 在进程启动时读取，不支持双 Token 过渡；操作顺序需安排短暂维护窗口。Java 客户端的 Token 文件虽然每次检索重读，也不能让服务端自动热加载。

**关闭：** `sudo systemctl disable --now travel-ame`；按需移除专用安全组规则。不要删除其他应用的服务、Docker 容器或共享安全组。

## 11. 常见问题

| 现象 | 检查方向 |
| --- | --- |
| OBS 连接超时 | 桶与 ECS 区域、内网 DNS、路由、出站 443、代理设置 |
| OBS 403 | 临时 URL 是否过期/完整、桶策略、对象是否正确；不是编译问题 |
| 文件 SHA-256 不一致 | 重新下载；核对本机原始校验值，不继续安装 |
| apt 无法联网 | 使用同发行版/架构的离线依赖包或预装镜像，不能靠重复 apt update 解决 |
| `cmake/g++/git: command not found` | 源码包不含 OS 工具，完成第 2 节 |
| `LoadCredential` 不识别 | systemd 太旧；使用符合基线的系统，不删除鉴权配置绕过 |
| `Exec format error` | 用错 CPU 架构的二进制；在目标 ECS 从源码构建 |
| `GLIBCXX...not found` | 拷贝了不兼容二进制；按目标系统重新构建并核对 libstdc++ |
| active 但端口未监听 | 图仍在导入，或查看 journal 的初始化失败信息 |
| AME 401 | Token 不匹配，或服务轮换后未重启、Agent 仍读旧 Token 文件 |
| AME 400 | dataset ID、page_size、10000 字上限；检查是否忘了 `KNOWLEDGE_BACKEND=ame` |
| Agent E5004 | AME URL/ID/Token 未配置；或仍为不可用平台加密值/旧文件路径 |
| Agent E5002 / 超时 | 私网不通、端口/Token 错误或 AME 失效；先从调用方网络直测 |
| AME 503 | 有界队列满或服务未就绪；默认单 worker 串行，不要用多层重试放大压力 |
| AME 502 后服务重启 | worker 异常或超时，systemd 正在重新导入；检查 `NRestarts` 和资源 |
| 本机检索成功，AgentArts 失败 | AgentArts 网络路径与 ECS 本机不同；检查 VPC、来源规则及实际运行版本 |

## 12. 验证范围

上一轮已在 Ubuntu 22.04 / x86_64 ECS 验证：固定 AME 源码编译、52 个切片导入、Bearer 鉴权、10000 字输入、异常输入、systemd 重启恢复，以及 Java 经 SSH 隧道连接真实 AME。分类器与模型在该联调中使用 mock。

本次验证结果：

- Java Agent：309 项测试通过，镜像内 11 项冒烟用例通过；分类器、模型及检索在镜像冒烟测试中使用 mock。
- SWR：已确认最终 manifest digest，按 digest 重新拉取；确认 `linux/arm64`、非 root UID 10001、镜像内 JAR 与测试产物一致。
- 部署包：固定文件清单、内部 SHA-256、离线 Git bundle 恢复检查通过；包中没有环境文件或云凭据。
- 在预装编译工具的 Ubuntu 22.04 / ARM64 容器中设置 `--network none`，只使用交付源码包完成源码恢复、C++ 编译和 52 个切片导入。
- 离线环境中 9 项 Python 接口测试通过；真实 AME 的健康检查、正常检索、错误 Token、错误知识库 ID、10000 字和 10001 字共 6 项检查通过。
- 文档中 17 段 Bash 命令通过语法检查。容器中没有验证 systemd；systemd 结果来自上一轮 x86_64 ECS 实测。

验证日志在工程 `target/ame-offline-20261104/offline-check.log`，结构化结果在同目录 `offline-result.json`；镜像发布记录在 `target/current-ame-release-path.txt` 指向目录的 `release.json`。

新 ECS 的系统包安装、OBS 内网链路和 AgentArts 私网链路尚未实测；部署时按第 2、5、8、9 节分别验收。本次没有替你向 OBS 上传文件或修改新 ECS。
