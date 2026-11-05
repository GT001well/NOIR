# 本机运行手册

面向本机（Arch Linux，Kali/平板等真机）的 NOIR 使用记录。构建步骤见 `BUILDING.md`，加固改动见 `security-hardening.md`，代码审查结论见 `security-review.md`。

## 常用命令

```bash
NOIR=./cli/build/install/noir/bin/noir

$NOIR devices                        # 确认设备已授权
$NOIR scan  --serial <SERIAL> --out <目录>   # 只读盘点，出报告
$NOIR ui    --serial <SERIAL> --out <目录>   # 只读盘点 + 终端仪表盘
$NOIR auto  --serial <SERIAL> --out <目录>   # 只读盘点 + 生成处置计划（dry-run）
$NOIR deep  <包名>                   # 拉单包 + jadx 反编译（会占磁盘）
```

`scan` / `ui` / `auto` 默认不修改设备，只有 `--apply` 才会执行 appops；`--purge` 才会卸载用户应用。

## 输出目录

```
report.md                 人类可读报告
report.json               结构化结果，后续分析以它为准
domains-trackers.txt      可整份拦截（统计/广告/崩溃上报）
domains-other.txt         需逐条判断
domains-functional.txt    禁止拦截（推送/登录/支付/商店/更新）
plan.txt                  本次可执行的 appops
plan-rollback.txt         按 plan 推测的回滚，未执行，仅供参考
rollback.txt              真实回滚命令，执行前写入，记的是改动前模式
work/                     中间文件，含触发的兜底拉包（可删）
```

## 读报告的顺序

1. `report.md` 的 Overview 表按分数排序，但**先看 sharedUid 列**：`android.uid.system` 的条目分数由共享 UID 的权限并集决定，不代表该应用自身申请并取得了全部权限。本机实测 190 个被标记应用中，28 分以上几乎全部来自这一组。
2. 再看用户应用（`system=false`）的分段，这部分才反映实际安装的软件。
3. 域名三份清单直接决定能做什么：`trackers` 进拦截列表，`functional` 一律不要碰，`other` 逐条看。

## 已知问题

### 1. `scan` 写出的建议是空表

`cli/src/main/kotlin/app/noir/cli/Main.kt` 的 `cmdScan` 调用 `writeReports(result, emptyList())`，因此 `noir scan` 生成的 `report.md` 恒为 `recommendations: 0`，`plan.txt` 为空。

`ui` 与 `auto` 走 `Advisor.advise`，同一份数据会得到非空建议（实测同一台设备：scan 0 条，ui 43 条建议 / 15 条 auto-safe）。

要处置计划就用 `ui` 或 `auto`，不要以 `scan` 的输出判断「没有可执行项」。

### 2. appops 计划可能全部为空转

`PROJECT_MEDIA` 在很多 ROM 上对系统应用默认就是 `ignore`。执行前先确认当前值：

```bash
adb shell cmd appops get <包名> PROJECT_MEDIA
```

实测某 vivo 设备上 10 条 `PROJECT_MEDIA` 建议全部已是 `Default mode: ignore`，执行后不改变任何状态；同一批里 5 条 `GET_USAGE_STATS` 为 `default`，只有它们会真正生效。

### 3. 端点抽取有 400 条上限

设备端脚本对每个 APK 最多回传 400 个 URL（`head -400`），大型应用（视频、浏览器、应用商店类）会撞上限，样本被截断。报告中端点数为 400 的条目不能当作「只有这些域名」。

### 4. `deep` 与 `deepscan` 会拉整包

`scan` / `ui` 的端点抽取在设备端完成，只回传几 KB；若设备缺少 `unzip` 或 `strings`，或个别 APK 无法被设备端解压，会退回「整包拉回本地」的兜底路径（实测某设备上 2 个包触发，共 74 MB）。

`deep` 与 `deepscan` 则一定会 `adb pull` 整包交给 jadx，大游戏可能占用数 GB。

### 5. Windows 下 jadx 经 `cmd /c` 启动

`deep` / `deepscan` 在 Windows 上会走 `Exec.argv` 的批处理分支：`.bat` / `.cmd` 被包成 `cmd /c <路径> <参数…>`。cmd.exe 会重新解析整条命令行，所以 jadx 安装路径里出现空格、`&`、`^`、`%` 或引号时，可能被当成语法，表现为启动失败或参数被吞。

现状与规避：

- 把 jadx 放到不含空格与特殊字符的目录（例如 `C:\jadx`），用 `--jadx C:\jadx\bin\jadx.bat` 指定，可以绕开绝大多数情况。
- 彻底的做法是不经过 shim：用 `java.exe -cp "<jadx>\lib\*" jadx.cli.JadxCLI <参数>` 直接启动。本仓库尚未实现，需要同时处理「下载解压目录」「scoop shim」「任意路径解压」三种布局。
- Linux / macOS 不受影响，那两个平台走的是纯 argv 启动。

## 卸载与清理

工具没有安装步骤，也不写系统目录，删文件就算卸载。

**构建产物**（在项目根）：

```bash
rm -rf build cli/build core/build .gradle
```

**本机缓存与下载的工具**：

```bash
rm -rf ~/.noir            # endpoints-v3.tsv 缓存 + tools 下自动下载的 adb/jadx
```

**扫描产物**：删掉 `--out` 指定的目录，默认是项目或当前目录下的 `noir-out/`。

**设备侧残留**（新版本扫描结束会自动清，旧版本或中断时可能留下）：

```bash
adb shell rm -f /data/local/tmp/noir_scan.sh /data/local/tmp/noir_targets.txt /data/local/tmp/noir_dex.bin
```

**撤销对设备的改动**：

- appops：按 `rollback.txt` 逐条执行，格式是 `adb shell cmd appops set --user <用户> <包名> <操作> <原模式>`。
- 被 `--purge` 卸载的应用：`adb shell pm install-existing --user <卸载时的用户号> <包名>`，数据不会回来。

`~/.gradle` 是 Gradle 的全局缓存，删了会波及其它项目，一般不动。如果自己加过启动链接或包装脚本，删掉那一个文件即可，不影响构建产物。

## 回滚

`rollback.txt` 每条在执行前落盘，格式为 `adb shell cmd appops set --user <用户> <包名> <操作> <原模式>`，逐条执行即恢复。因为写在执行前，命令结果未确认时也会留一条，宁可多一条也别丢恢复手段。

`plan-rollback.txt` 是按计划推测的，不构成执行记录；重跑 scan / ui / auto 只会刷新它，不会动 `rollback.txt`。

被 `--purge` 卸载的用户应用可用 `adb shell pm install-existing --user <卸载时的用户号> <包名>` 恢复（`rollback.txt` 之外，卸载日志里会打印具体用户），但该用户下的应用数据不会回来。
