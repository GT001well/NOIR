```text
  ███╗   ██╗ ██████╗ ██╗██████╗
  ████╗  ██║██╔═══██╗██║██╔══██╗
  ██╔██╗ ██║██║   ██║██║██████╔╝
  ██║╚██╗██║██║   ██║██║██╔══██╗
  ██║ ╚████║╚██████╔╝██║██║  ██║
  ╚═╝  ╚═══╝ ╚═════╝ ╚═╝╚═╝  ╚═╝
```

# NOIR

[![license](https://img.shields.io/github/license/GT001well/NOIR)](LICENSE)
![platforms](https://img.shields.io/badge/platforms-macOS%20%7C%20Windows%20%7C%20Linux-2ea44f)

**把「这台手机到底能泄露什么」变成一份可复现的报告。**

NOIR 是一个跑在电脑上的审计工具，通过 `adb` 读取已连接 Android 设备的应用清单、权限和 APK 里的网络端点，给出分类、风险评分和可执行的处置建议。它也可以在需要时调用 `jadx` 对单个应用做反编译和关键字检索。

> 定位：**审计与自我防护**。面向自己的设备、自己的应用、已授权的安全研究。

**上游 / Upstream:** https://verlintas.dev/noir/ · [中文](https://verlintas.dev/noir/zh/) · [Verlintas/NOIR](https://github.com/Verlintas/NOIR)

## 目录

- [它做什么](#它做什么) · [第一次运行](#第一次运行) · [安装与构建](#安装与构建)
- [用法](#用法) · [DeepScan](#deepscan) · [界面](#界面)
- [输出](#输出) · [缓存](#缓存) · [架构](#架构) · [设计取舍](#设计取舍)
- [隐私](#隐私) · [常见问题](#常见问题)
- [参与](#参与) · [本仓库追加的文档](#本仓库追加的文档) · [许可与免责](#许可与免责)

---

## 它做什么

1. **盘点**：列出所有「有网络权限 + 敏感权限」的应用，以及它们各自拿到了什么。
2. **抽取端点**：在设备端用 `unzip + strings` 从每个 APK 的 dex 里提取 URL，只回传几 KB，不把整包拉回电脑。
3. **分类与打分**：按包名和域名识别厂商（Xiaomi / Google / 腾讯 / 高德 …）与类别（遥测、广告、定位、输入法 …），结合权限和上报端点计算风险分。
4. **数据关联**：把权限翻译成「能拿到什么数据」（位置/通讯录/短信/通话/麦克风/相机/屏幕/应用列表/设备标识/文件/日历/账号/健康传感器），再和上报域名配对，得出「哪类数据可能流向哪里」。
5. **共享 UID**：识别 `android.uid.system` 这类共享用户，列出哪些包实际上共享同一套权限并集。
6. **清单层**：解析 `AndroidManifest.xml` 二进制 AXML，找出没有权限保护的导出组件、明文流量、备份、debuggable、targetSdk。
7. **给建议**：区分「可安全自动执行」「需人工确认」「不建议动」，并为每条动作生成回滚命令。
8. **必要时深挖**：`deep` 会把单个 APK 拉到本地，用 `jadx` 反编译后检索 `Runtime.exec`、`setApplicationEnabledSetting`、`DexClassLoader`、`MediaProjection` 等高风险调用。

### 它不做什么

- 不联网、不上传任何数据、不需要账号。
- 不修改系统分区，不需要 root。
- 不会自动卸载任何东西，除非你显式加 `--purge`（`deepscan`）或用 `noir block --apply`。

## 第一次运行

```bash
noir
```

不带参数会进入向导：

1. 检查 `adb`，没有就**问你要不要自动下载**（`~/.noir/tools`）
2. 检查设备，没有就打印**开发者模式 / USB 调试的开通步骤**，然后**等你插线**（最多 180 秒）
3. 问一句「开始扫描?」，扫完直接出仪表盘
4. 问一句「执行这 19 条安全处置?」，`y` 就执行，并写好回滚文件

全程只会问两三个 y/n，其余都是自动的。接了两台以上设备又不指定 `--serial` 时会直接报错，不会替你挑一台。

自检与环境准备：

```bash
noir doctor        # 打印 platform / java / adb / device / jadx
noir setup         # 缺什么下什么
noir setup --yes   # 不询问，适合脚本
```

所有错误都会带「怎么办」，而不是抛异常栈：

```text
╭─ ERROR ──────────────────────────────────────────────────────────╮
│ 指定的 adb 不存在：/nonexistent/adb                              │
├──────────────────────────────────────────────────────────────────┤
│ 检查路径，或运行 noir setup 自动安装。                           │
╰──────────────────────────────────────────────────────────────────╯
```

没有设备时给的是可执行步骤：

```text
╭─ 没有检测到设备 ──────────────────────────────────────────────╮
│  1. 打开开发者模式                                           │
│     小米 / HyperOS：设置 → 我的设备 → 全部参数 → 连点「MIUI 版本」7 次
│  2. 开启 USB 调试：设置 → 系统 → 开发者选项 → USB 调试        │
│  3. 插线后手机弹「允许 USB 调试吗？」→ 勾选「一律允许」        │
│  4. 还不行就换一根数据线（很多线只能充电）                    │
╰──────────────────────────────────────────────────────────────╯
```

已连接但没授权时，会单独提示「撤销 USB 调试授权后重插」。

## 安装与构建

本仓库不发布 release 包，安装等于从源码构建。

| 系统 | 状态 | 说明 |
|---|---|---|
| macOS (Intel / Apple Silicon) | ✅ | 上游主要开发平台 |
| Windows 10 / 11 | ✅ | `deep` / `deepscan` 下 jadx 经 `cmd /c` 启动，安装路径别带空格和特殊字符（见 [docs/runbook.md](docs/runbook.md) 已知问题 5） |
| Debian / Ubuntu | ✅ | 上游官方列表 |
| Arch 等其它 Linux | 可用 | 构建链与发行版无关，实测 Arch + JDK 21 通过全部测试 |

依赖：

- **JDK 17+**（仅构建需要）
- **Android platform-tools**（`adb`，运行需要）
- **jadx**（`deep` 与 `deepscan` 需要）

程序会自动在常见安装位置查找 `adb` 和 `jadx`；找不到时可以用 `--adb` / `--jadx` 指定。

```bash
# macOS
brew install --cask android-platform-tools jadx

# Debian / Ubuntu
sudo apt install adb
# jadx: https://github.com/skylot/jadx/releases

# Arch
sudo pacman -S android-tools jadx

# Windows (scoop)
scoop install adb jadx
```

构建：

```bash
git clone https://github.com/GT001well/NOIR && cd NOIR
./gradlew :cli:installDist
```

产物是 Gradle `installDist` 布局，启动脚本依赖同级的 `lib/`，不能只拷单个 jar：

```text
cli/build/install/noir/bin/noir        # macOS / Linux
cli/build/install/noir/bin/noir.bat    # Windows
```

开发时可以直接跑：

```bash
./gradlew :cli:run --args="devices"
```

首次构建会下载 Gradle 9.8.2 与 Kotlin 插件；需要代理时注意 Gradle 不读 `https_proxy`，要传 `-Dhttps.proxyHost` / `-Dhttps.proxyPort`。完整说明见 [BUILDING.md](BUILDING.md)。

上游另提供自带运行时的 portable 包（双击启动，不用装 Java），由上游 CI 用 jpackage 打包，见 [上游 Releases](https://github.com/Verlintas/NOIR/releases)。本仓库不提供，那些包与其 `SHA256SUMS.txt` 只对应上游代码。

## 用法

```bash
noir --about              # 它是什么、会写什么、许可
noir --version
noir devices
noir scan                 # 全量扫描，输出 JSON / Markdown / 域名清单
noir ui                   # 扫描并渲染仪表盘
noir auto                 # 扫描 + 分类 + 生成处置计划（dry-run）
noir auto --apply         # 执行安全的 appops 动作（可回滚）
noir deep com.example.app # 拉包 + jadx + 关键字检索
noir block com.example.app        # 打印处置命令（含卸载）
noir block com.example.app --apply
```

常用参数：

| 参数 | 说明 |
|---|---|
| `--serial <id>` | 指定设备 |
| `--out <dir>` | 输出目录（默认 `noir-out`） |
| `--min <score>` | 只保留分数 ≥ N 的应用 |
| `--limit <n>` | 最多输出 N 条（`--all` 时不受此限） |
| `--only <regex>` | 只扫描匹配的包名，例如 `--only 'com\.miui\.'` |
| `--adb <path>` / `--jadx <path>` | 手动指定工具路径 |
| `--timeout <sec>` | `deepscan` 单个应用的反编译超时，默认 1800 |
| `--yes` | 自动化场景下跳过确认 |

参数按白名单校验：未知选项（`--bogus`）和选项缺值（`--out` 后面没东西）都会直接报错，不会悄悄忽略。

### DeepScan

一条命令完成反编译与处置。

```bash
noir deepscan                  # 反编译风险最高的 15 个应用
noir deepscan --top 30         # 更多
noir deepscan --all            # 全部被标记应用（慢，占磁盘）
noir deepscan --jobs 4         # 并行 jadx 数量
```

流程是：扫描 → 按风险排序 → 逐个 `adb pull` + `jadx` → 统计代码级信号
（`Runtime.exec`、`DexClassLoader`、`setApplicationEnabledSetting`、
`enabled_accessibility_services`、`MediaProjection`、`SystemProperties.set`、
短信/设备标识、WebView 桥、无障碍/通知监听/设备管理员 …）→ 解析清单
（导出组件、明文流量、备份、debuggable）→ 识别 SDK（Umeng/Firebase/AppsFlyer…）
→ 输出 `deepscan.md` / `deepscan.tsv` → 询问是否清理。

```bash
noir deepscan --apply          # 分析后执行安全 appops
noir deepscan --apply --purge  # 再逐个确认卸载高风险用户应用
```

反编译结果按「设备 + 包名 + APK 路径」缓存，第二次跑同一个应用会直接复用；换设备不会复用上一台的源码。

## 界面

![NOIR dashboard on a Xiaomi 17 Pro Max](docs/screenshot.png)

真机输出（macOS Terminal）：渐变 logo、CJK 对齐的表格、按风险着色的分数、
每处理完一个 APK 就刷新一次的进度条。

`noir ui` 会把结果渲染成一块等宽仪表盘；颜色和边框都会按终端能力自动降级：

- `--ascii`：纯 ASCII 边框，给旧版 Windows 控制台
- `--no-color` / `--color`：关闭或强制 ANSI 颜色（默认按是否为 TTY 自动判断）
- `--quiet`：只留仪表盘，不打印扫描过程
- `--no-clear`：不清屏（方便把输出重定向到文件）

## 输出

```text
noir-out/
├── report.json               # 结构化结果（应用、权限、端点、建议）
├── report.md                 # 人类可读报告
├── domains-trackers.txt      # 统计/广告/崩溃上报，可整份导入
├── domains-other.txt         # 用途不明，导入前逐条判断
├── domains-functional.txt    # 推送/登录/更新/支付/商店/云同步，禁止拦截
├── domains.txt               # 等于 trackers（兼容旧脚本）
├── plan.txt                  # 本次可执行的 appops
├── plan-rollback.txt         # 按 plan 推测的回滚（未执行，仅供参考）
├── applied.txt               # 实际执行的命令、退出码、完整 stdout/stderr
├── rollback.txt              # 真实回滚命令（执行前逐条写入，跨轮追加）
└── incomplete.txt            # 仅在有包抽取失败时出现，列出未完成的包
```

`report.md` 按分数排序，但分数要先看 `sharedUid` 那一列：挂在 `android.uid.system` 下的应用拿到的是共享权限并集，不代表它自己申请并取得了这些权限。

### 缓存

扫描结果按「设备 + 包名 + APK 路径」缓存在 `~/.noir/endpoints-v3.tsv`（文件名带缓存版本号，版本变化会另起一份）。换设备或系统升级不会串数据；应用更新后路径变化会自动重新分析，重复扫描通常只需几秒。

抽取失败的包不写缓存，下次自动重试，并出现在 `incomplete.txt` 里。

## 架构

```text
adb ──▶ pm / dumpsys ──▶ Dumpsys parser ──┐
                                          ├─▶ cache (device + pkg + apk path)
adb ──▶ unzip/strings ──▶ endpoints ──────┘        │
                                                   ▼
                                          Classifier ─▶ score ─▶ Advisor
                                                   │              │
                                                   ▼              ▼
                                              report.json    plan.txt
                                              report.md      plan-rollback.txt
                                                   │              │
                                                   ▼              ▼
                                                 Tui / CLI      apply
                                                                  │
                                                                  ▼
                                                             rollback.txt
```

`deep` 走另一条支线：`adb pull` → `jadx` → 源码 grep。

### 设计取舍

- **端点抽取在设备端完成**：`adb shell` 内跑 `unzip | strings | grep`，避免把几百个 APK 拉回电脑。设备缺少 `unzip` / `strings` / `grep` 时退化为本地解析。
- **共享 UID 特殊处理**：Android 上 `android.uid.system` 下的应用（常见 50+ 个）权限记录在 `dumpsys package` 尾部的 `Shared users` 段。解析器会把这些权限合并回每个包，否则最该被看见的应用反而会消失。
- **建议分级**：只有 appops 级别的动作标记为可自动执行；卸载必须显式开启。
- **无遥测**：程序不包含任何上报逻辑。

## 隐私

- 工具本身**不联网**：没有遥测、没有版本检查、没有崩溃上报
- 扫描结果只写在你指定的 `--out` 目录
- 唯一的外部请求发生在 `noir setup`（下载 adb/jadx）和 `noir deep`（jadx 是本地进程）
- 报告包含设备型号、序列号与系统指纹，分享前自行删除；`~/.noir/endpoints-v3.tsv` 里是包名与域名清单，同样只留在本机

## 常见问题

**需要 root 吗？**
不需要。全部通过 `adb` 的 shell 权限完成；卸载只是 `--user 0` 级别，APK 仍在系统分区。有些 ROM 会拒绝 shell 层改 AppOps，这时 `applied.txt` 会写明原因。

**会改坏手机吗？**
默认完全只读。`auto --apply` 与 `deepscan --apply` 只改 app-ops；`noir block --apply` 会卸载指定包，执行前打印 `pm install-existing` 恢复命令。

`rollback.txt` 每条都在执行前写入，记的是原始模式，跨轮追加，不会被下一次扫描覆盖；命令失败或结果未确认时也会保留该行。完整 stdout/stderr 与退出码写入 `applied.txt`。有失败时命令以非零码退出。

**为什么有些应用只给「人工复核」？**
系统应用不能真正卸载（`--user 0` 卸载也会被 OTA 或恢复出厂装回来），所以只建议 app-ops 或 DNS 层拦截。

**DNS 拦截为什么只导出不执行？**
跨平台改 DNS 很容易把设备网断掉（我们在开发中踩过），所以只导出清单：

- `domains-trackers.txt` —— 统计/广告/崩溃上报，可以整份导入
- `domains-other.txt` —— 用途不明，导入前逐条判断
- `domains-functional.txt` —— 推送、登录、更新、支付、应用商店、云同步，**拦了会掉功能**
- `domains.txt` —— 现在只包含 trackers，兼容旧脚本

IP、`localhost` 和带 `test`/`staging` 的域名会被自动剔除。

**支持 iOS 吗？**
不支持。`core` 是按 Kotlin Multiplatform 组织的，但审计逻辑依赖 Android 的 `dumpsys` 和 APK 格式。

## 参与

- [CONTRIBUTING.md](CONTRIBUTING.md) — 构建、测试、规则放在哪
- [SECURITY.md](SECURITY.md) — 漏洞报告方式（请不要开公开 issue）
- [Issues](https://github.com/GT001well/NOIR/issues) — 本仓库的问题，请附上 `noir doctor` 输出
- 上游缺陷请报 [Verlintas/NOIR Issues](https://github.com/Verlintas/NOIR/issues)，那里的修复不会自动进本仓库；上游的功能路线图也在那个仓库

### 本仓库追加的文档

本仓库是上游 v0.2.6（commit `7b14d6c`）的 fork。代码侧只做安全加固与结果准确性修正，不新增功能、不改界面；文档侧追加以下文件：

| 文档 | 内容 |
|---|---|
| [BUILDING.md](BUILDING.md) | 源码构建、产物位置、首次构建的网络访问与代理配置 |
| [docs/README.md](docs/README.md) | 文档索引 |
| [docs/runbook.md](docs/runbook.md) | 使用手册：命令、输出结构、读报告顺序、已知问题、回滚 |
| [docs/security-hardening.md](docs/security-hardening.md) | 相对上游的加固改动与验证记录 |
| [docs/security-review.md](docs/security-review.md) | 上游代码审查结论 |
| [skills/noir-android-audit/SKILL.md](skills/noir-android-audit/SKILL.md) | 可移植的 agent skill，整目录可拷入任意 agent 的 skills 目录 |

## 许可与免责

MIT © 2026 Verlintas，本 fork 的修改同样以 MIT 发布，保留原版权声明。

NOIR 只应用于**你自己的设备**，或你获得明确授权的设备。它给出的是静态证据（「这个应用具备这个能力，并且代码里有这个端点」），**不能证明**某次具体传输发生过，请结合网络层证据自行判断。
