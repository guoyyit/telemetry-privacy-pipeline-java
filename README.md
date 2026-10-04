# 客户端遥测隐私采集与合规导出管道

面向客户端遥测事件的隐私采集与合规导出管道。上报数据可能包含敏感字段与用户标识，
本管道在“采集最小化、字段去标识、导出授权、聚合抑制、删除权、并发一致性与崩溃恢复”
七个方面提供确定性、可解释、可追溯的保证。

技术栈：Java 17、Spring Boot 3.4、无外部数据库（分段追加日志 + fsync 持久化）。

---

## 1. 隐私语义与保证

### 1.1 采集准入（拒绝原因可区分、可追溯）
事件进入存储前依次经过固定判定链，任一环节失败即被拒绝并写入审计日志，**数据绝不落库**：

1. `INVALID_EVENT`：缺少 `eventId` 或 `userId`；
2. `NO_CONSENT`：`consent != true`，用户未同意采集；
3. `EVENT_TYPE_NOT_ALLOWED`：事件类型不在 `pipeline.allowed-event-types` 白名单；
4. `DENY_RULE`：字段命中 `pipeline.denied-fields` 绝对拒绝名单（整事件拒绝）；
5. `CONSENT_WITHDRAWN`：用户已有删除墓碑（撤回同意），迟到/重放上报一律拒绝。

每条拒绝都在 `audit.log` 中留下 `EVENT_DISCARD / DENY` 记录，含具体原因与细节。

### 1.2 字段级脱敏与去标识（确定性、顺序无关）
`pipeline.field-rules.<字段>=KEEP|MASK|HASH|DROP`：

- `KEEP`：原值保留；
- `MASK`：替换为固定掩码 `***REDACTED***`；
- `HASH`：替换为无盐 SHA-256 十六进制摘要，同一输入在任何批次、任何处理顺序下结果一致；
- `DROP`：字段从所有视图中彻底删除。

脱敏结果以**字段名字典序**输出（`TreeMap`），与输入 Map 的迭代/处理顺序无关；
脱敏是无随机盐的纯函数，相同输入永远得到相同输出。原始值仅保存在存储事件的 `rawFields`
中，最小化视图只暴露 `redactedFields`，且最小化视图中不包含 `userId`。

### 1.3 最小化视图与授权（越权拒绝并留痕）
- 导出/查询默认返回最小化视图（脱敏字段、无用户标识）；
- 仅 `PRIVACY_ADMIN` 角色在显式 `raw=true` 时可查看未脱敏原始值；
- 其他角色（`ANALYST`、`SERVICE` 等）请求 `raw=true` 时：
  - 抛出 `AccessDeniedException`，HTTP 映射为 **403 ACCESS_DENIED**；
  - 无论是否抛出，都先写 `RAW_VIEW_DENIED / DENY` 审计记录；
- 聚合路径同样先投影再聚合，无法借聚合绕过最小化视图。

调用方身份通过 `X-Caller-Id` / `X-Caller-Role` 请求头传递，生产环境应由认证网关注入。

### 1.4 聚合抑制（k-匿名式、确定性）
`GET /api/aggregates?field=...` 按字段取值分组计数，规则全部确定：

- 计数 `< pipeline.min-group-size` 的分组**必须被抑制**，不泄露小样本；
- `pipeline.merge-suppressed-groups=true` 时，被抑制分组合并为单一 `__other__` 行；
  合并行自身若仍低于最小分组规模，则继续抑制；
- 输出按组键字典序排列，`__other__` 固定末尾；同一输入反复导出结果逐字节一致。

### 1.5 删除权与保留期（删除不复活）
- `POST /api/users/delete`：撤回同意。删除墓碑（`deletions.log` 的 `D` 记录）先 fsync
  持久化，再发布不含该用户任何事件的新版本；
- `POST /api/admin/retention/purge`：按 `pipeline.retention-days` 推进清除水位
  （`X` 记录），水位之前的事件从所有视图消失；
- 采集与删除在 `EventStore` 的同一把写锁内完成“墓碑判定 → 追加日志 → 发布新版本”，
  **并发/迟到写入不可能让已删除内容复活**；恢复重放时同样应用墓碑与水位。

### 1.6 并发一致性（快照隔离、慢导出不阻塞写入）
- 已发布状态是不可变快照（`StoreSnapshot`：版本号 + 事件列表 + 墓碑集合），以
  `volatile` 引用原子切换；
