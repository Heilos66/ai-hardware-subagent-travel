# Huawei Cloud EulerOS 3.0：通过 Git 部署独立 AME

适用系统：**Huawei Cloud EulerOS 3.0 / x86_64**，ECS 已能访问网络。更新日期：2026-11-05。

按顺序执行以下步骤，命令均使用完整路径，不需要设置或复用 Bash 变量。本文使用 `20261105` 作为部署目录名称；后续部署请统一换成新的日期或版本号，不覆盖已有发布目录。任何安装、下载或编译命令失败时，先解决报错，再执行下一步。

**所有代码块均使用纯 ASCII 字符，可以在无法输入中文的终端直接粘贴。** 中文问题使用 Unicode 转义，Python / JSON 解析后仍是原始中文；知识文件通过 SFTP 上传，保持 UTF-8 编码，不在终端粘贴文件内容。

本文只部署 ECS 上的独立 AME：获取源码、编译检索 worker、导入知识数据、启动 HTTP 服务并验证接口。文旅 Agent 镜像已推送到 SWR，本次部署不涉及其编译、镜像或运行时发布。ECS 不需要安装 Java、Maven 或 Docker。

部署文件的获取方式：AME 核心从公开仓库通过 HTTPS 克隆；HTTP 服务适配代码可从本仓库的 `codex/ame-ecs-integration` 分支获取，也可使用独立包上传。知识数据单独提供，不随 Git 提交。

## 1. 确认代码和数据已准备好

