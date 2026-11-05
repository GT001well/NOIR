# 安全加固说明（相对上游 v0.2.6）

本仓库基于 [Verlintas/NOIR](https://github.com/Verlintas/NOIR) v0.2.6（commit `7b14d6c`），在 `feat/security-hardening` 分支上叠加五处加固。上游功能与界面未做改动，全部改动只涉及边界检查与资源清理。

## 改动清单

| # | 文件 | 问题 | 改法 |
|---|---|---|---|
| 1 | `core/.../platform/Setup.kt` | 下载 adb / jadx 后不校验内容 | 写入时流式计算 SHA-256，与常量比对；为空时打印 warning，不匹配则删除文件并返回失败 |
| 2 | `core/.../platform/Setup.kt` | 解压不防路径穿越（zip slip） | 用 `canonicalFile` 规范化落点，拒绝绝对路径条目与越出目标目录的条目，末尾汇总跳过数 |
| 3 | `cli/.../Main.kt` | 包名直接拼进设备 shell 命令 | 新增 `pkgValidationError`，在 `deep`、`block`、`analyzeOne`、`purge` 四处前置校验，非法则跳过并写 stderr |
| 4 | `cli/.../Main.kt` | 包名当路径段并配 `deleteRecursively` | 同上校验；非法包直接返回带 `error` 的 `DeepFinding`，不会触发删除 |
| 5 | `core/.../adb/DeviceScan.kt` | 设备端临时文件不清理 | `try/finally` 包裹扫描，结束时删除 `/data/local/tmp/noir_scan.sh` 与 `noir_targets.txt`，超时设为 10 秒避免离线设备拖住收尾 |

## 校验规则

包名需满足：非空、长度不超过 255、字符集为 `[A-Za-z0-9._]`、不以 `.` 或 `-` 开头、不含 `..`。

该字符集与 Android 安装器的包名约束一致，因此正常设备不会出现被误拦的包；此校验只对异常输入生效。

## 已知限制

1. **哈希常量默认留空。** 加固点 1 的钩子已就位，但 `ADB_*_SHA256` 与 `JADX_SHA256` 需要按发布值填入才会真正生效。原因是 `platform-tools-latest-*` 是滚动 URL，哈希无法预先确定；要真正固定，需同时改为版本化 URL。jadx v1.5.6 的 release 未提供 `.sha256` 附件。
2. **解压仍不识别符号链接。** `java.util.zip.ZipEntry` 不暴露 Unix 文件模式，无法判断条目是否为符号链接。当前依赖 canonical path 检查拦截越界写入，对「指向目录外的符号链接后跟随写入」这一情形不覆盖。上游下载源为 HTTPS 的 Google 与 GitHub，触发该路径需要上游被替换。
3. **未做下载完整性之外的构建链固定。** `gradle-wrapper.properties` 没有 `distributionSha256Sum`，首次构建依赖 TLS 信任。仓库自带 wrapper jar 经检查只含 `org.gradle.*` 类，无附加内容。

## 验证记录（2026-11-05）

构建与测试：

```
./gradlew :core:jvmTest :cli:test :cli:installDist
BUILD SUCCESSFUL in 11m 34s
tests=32 failures=0 errors=0 skipped=0
```

包名校验实测：

```
$ noir deep 'bad;pkg'
skip package 'bad;pkg': package name contains unsupported characters

$ noir deep '..'
skip package '..': package name must not start with '.' or '-'
```

解压防穿越实测：构造含 `../escaped.txt`、`/tmp/absolute.txt`、`sub/../../escaped2.txt` 的 zip，输出 `skipped 3 unsafe zip entries`，正常条目解出，三个越界文件均未生成。

设备清理实测：真机扫描 190 个应用后，`adb shell ls /data/local/tmp/noir_*` 返回 `No such file or directory`。

## 与上游的关系

本分支只发布到 fork，不提交上游。若要向上游提出，需先补齐哈希常量与对应测试。
