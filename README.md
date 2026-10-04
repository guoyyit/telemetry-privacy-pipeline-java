# Telemetry Privacy Pipeline

面向客户端遥测的隐私采集与合规导出管道。上报事件可能包含敏感字段与用户标识，
本管道在采集、存储、导出各环节落实数据最小化，并在并发与失败场景下保持结果可解释。

## 架构

```
client → IngestionService → [consent + collection policy] → WAL → VersionedEventStore
                                                                        │
              ExportController ← DefaultExportService / AggregationExporter
                                                                        │
              PrivacyController → ConsentRegistry / RetentionService ───┘
```

- **采集**（`ingest`）：先校验用户同意与采集策略，通过校验的事件经 WAL 落盘后原子提交。
- **存储**（`store`）：MVCC 版本化存储，提交即产生新的不可变版本。
- **导出**（`export`）：按调用方角色应用最小化视图；聚合导出带 k-匿名抑制。
- **合规**（`retention` / `consent`）：保留期清除与撤回同意后的删除。

## 隐私语义

### 1. 采集准入（拒绝可区分、可追溯）
事件进入存储前依次检查：用户同意（`NO_CONSENT` / `CONSENT_WITHDRAWN`）、
事件类型白名单（`EVENT_TYPE_DENIED`）、字段拒绝规则（`FIELD_POLICY_DENIED`）。
任一检查失败，事件**不会进入存储**，且 `IngestionResult.dropReason` 给出可区分的原因。

### 2. 字段级脱敏（确定性）
`RuleBasedRedactor` 按规则对字段做 `MASK`（固定掩码 `***`）、`HASH`
（HMAC-SHA-256，盐 + 字段名参与计算，即伪匿名化）或 `DROP`（删除字段）。
同一输入在任意处理顺序、任意线程、任意 JVM 实例下得到**完全相同**的脱敏结果。

### 3. 导出最小化视图（越权拒绝并留痕）
存储中保留原始值；导出时按调用方角色应用视图：
- `STANDARD`：只能获得脱敏后的最小化视图；
- `PRIVILEGED`：可请求 `raw=true` 获取原始值。
`STANDARD` 调用方请求原始视图会被拒绝（`AccessDeniedException`），
且所有允许/拒绝的访问都写入 `AuditLog`。

### 4. 聚合抑制（确定性 k-匿名）
`AggregationExporter.countByField` 按字段值分组计数。样本数小于 `minGroupSize`
的分组绝不单独出现：它们被合并进 `__other__` 桶；若合并后仍小于最小规模则整体抑制。
结果按分组键排序，同一已提交状态下输出确定，与事件到达顺序无关。

### 5. 保留期与删除（防复活）
`RetentionService` 清除超过保留期的事件，并删除撤回同意用户的全部事件。
删除在存储层安装**持久墓碑**（已删用户集合、保留期截止点）：删除前已在途的并发写入
在提交时会被墓碑过滤，已删除内容不会因并发写而复活。

### 6. 读写并发（版本一致性）
存储为 MVCC：提交在锁内构造新的不可变状态并原子切换；读方通过一次 volatile
读取获得**某个已提交的完整版本**，绝不出现字段级/批次级混合；慢导出不阻塞写入。

### 7. 中断恢复（不丢、不重、无部分写入）
每个批次先写 WAL（临时文件 + 原子重命名），状态在磁盘上可区分：
`*.pending`（未提交）与 `*.committed`（已提交，含版本号）。
重启时 `WalRecovery` 按版本顺序重放已提交批次、丢弃未完成批次；
存储按批次号幂等提交，重复重放不会产生重复数据。

## 配置项（`application.properties`）

| 配置 | 说明 | 示例 |
|---|---|---|
| `privacy.policy.allowed-event-types` | 允许采集的事件类型（空 = 全部允许） | `page_view,error` |
| `privacy.policy.denied-fields` | 命中即整事件拒绝的字段 | `ssn,password` |
| `privacy.redaction.rules` | 脱敏规则 `字段:动作`，动作为 `MASK/HASH/DROP` | `email:HASH,ip:MASK` |
| `privacy.redaction.salt` | HASH 脱敏的盐，变更即更换伪匿名标识 | `change-me-in-production` |
| `privacy.retention-period` | 保留期（ISO-8601 Duration） | `PT720H`（30 天） |
| `privacy.wal.dir` | WAL 目录 | `./data/wal` |

## HTTP API

| 方法/路径 | 说明 |
|---|---|
| `POST /api/ingest` | 上报单个事件，返回接受/丢弃及原因 |
| `POST /api/ingest/batch` | 批量上报，逐事件返回结果 |
| `GET /api/export?raw=false` | 导出视图；请求头 `X-Caller-Id`、`X-Caller-Role`（`STANDARD`/`PRIVILEGED`） |
| `GET /api/export/aggregate?field=country&minGroupSize=5` | 聚合导出（k-匿名抑制） |
| `POST /api/privacy/consent/{userId}` | 授予同意 |
| `DELETE /api/privacy/consent/{userId}` | 撤回同意（后续保留期执行将清除其数据） |
| `POST /api/privacy/retention/enforce` | 立即执行保留期与删除 |

## 测试

`mvn test` 覆盖：采集拒绝（`IngestionRejectionTest`）、脱敏一致性
（`RedactionDeterminismTest`）、越权拒绝与留痕（`ExportAuthorizationTest`）、
聚合抑制（`AggregationSuppressionTest`）、删除复活与读写并发
（`DeletionAndConcurrencyTest`）、中断恢复（`CrashRecoveryTest`、
`WalRecoveryStartupTest`）、保留期（`RetentionTest`）。
