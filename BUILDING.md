# 构建与运行

本文件说明如何从源码构建 NOIR，以及各产物的用途。适用于 macOS、Linux 与 Windows，附 Arch Linux 上的实测注意事项。

## 环境要求

| 项目 | 要求 | 说明 |
|---|---|---|
| JDK | 17 或更高 | 仅构建需要；portable 包自带运行时 |
| Gradle | 无需预装 | 由 `gradlew` 自动下载 9.8.2 |
| adb | 运行期需要 | 可预装，也可由 `noir setup` 下载 |
| jadx | 仅 `deep` / `deepscan` 需要 | 1.5.6 起可用，可预装 |

## 构建

```bash
git clone https://github.com/GT001well/NOIR
cd NOIR
./gradlew :cli:installDist
```

产物：

```
cli/build/install/noir/bin/noir         Linux / macOS 启动脚本
cli/build/install/noir/bin/noir.bat     Windows 启动脚本
cli/build/install/noir/lib/*.jar        cli、core、kotlin-stdlib
```

以 installDist 布局为准：启动脚本依赖同级的 `lib/` 目录，不能只把单个 jar 拷走使用。

版本号来自 `cli/build.gradle.kts`，默认 0.2.6，可在构建时覆盖：

```bash
./gradlew :cli:installDist -PnoirVersion=0.2.7
```

## 开发与测试

```bash
./gradlew :cli:run --args="devices"      # 不安装直接运行
./gradlew :core:jvmTest :cli:test        # 单元测试
```

## 首次构建的网络访问

首次构建会访问外网，共两项：

1. `services.gradle.org` 下载 `gradle-9.8.2-bin.zip`，落在 `~/.gradle/wrapper/dists/`
2. `plugins.gradle.org` 与 `repo.maven.apache.org` 拉 Kotlin 2.4.21 插件与 stdlib

需要代理时注意：Gradle wrapper 用 JVM 自身的 HTTP 栈，**不读 `https_proxy` 环境变量**，必须传系统属性：

```bash
export GRADLE_OPTS="-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 \
                    -Dhttp.proxyHost=127.0.0.1  -Dhttp.proxyPort=7897"
./gradlew :cli:installDist
```

已缓存的发行版位于 `~/.gradle/wrapper/dists/`，清理该目录即等于重下。

## Arch Linux 注意事项

上游 README 的官方支持列表只写了 Debian / Ubuntu。构建链本身与发行版无关，Arch 上按上述步骤即可完成构建与测试（实测 Gradle 9.8.2 + JDK 21，32 个测试全部通过）。

运行期依赖本机 `adb` 与 `jadx`，用包管理器安装即可，无需执行 `noir setup`；这样也避开了 `Setup.kt` 下载环节（见 `docs/security-hardening.md`）。

## 构建后自检

```bash
./cli/build/install/noir/bin/noir --version          # 应输出 NOIR 0.2.6
./cli/build/install/noir/bin/noir doctor             # 检查 adb / java / 设备
./cli/build/install/noir/bin/noir devices            # 列出已连接设备
```

`doctor` 在找不到 jadx 时会给出提示，不影响 `scan` 与 `ui`，只影响 `deep` 与 `deepscan`。
