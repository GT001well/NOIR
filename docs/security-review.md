# 第三方代码安全审查

审查对象：[Verlintas/NOIR](https://github.com/Verlintas/NOIR) v0.2.6，commit `7b14d6c`，2026-11-05 拉取。
审查方式：源码通读（28 个 Kotlin 文件，4286 行）加汇聚点扫描（网络、子进程、文件写入、环境变量、内嵌数据），未运行上游二进制。

## 结论

代码没有发现后门、外联上报、提权或持久化行为，可以作为本地工具使用。

主要短板集中在下载与解压环节，且都可以通过「不使用 `noir setup`、改为源码构建」规避，本分支已就地加固（见 `security-hardening.md`）。

## 风险项

| 级别 | 项目 | 位置 | 说明 |
|---|---|---|---|
| 中 | 下载组件不校验哈希 | `Setup.kt` `download()` | 仅判断 HTTP 200 |
| 中 | 解压不防路径穿越 | `Setup.kt` `unzip()` | 条目名直接拼接，未做规范化校验 |
| 低 | 设备端命令未转义 | `Main.kt` `pm path`、`cmd appops set`、`pm uninstall` | 包名来自系统输出，字符集受限，实际难以注入 |
| 低 | 包名当路径段并递归删除 | `Main.kt` `analyzeOne()` | 同上，属加固项 |
| 低 | jadx 处理不可信输入 | `Jadx.decompile()` | 风险在 jadx 解析恶意 dex，非 NOIR 自身 |
| 低 | Gradle wrapper 无分发校验和 | `gradle-wrapper.properties` | 首次构建依赖 TLS 信任 |
| 低 | CI 的 actions 按 tag 固定 | `.github/workflows/*` | 行业通例 |
| 提示 | 报告含设备指纹 | `report.json` / `report.md` | 含 fingerprint、serial、主机信息，外发前需清理 |
| 提示 | 设备端残留 | `/data/local/tmp/noir_*` | 上游不清理；本分支已修复 |
| 行为 | `pm uninstall --user 0` | `Main.kt` | 可用 `pm install-existing` 恢复，但该用户数据会清空 |

## README 宣称的核实

| 宣称 | 核实结果 |
|---|---|
| 不联网、无遥测、无版本检查、无崩溃上报 | 成立。网络代码仅出现在 `Setup.kt`，地址为 `dl.google.com` 的 platform-tools 与 jadx v1.5.6 的 GitHub release |
| 不上传数据、不需要账号 | 成立。无外发端点，报告全部落本地 |
| 不修改系统分区、不需要 root | 成立。设备端仅执行 `pm`、`dumpsys`、`getprop`、`unzip`、`strings`、`cmd appops set|get` |
| 不自动卸载，除非显式要求 | 成立。卸载只在 `--purge`（`deepscan`，逐条确认）与 `noir block --apply` 出现；后者不确认、也不检查 `--yes`，执行前只打印恢复命令 |

## 未发现的项目

- 第三方运行时依赖：零。`cli` 仅依赖 `core`，`core` 只有 Kotlin stdlib，测试用 `kotlin("test-junit")`。
- 内嵌数据：无 Base64 大块、无超长单行载荷、除截图与 wrapper jar 外无二进制资源。
- 代码加载：无 `Class.forName`、无 `URLClassLoader`、无 `System.load`。
- 混淆：无，源码可读。
- 子进程：仅 `adb` 与 `jadx`，全部为 `ProcessBuilder` 参数列表形式；Linux / macOS 不经主机 shell，Windows 上 `.bat` / `.cmd` 会包一层 `cmd /c`（见 `runbook.md` 已知问题第 5 条）。
- 写入点：`~/.noir/`、`./noir-out/**`、`/data/local/tmp/`。
- 持久化：无开机自启、无定时任务、无桌面项。

仓库内出现的 HTTP 地址只有三类：测试夹具、分类器的厂商特征表、下载源，没有外联地址。

## 使用时建议

1. 不使用 `noir setup`：本机已有 `adb`，`jadx` 用包管理器安装。
2. 从源码构建，不使用 portable 包，避免二进制与源码不一致的风险。
3. 先 dry-run，`--apply` 前阅读 `plan.txt` 与 `rollback.txt`；`--purge` 单独评估。

## 供应链背景

上游作者为个人开发者，账号注册于 2026-07，仓库 2026-10-31 建仓后数日内获得较高关注度。代码规模小、依赖为零、issue 中被用户报告的真实缺陷均有修复记录，但仓库历史较短，不构成「经过长期审计」的判断依据。本审查针对具体 commit，上游后续版本需重新核对。
