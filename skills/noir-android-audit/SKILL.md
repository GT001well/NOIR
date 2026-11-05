---
name: noir-android-audit
description: "Use when 用 NOIR 审计安卓隐私（扫包/读报告/appops 处置）时用。含构建路径、读分口径与四个坑。"
version: 1.0.0
license: MIT
metadata:
  hermes:
    tags: [android, adb, privacy, audit, noir, appops]
    related_skills: []   # 例：[foo-bar]；按需填相关的 skill 名
---

# NOIR 安卓隐私审计

NOIR 是跑在电脑上的审计工具：通过 adb 读应用清单、权限与 APK 里的网络端点，给风险分与可回滚的 appops 处置。审计对象是本人设备，不涉及未授权目标。

本 skill 只管「怎么用它」；工具本身的构建、加固与审查结论在仓库文档里：

- 构建与产物 → `BUILDING.md`
- 本机命令、输出结构、已知问题 → `docs/runbook.md`
- 相对上游的加固 → `docs/security-hardening.md`
- 上游代码审查结论 → `docs/security-review.md`

## 先决

1. `adb devices` 能看到设备且状态为 `device`（不是 `unauthorized`）。
2. 二进制来自源码构建：`./gradlew :cli:installDist`，路径 `cli/build/install/noir/bin/noir`。不要用 portable 包做安全相关的判断，也不要执行 `noir setup`（下载组件不校验哈希）。
3. `jadx` 只有 `deep` / `deepscan` 需要，用包管理器装。

## 三条命令

```bash
noir scan --serial <S> --out <目录>      # 只读盘点
noir ui   --serial <S> --out <目录>      # 只读盘点 + 终端仪表盘
noir auto --serial <S> --out <目录>      # 只读盘点 + 处置计划（dry-run）
```

只有 `--apply` 才改设备，`--purge` 才卸载。审计场景默认不加。

## 读报告的口径

1. **先看 sharedUid。** 带 `android.uid.system` 的条目分数来自共享 UID 的权限并集，不代表该应用自身取得全部权限。实测某设备 190 个被标记应用里 28 分以上几乎全是这一组，直接按分数排序会得出错误结论。
2. **再看用户应用段**（`system=false`），这部分反映实际安装的软件。
3. **端点数是抽样，不是全集。** 每个应用上限 400 条，视频、浏览器、商店类必然撞顶，报告里 400 就代表截断。
4. **域名三份清单决定动作**：`trackers` 可整份拦，`functional` 一律不碰，`other` 逐条判断。

## 四个坑

| 现象 | 原因 | 处理 |
|---|---|---|
| `scan` 报告 `recommendations: 0`、plan.txt 为空 | `cmdScan` 传了 `emptyList()`（Main.kt:272） | 要计划用 `ui` 或 `auto` |
| 执行 appops 后状态没变 | `PROJECT_MEDIA` 对系统应用常默认就是 ignore | 执行前 `adb shell cmd appops get <包> PROJECT_MEDIA` 查当前值 |
| 大游戏扫描很慢 | 慢在设备端 unzip + strings 解压流，不是 USB | 正常，别以为在拉包 |
| work/apks 出现几十 MB | 个别 APK 设备端解压失败触发兜底拉包 | 报告不受影响，目录可删 |

## 处置纪律

- `--apply` 前先读 `plan.txt`，确认每条 appop 的作用面（`PROJECT_MEDIA` 影响截屏/投屏/识屏，`GET_USAGE_STATS` 一般无感）。
- `rollback.txt` 每条在执行前写入，记的是改动前模式，回滚就是照抄执行。重跑扫描只刷新 `plan-rollback.txt`，不会覆盖它。
- `--purge` 卸载用 `pm install-existing --user <卸载时的用户号> <包>` 能装回来，但用户数据不会回来。
- 报告含设备指纹与序列号，外发前自行清理。

## 参考

- 仓库：`https://github.com/GT001well/NOIR`，相对上游的加固见 `docs/security-hardening.md`
- 上游：`https://github.com/Verlintas/NOIR`（MIT）
