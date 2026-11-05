# 安全加固说明（相对上游 v0.2.6）

本仓库基于 [Verlintas/NOIR](https://github.com/Verlintas/NOIR) v0.2.6（commit `7b14d6c`）。main 上叠加的改动只做边界检查、结果准确性与资源收尾，不新增功能、不改界面。

## 改动分组

**输入与路径边界**

| 文件 | 问题 | 改法 |
|---|---|---|
| `core/.../platform/Setup.kt` | 下载 adb / jadx 后不校验内容 | 写入时流式计算 SHA-256，与常量比对；常量留空时打 warning，不匹配则删文件并返回失败 |
| `core/.../platform/Setup.kt` | 解压不防路径穿越（zip slip） | 用 `canonicalFile` 规范化落点，拒绝绝对路径与越界条目，末尾汇总跳过数 |
| `cli/.../Main.kt` | 包名直接拼进设备 shell 命令、当路径段配 `deleteRecursively` | 新增 `pkgValidationError`，`deep` / `block` / `analyzeOne` / purge 四处前置校验，非法则跳过；`analyzeOne` 在最前拦截，删除走不到 |

包名规则：非空、长度不超过 255、字符集 `[A-Za-z0-9._]`、不以 `.` 或 `-` 开头、不含 `..`。与 Android 安装器的约束一致，正常包不会误拦。

**结果可信度**

| 位置 | 问题 | 改法 |
|---|---|---|
| `performScan` | 清单或 dumpsys 失败时仍生成「零候选」报告 | 空清单、非零退出、空输出、读取截断都直接中止 |
| `DeviceScan` | 设备端脚本没跑完的包被当成「零端点」 | 记录已处理包集合，未完成的进失败集合，不写缓存 |
| `performScan` | 抽取失败只打在控制台 | 写 `incomplete.txt`，并在控制台提示重试 |
| `analyzeOne` | jadx 非零退出但已有输出时按成功计分 | 非零退出直接判失败；超时报 `jadx timeout after Ns` |
| `applyCommands` | appops 只看退出码，命令返回 0 但状态没变也算成功 | 以执行后的实际模式为准，读不到标 `unverified`，都不计成功 |
| `Adb` | `devices` 按行号解析，daemon 日志会被当成设备 | 按表头定位，表头缺失或命令失败返回空 |
| `Args` | 未知选项、缺值被静默忽略 | 白名单校验，未知选项与缺值直接报错 |
| `awaitDevice` | 非交互多设备时自动取第一台 | 直接报错，要求 `--serial` |

**回滚与处置**

- `rollback.txt` 每条在改动设备之前写入，跨轮追加，不会被后续扫描覆盖；按计划推测的内容改到 `plan-rollback.txt`。
- 读不到 AppOp 原值时跳过该条，不猜 `default`。
- `auto --apply` / `deepscan --apply` / `block --apply` 有失败时以非零码退出。
- `--purge` 卸载与恢复提示使用扫描到的实际用户号，不再固定 `user 0`。

**资源与超时**

- `Adb.exec` / `execStreaming` 的读取移到独立线程，超时能真正打断；读不全时 `ExecResult.truncated` 置位。
- `Jadx.decompile` 默认 1800 秒超时，可用 `--timeout` 覆盖；日志落临时文件，不进堆内存。
- 设备端扫描结束（含异常）清理 `/data/local/tmp` 的脚本与清单，清理本身限时 10 秒。
- dex 用 `unzip -Z1` 动态枚举全部分片，老 ROM 不支持时退回固定名单。

**缓存与权限表**

- 缓存键改为「设备序列号 + 系统指纹 + 包名 + APK 路径」，缓存版本 v3。
- `Rules.SENSITIVE` 补齐粗定位、旧存储、短信、拨号、音频等权限，并全部映射到 `Exposure` 的数据类型；缺映射会有测试报错。

## 已知限制

1. **哈希常量默认留空。** `ADB_*_SHA256` 与 `JADX_SHA256` 需按发布值填入才生效。`platform-tools-latest-*` 是滚动 URL，哈希无法预先确定，真要做需同时改成版本化 URL；jadx v1.5.6 的 release 没有 `.sha256` 附件。
2. **解压不识别符号链接。** `ZipEntry` 不暴露 Unix 文件模式，靠 canonical path 拦越界写入；「指向目录外的符号链接再跟随写入」这一情形不覆盖。
3. **构建链只依赖 TLS。** `gradle-wrapper.properties` 没有 `distributionSha256Sum`；仓库自带 wrapper jar 经检查只含 `org.gradle.*` 类。
4. **split APK 只扫基础包。** `pm list packages -f` 只给 base APK，split 里的端点仍会漏；dex 数量不再受限，但分包本身没解决。
5. **Windows 下 jadx 经 `cmd /c`。** 安装路径带空格或元字符可能被解析坏，见 `runbook.md` 已知问题 5。

## 验证记录（2026-11-05）

构建与测试：

```
./gradlew :core:jvmTest :cli:test :cli:installDist
BUILD SUCCESSFUL
tests=37 failures=0 errors=0
```

行为实测：

```
$ noir deep 'bad;pkg'
skip package 'bad;pkg': package name contains unsupported characters

$ noir deep '..'
skip package '..': package name must not start with '.' or '-'

$ noir scan --bogus
未知选项 --bogus。

$ noir scan --out
选项 --out 缺少取值。
```

- zip slip：构造含 `../escaped.txt`、`/tmp/absolute.txt`、`sub/../../escaped2.txt` 的压缩包，输出 `skipped 3 unsafe zip entries`，正常条目解出，三个越界文件都没生成。
- 设备清理：真机扫描 190 个应用后 `adb shell ls /data/local/tmp/noir_*` 返回 `No such file or directory`。
- 平板全量重扫（清空缓存）：190 个候选、155 个应用抽出端点、11637 条端点，重建缓存与新脚本结果一致，无失败包。
- 只读命令不会生成 `rollback.txt`，`plan-rollback.txt` 只有头部；`auto` 生成 15 条计划与 15 条推测回滚。

## 与上游的关系

这些改动只发布在 fork 的 main 上，没有提交上游。若要向上游提出，需要先补齐哈希常量、split APK 支持与对应测试。
