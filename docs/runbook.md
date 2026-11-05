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
rollback.txt              对应的回滚命令
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

## 回滚

`rollback.txt` 逐条记录了改动前的 AppOps 模式，格式为 `adb shell cmd appops set --user <用户> <包名> <操作> <原模式>`。逐条执行即恢复。

被 `--purge` 卸载的用户应用可用 `adb shell pm install-existing --user 0 <包名>` 恢复，但该用户下的应用数据不会回来。