| 内容 | 来源 |
| --- | --- |
| AME 核心和词典 | [Heilos66/ame](https://github.com/Heilos66/ame)，`dev-3.0` 分支 |
| AME 的 HTTP 接口、鉴权、构建和服务脚本 | 本分支的 `deploy/ame/`，或独立的 `ame-service-20261105.tar.gz` |
| 知识数据 | 单独提供的 `corpus.json`，共 318 个问答切片；独立包已包含 |

AME 服务由核心和 HTTP 适配代码两部分组成。独立包包含本工程 `deploy/ame/` 的 C++ / Python 服务及知识数据，不依赖文旅 Java 程序。只克隆 AME 核心仓库，无法得到本工程的 `/api/v1/retrieval` 接口。

第 3 节的两种获取方式二选一。有仓库读取权限时可直接使用 Git；SSH 克隆需要 ECS 执行账户已配置 GitHub 读取权限。没有权限时，将以下两个本机文件通过 SFTP 或堡垒机上传到 ECS 的 `/tmp/`：

- `target/ame-service-20261105/ame-service-20261105.tar.gz`
- `target/ame-service-20261105/ame-service-20261105.tar.gz.sha256`

这两个文件是本机交付产物，不在 Git 仓库中。包内是服务源码与知识数据，不是 Docker 镜像；不包含 AME 核心、系统依赖或鉴权令牌。下面的命令使用 root 执行。

## 2. 安装依赖

登录目标 ECS，切换到 root；如果已经是 root，可跳过：

```bash
sudo -i
```

确认系统为 HCE 3.0，架构输出为 `x86_64`：

```bash
cat /etc/os-release
uname -m
```

安装工具：

```bash
dnf makecache
dnf install -y cmake make gcc gcc-c++ git python3 curl ca-certificates file tar gzip
```

检查版本：

```bash
cmake --version
g++ --version
/usr/bin/python3 --version
systemctl --version
```

要求：CMake ≥ 3.16、g++ 支持 C++17、`/usr/bin/python3` ≥ 3.10、systemd ≥ 247。服务固定使用 `/usr/bin/python3`，请检查这个路径的版本。

systemd 通常已随系统安装，无需单独下载。只有确认未安装时才执行 `dnf install -y systemd`。DNF 安装失败时，先检查 [HCE 官方软件源配置](https://support.huaweicloud.com/usermanual-hce/hce_repo.html)。

## 3. 获取代码

### 方式 A：通过 Git 获取服务代码

已配置仓库读取权限时执行：

```bash
git clone --branch codex/ame-ecs-integration --single-branch \
  git@github.com:Heilos66/ai-hardware-subagent-travel.git \
  /opt/ame-adapter-src-20261105

mkdir -p /opt/travel-ame/releases
mkdir /opt/travel-ame/releases/20261105-ame-git
cp -a /opt/ame-adapter-src-20261105/deploy/ame/. /opt/travel-ame/releases/20261105-ame-git/
git -C /opt/ame-adapter-src-20261105 rev-parse HEAD \
  > /opt/travel-ame/releases/20261105-ame-git/adapter-revision.txt
```

只使用 `deploy/ame/`，不编译文旅 Java 程序。完成后跳过方式 B，直接执行下面的“获取 AME 核心”。若目标目录已存在，请使用新的版本目录，不要覆盖。

### 方式 B：上传独立服务包

检查已上传的独立包，预期输出 `ame-service-20261105.tar.gz: OK`：

```bash
cd /tmp
sha256sum -c /tmp/ame-service-20261105.tar.gz.sha256
```

创建本次发布目录并解压。若目录已存在，请使用新的版本目录，不要覆盖：

```bash
mkdir -p /opt/travel-ame/releases
mkdir /opt/travel-ame/releases/20261105-ame-git
tar -xzf /tmp/ame-service-20261105.tar.gz \
  -C /opt/travel-ame/releases/20261105-ame-git

cd /opt/travel-ame/releases/20261105-ame-git
sha256sum -c SHA256SUMS
ls /opt/travel-ame/releases/20261105-ame-git
```

文件校验应全部为 `OK`，目录中应包含 `server.py`、`worker.cpp`、`CMakeLists.txt`、`build.sh`、`install.sh`、`travel-ame.service`、`tests` 和 `corpus.json`。

### 获取 AME 核心

再通过 HTTPS 下载公开的 AME 核心，固定到已验证的提交，无需文旅仓库的 SSH 权限：

```bash
git clone --branch dev-3.0 --single-branch \
  https://github.com/Heilos66/ame.git \
  /opt/travel-ame/releases/20261105-ame-git/upstream

git -C /opt/travel-ame/releases/20261105-ame-git/upstream checkout --detach \
  df68cbf1f2742095901ec97434507d76cba2f61d
```

不要改用 `master` 或跳过提交固定；构建脚本会检查核心版本。

## 4. 检查知识数据

本次使用以下两份知识资料，已转换为 AME 可直接读取的 `corpus.json`，本机文件为 `artifacts/ame-moji-20261105/corpus.json`，不在 Git 仓库中：

| 原始文件 | 问答切片数 |
| --- | --- |
| `摩托车保养知识_扩充版.md` | 252 |
| `摩吉基本知识.md` | 66 |
| 合计 | 318 |

每组完整问答作为一个切片，保留原文和资料名称，只去掉分隔线及切片首尾空行。按 `#### Q:` 标题切分，避免遗漏没有分隔线的问答；没有混入原先的展品语料。原文件和生成文件的 SHA-256、切片数量记录在本机 `artifacts/ame-moji-20261105/manifest.json`。

使用 Git 方式时，通过 SFTP 或堡垒机将上述 `corpus.json` 上传到以下路径。使用独立包方式时，第 3 节解压后已包含该文件，无需再次上传。不要将 Markdown 直接交给 AME worker：

```text
/opt/travel-ame/releases/20261105-ame-git/corpus.json
```

格式是 JSON 数组，每项包含非空字符串 `id`、`document`、`content`，且 `id` 唯一。更换知识内容不要求修改接口的知识库标识，本文继续使用服务默认的 `ame-travel`。

解压后执行检查，本次数据应输出 `chunks = 318`：

```bash
/usr/bin/python3 - <<'PY'
import json
from pathlib import Path

chunks = json.loads(Path('/opt/travel-ame/releases/20261105-ame-git/corpus.json').read_text(encoding='utf-8'))
assert isinstance(chunks, list) and chunks, 'Corpus must be a nonempty array'
for chunk in chunks:
    assert isinstance(chunk, dict), 'Each chunk must be an object'
    for key in ('id', 'document', 'content'):
        assert isinstance(chunk.get(key), str) and chunk[key].strip(), 'Missing or empty field'
assert len({chunk['id'] for chunk in chunks}) == len(chunks), 'Duplicate chunk IDs'
print('chunks =', len(chunks))
PY
```

## 5. 编译和测试

编译 x86_64 worker：

```bash
bash /opt/travel-ame/releases/20261105-ame-git/build.sh \
  /opt/travel-ame/releases/20261105-ame-git/upstream
```

检查二进制和接口测试：

```bash
file /opt/travel-ame/releases/20261105-ame-git/build/ame_worker
ldd /opt/travel-ame/releases/20261105-ame-git/build/ame_worker
/usr/bin/python3 -B -m unittest discover \
  -s /opt/travel-ame/releases/20261105-ame-git/tests -v
```

预期：二进制显示 `x86-64`，依赖没有 `not found`，接口测试全部通过。接口测试通过后，仍需执行第 7 节的真实检索。

Git 方式已在第 3 节记录 `adapter-revision.txt`。独立包方式的文件清单和校验值在 `manifest.json`、`SHA256SUMS`，知识数据来源在 `corpus-manifest.json`。另外记录 AME 核心提交和数据校验值：

```bash
git -C /opt/travel-ame/releases/20261105-ame-git/upstream rev-parse HEAD \
  > /opt/travel-ame/releases/20261105-ame-git/ame-revision.txt
sha256sum /opt/travel-ame/releases/20261105-ame-git/corpus.json \
  > /opt/travel-ame/releases/20261105-ame-git/corpus.sha256
```

## 6. 启动服务

先检查端口和已有服务：

```bash
ss -ltnp 'sport = :18087'
systemctl status travel-ame --no-pager
readlink -f /opt/travel-ame/current
```

首次安装时服务和 `current` 不存在是正常现象。若已有 AME，记录 `current` 的完整路径用于回滚；若 18087 被其他应用占用，先解决端口冲突。

设置服务所需的文件权限，然后安装：

```bash
chown -R root:root /opt/travel-ame/releases/20261105-ame-git
chmod 755 /opt/travel-ame /opt/travel-ame/releases
chmod -R u=rwX,go=rX /opt/travel-ame/releases/20261105-ame-git

systemd-analyze verify /opt/travel-ame/releases/20261105-ame-git/travel-ame.service
bash /opt/travel-ame/releases/20261105-ame-git/install.sh \
  /opt/travel-ame/releases/20261105-ame-git

systemctl status travel-ame --no-pager
```

安装脚本会设置开机启动、切换 `current` 并启动服务。默认监听 `0.0.0.0:18087`，首次生成鉴权令牌 `/etc/travel-ame/token`，后续安装保留该令牌。令牌文件仅 root 可读，服务通过 systemd `LoadCredential` 获取令牌。

## 7. 验证服务

首次启动需要加载词典和知识数据，可能需要数十秒。检查健康状态：

```bash
curl --fail --max-time 5 http://127.0.0.1:18087/health
```

预期返回 `{"ready": true}`。如果尚未就绪，查看日志，加载完成后再次执行：

```bash
journalctl -u travel-ame -n 80 --no-pager
```

执行真实检索。以下命令直接读取令牌文件，不需要手动复制 Token。问题的 Unicode 转义会被还原成中文后发起查询：

```bash
/usr/bin/python3 - <<'PY'
import json
from pathlib import Path
from urllib.request import Request, urlopen

request = Request(
    'http://127.0.0.1:18087/api/v1/retrieval',
    data=json.dumps({
        'dataset_ids': ['ame-travel'],
        'question': "\u6df1\u5733\u6469\u5409\u667a\u884c\u79d1\u6280\u6709\u9650\u516c\u53f8\u7684\u6838\u5fc3\u4e1a\u52a1\u5b9a\u4f4d\u662f\u4ec0\u4e48\uff1f",
        'page': 1, 'page_size': 3, 'similarity_threshold': 0,
    }).encode('utf-8'),
    headers={
        'Content-Type': 'application/json',
        'Authorization': 'Bearer ' + Path('/etc/travel-ame/token').read_text(encoding='utf-8').strip(),
    },
)
with urlopen(request, timeout=12) as response:
    result = json.load(response)
assert result['code'] == 0, 'Retrieval failed'
assert result['data']['chunks'], 'No chunks returned'
Path('/tmp/ame-retrieval-result.json').write_text(
    json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8',
)
print(json.dumps(result, ensure_ascii=True, indent=2))
print('UTF-8 response saved to /tmp/ame-retrieval-result.json')
PY
```

终端输出使用 ASCII 转义，避免中文乱码。需要阅读实际中文结果时，通过 SFTP 下载 `/tmp/ame-retrieval-result.json`，在本机以 UTF-8 打开；重复执行会覆盖该测试结果文件。

用下面两个问题分别验证两份资料：

| 问题 | 应召回的资料与答案内容 |
| --- | --- |
| 深圳摩吉智行科技有限公司的核心业务定位是什么？ | `摩吉基本知识`：面向全球两轮车厂商提供智能网联化硬件及软件平台解决方案 |
| 摩托车链条传动系统通常需要进行哪些维护？ | `摩托车保养知识_扩充版`：定期清洁、润滑，并检查链条松紧度及磨损情况 |

第一个问题已包含在上面的脚本中。验证第二个问题时，将脚本中 `question` 对应的字符串值替换为以下 ASCII 字符串，再执行完整脚本；无需输入中文：

```json
"\u6469\u6258\u8f66\u94fe\u6761\u4f20\u52a8\u7cfb\u7edf\u901a\u5e38\u9700\u8981\u8fdb\u884c\u54ea\u4e9b\u7ef4\u62a4\uff1f"
```

再检查不带 Token 的请求是否被拒绝，预期输出 `401`：

```bash
curl --silent --show-error --max-time 10 --output /dev/null --write-out '%{http_code}\n' \
  -H 'Content-Type: application/json' \
  --data '{"dataset_ids":["ame-travel"],"question":"\u6469\u5409\u667a\u884c\u7684\u6838\u5fc3\u4e1a\u52a1\u662f\u4ec0\u4e48\uff1f"}' \
  http://127.0.0.1:18087/api/v1/retrieval
```

持续查看服务日志：

```bash
journalctl -u travel-ame -f
```

检索成功日志包含 `ame_retrieval status=succeeded chunks=... elapsed_ms=...`。按 `Ctrl+C` 退出日志查看，不会停止服务。

## 8. 开放私网访问并记录接口信息

在 ECS 安全组放行 TCP 18087，来源限定为实际调用方的 IP/CIDR 或安全组。主机防火墙也需允许对应来源，并确保调用方到 ECS 的私网路由可达。

以下以 ECS 私网 IP `192.168.0.25` 为例，使用时替换为实际地址：

| 项目 | 值 |
| --- | --- |
| 健康检查 | `GET http://192.168.0.25:18087/health` |
| 检索接口 | `POST http://192.168.0.25:18087/api/v1/retrieval` |
| 知识库 ID | `ame-travel`，请求中使用 `"dataset_ids":["ame-travel"]` |
| 鉴权 | `Authorization: Bearer <Token>`，Token 保存在 ECS 的 `/etc/travel-ame/token` |

从调用方所在网络验证健康检查：

```bash
curl --fail --max-time 5 http://192.168.0.25:18087/health
```

返回 `{"ready": true}` 表示调用方可访问 AME。带 Token 的检索请求格式见第 7 节；从其他机器测试时使用私网地址，并通过受控方式获取 Token。检索成功要求 `code=0`，且返回内容与知识数据相符。

## 9. 更新和回滚

更新代码或知识数据时，重新获取 Git 服务代码或准备更新后的服务包，并使用新的目录名称重新执行第 3～7 节。不要在 `/opt/travel-ame/current` 中直接更新代码或覆盖知识数据。升级 AME 核心提交时，还需同步更新并验证 `build.sh` 的版本约束。

回滚时，使用第 6 节记录的旧目录执行安装脚本。例如，**仅当旧目录确实为 `/opt/travel-ame/releases/20261104-ame-git` 时**执行以下命令；否则将两处路径改成实际旧目录：

```bash
bash /opt/travel-ame/releases/20261104-ame-git/install.sh \
  /opt/travel-ame/releases/20261104-ame-git
```

回滚后重新执行健康检查和检索验证。切换版本会重启服务，并重新加载知识数据。

## 10. 常见问题和验证范围

终端显示乱码时，可先执行以下只读检查：

```bash
locale
locale charmap
locale -a
```

`locale charmap` 用于查看当前字符编码，`locale -a` 列出可用的语言环境。若编码不是 UTF-8，应使用系统实际存在的 UTF-8 locale；若已经是 UTF-8 仍显示乱码，还需检查 SSH / 堡垒机客户端的编码和字体。终端无法粘贴中文也可能与客户端有关，不能仅凭显示现象认定知识文件已损坏。本文的命令全部为 ASCII，文件读取显式使用 UTF-8，可先按本文完成部署，无需先修复中文显示。

| 问题 | 处理方式 |
| --- | --- |
| Git 提示 `Permission denied (publickey)` | 配置 ECS 执行账户的 GitHub 读取权限，或使用第 3 节的独立包方式 |
| AME 核心克隆超时或证书错误 | 检查 ECS 的 GitHub HTTPS 出口、DNS 和 CA 证书 |
| 独立包或文件校验失败 | 重新上传原始包和对应校验文件，通过后再解压或构建 |
| `Unexpected AME revision` | 按第 3 节检出固定的 AME 提交 |
| `LoadCredential` 不识别 | 检查 systemd 是否 ≥ 247 |
| 服务启动失败 | 查看 `journalctl`，检查 Python 版本、文件权限、知识数据和端口 |
| 检索返回 401 / 400 | 分别检查 Token；知识库 ID 和请求参数 |
| 本机成功、远程调用超时 | 检查私网路由、安全组、主机防火墙和请求 URL |

本文已核对服务脚本、命令语法和路径；本次 318 个问答切片通过 JSON 结构、ID 唯一性和原文完整性校验，尚未导入目标 ECS，也尚未验证这份新语料的实际召回质量。此前 Ubuntu ECS 的部署验证不能替代本次 HCE ECS 的实际验证；新 ECS 仍需按第 5～8 节完成 AME 编译、检索和私网访问验证。

[OBS 离线部署文档](ame-private-ecs-obs-deployment.md) 保留为备用方案，当前联网部署不需要离线源码包。
