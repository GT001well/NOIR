# 文档索引

上游文档（`site.md`、`og-card.html`、`screenshot.png`）保持原样，以下是本 fork 追加的文档。

| 文档 | 内容 |
|---|---|
| [`../BUILDING.md`](../BUILDING.md) | 从源码构建、产物说明、首次构建的网络访问与代理配置 |
| [`runbook.md`](runbook.md) | 本机使用手册：常用命令、输出目录、读报告顺序、已知问题、回滚方式 |
| [`security-hardening.md`](security-hardening.md) | 相对上游 v0.2.6 的五处加固：改动、校验规则、已知限制、验证记录 |
| [`security-review.md`](security-review.md) | 上游 v0.2.6（commit `7b14d6c`）的第三方代码审查结论 |
| [`../skills/noir-android-audit/SKILL.md`](../skills/noir-android-audit/SKILL.md) | 可移植的 agent skill：触发条件、三条命令、读分口径、四个坑、处置纪律 |

版本对应关系：本 fork 的加固基于 commit `7b14d6c`（v0.2.6）。上游后续版本若改动相关文件，`security-review.md` 的结论需要重新核对。