- 读者只取一次快照引用并遍历不可变对象，**不加写锁**：慢导出不会阻塞写入；
- 任一时刻读者看到的都是某个已提交的完整版本，序号自 1 连续，绝不出现字段级混合；
- 写入以批次为单位原子提交，要么整批进入新版本，要么一条都不可见。

### 1.7 崩溃恢复（不丢不重、无部分写入）
持久化文件位于 `pipeline.data-dir`：

| 文件 | 内容 |
| --- | --- |
| `events.log` | `P`(PREPARE) / `E`(事件) / `C`(COMMIT) 行记录，每条 fsync |
| `deletions.log` | `D`(用户删除墓碑) / `X`(保留期清除水位) |
| `rollback.log` | 恢复时判定为未完成而回滚的批次（可追溯） |
| `audit.log` | 全部合规决策审计记录 |

恢复规则（启动时 `StartupRecovery` 在服务对外可用前执行）：

- 只有读到 `C(COMMIT)` 的批次算已提交并被重放，全局序号连续分配，**不丢不重**；
- 只有 `P` 没有 `C` 的批次判定为**未完成**：整批不进入视图，并把批次 ID 追加到
  `rollback.log`，与已提交批次状态明确区分，绝不留下部分写入；其 eventId 可安全重投；
- 墓碑与清除水位随删除日志重放，删除/过期数据在恢复后同样不可见。
- 恢复状态可通过 `GET /api/admin/recovery` 查看（已恢复事件/批次、回滚批次清单）。

---

## 2. HTTP 接口

| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/events` | 采集单条事件，返回 accepted/seq 或可区分拒绝原因 |
| `POST /api/events/batch` | 采集一批（一个原子批次），支持 `clientBatchId` |
| `GET  /api/events?eventType=&raw=` | 明细查询/导出；`raw=true` 仅 PRIVACY_ADMIN |
| `GET  /api/aggregates?field=&raw=` | 聚合导出（最小分组抑制/合并） |
| `POST /api/users/delete` | 撤回同意：`{"userIds":[...], "reason":"..."}` |
| `POST /api/admin/retention/purge` | 立即执行保留期清除 |
| `GET  /api/admin/audit` | 查询审计留痕 |
| `GET  /api/admin/recovery` | 查询最近一次启动恢复报告 |

事件示例：

```json
{
  "eventId": "evt-1",
  "eventType": "page_view",
  "userId": "u-123",
  "consent": true,
  "fields": { "email": "a@example.com", "phone": "13800000000", "content": "hello" }
}
```

---

## 3. 配置项（前缀 `pipeline`）

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `pipeline.data-dir` | `${java.io.tmpdir}/telemetry-pipeline` | 持久化日志与审计目录 |
| `pipeline.retention-days` | `90` | 保留期天数，超期事件经清除后不可见 |
| `pipeline.min-group-size` | `5` | 聚合导出最小分组规模（k 值） |
| `pipeline.merge-suppressed-groups` | `true` | 被抑制分组是否合并为 `__other__`（false=直接抑制） |
| `pipeline.allowed-event-types[n]` | `page_view,click,performance` | 允许采集的事件类型白名单，空列表=允许全部 |
| `pipeline.field-rules.<name>` | 见 application.properties | 字段脱敏规则：`KEEP/MASK/HASH/DROP` |
| `pipeline.denied-fields[n]` | `health_data,payment_token` | 绝对拒绝字段，命中即整事件拒绝（`DENY_RULE`） |

---

## 4. 构建与测试

```bash
mvn test
```

测试覆盖（`src/test/.../tests`）：

- `RejectionTest`：三类拒绝原因、数据不落库、审计可追溯；
- `SanitizationConsistencyTest`：掩码/摘要/删除与处理顺序无关的确定性；
- `AccessControlTest`：最小化视图、授权 raw、越权 403 且留痕；
- `AggregationSuppressionTest`：小分组抑制/合并、`__other__` 阈值、确定性；
- `DeletionResurrectionTest`：撤回清除、删除后重投拒绝、并发写入不复活、保留期清除；
- `ConcurrentSnapshotTest`：慢导出与多写入并发下的完整版本一致性与不阻塞；
- `CrashRecoveryTest`：已提交批次不丢不重、PREPARED 未 COMMIT 批次回滚且状态可区分、删除/水位恢复；
- `WebApiIntegrationTest`：REST 端到端（拒绝、403、授权、聚合、删除、审计）。
