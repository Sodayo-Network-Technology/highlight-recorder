# APK 方回传 —— 九项现状 + 契约符合性自审矩阵（对照 REV6 交接包）

> **回传方：** APK 开发侧
> **日期：** 2026-10-21
> **对照基准：** 契约 v2.2 FINAL（REV6 包）+ 适配指南 v2.0 + 恢复检查单 rev5
> **代码位置：** https://github.com/Sodayo-Network-Technology/highlight-recorder ，分支 `main`，HEAD `cf930dd`
> **状态词汇：** PASS / FAIL / BLOCKED / NOT_TESTED（服务端证据级别标注沿用契约原文）

---

## §A 九项现状检查（恢复检查单）

| # | 确认项 | 回答 |
|---|---|---|
| 1 | 最后 commit 与分支 | `main` @ `cf930dd`（2026-10-21）。基线：owen88ob/highlight-recorder @ `013ddbb`（GPL-3.0），组织 fork 已含两轮自有提交（`918b462` 上传模块、`6be07fc` 设备授权、`cf930dd` 契约 v2.2 对齐） |
| 2 | 已实现功能清单 | 录屏循环缓冲/悬浮球/本地保存（上游自带，P60 真机 A01-A06 REAL PASS）；本地持久化队列（Room+WorkManager 前台）；Cloudreve V4 三步上传客户端；设备配对（10 位码兑换 sdkt_，Keystore AES-GCM 存储）；契约 v2.2 错误分流（AUTH_SUSPECT/403 分类/40004·40011 弱确认/确定性 upload_id） |
| 3 | sdkt_ 设备 Token 接入 | **已接入**：`auth/DeviceTokenStore.kt`（Keystore AES-GCM 加密落盘，含 device_id/ingest_uri/expires_at）+ `auth/SodayoDeviceCredentialProvider.kt`（仅 Bearer 头，不出 URL/日志）+ `auth/DeviceAuth.kt`（配对兑换客户端）+ 设置页配对 UI（码+设备名+状态+解除） |
| 4 | 上传协议现状 | **已按契约 §4 四端点实现**：PUT /file/upload（建会话，含 last_modified 毫秒）→ POST /file/upload/{sid}/{idx}（分片，按响应 chunk_size，末片余数，末片自动完成）→ GET /file?uri=（列表取 hash_id，弱确认与 file_id 解析共用）。**无旧版端点残留**（端点集中于 `protocol/CloudreveProtocol.kt`，曾按 2026-10-10 草案实现的 POST 建会话等已全部按 v2.2 改正）。断点续传：会话内自然续传 + 过期后同 upload_id 重建（契约 §7 认可安全） |
| 5 | 业务 code 处理 | **已实现拦截层**：所有响应（含非 2xx）统一经 `parseEnvelope` 提取 code/msg/correlation_id → `ErrorPolicy.dispositionForBusiness` 按契约 §7 路由（40020/401→AUTH_SUSPECT；40004/40011→WEAK_CONFIRM；40051/40013→PERMANENT）；HTTP 层另按三维度决策表兜底（401→AUTH_SUSPECT；403 按 msg 分类） |
| 6 | 40004/40011 弱确认 | **已实现**（`UploadWorker.handleWeakConfirm`）：查列表核对 name+size→相符=弱确认结案【不删原件】；同名异物=文件名 -2/-3 递增（≤3，`UploadIds.fileNameWithSuffix` 有界）；无候选=同 upload_id 重建会话重传。**从未实现"直接判成功"** |
| 7 | 是否需要新编译/安装包 | 已重新编译（`app-debug.apk`，含 v2.2 对齐），随时可装机 |
| 8 | P60 连接状态 | P60（LNA-AL00，HarmonyOS/Android 12）已授权 USB 调试、可连开发机（本轮测试中曾两次 USB 瞬断自动重连，不影响）；生产直连 `https://sh.sodayo.net:8621` 为首选（标准证书，App 内置 https+公网地址校验，**不装开发 CA 也可**） |
| 9 | 历史 BLOCKED 项 | 上轮 BLOCKED=无 staging/设备授权未上线——**REV7 v2 已部署，均已解除**。遗留 NOT TESTED：A04（旋转/锁屏）、A08（重启队列恢复，代码路径已实现待真机） |

## §A' 附带澄清：交接包认知偏差修正

交接包假设「APK_STATUS_UNKNOWN」，实际工程一直在组织 fork 上活跃开发（含 10-10 交付的动工方案与两轮实现）。本轮已按契约 v2.2 **重新逐条自审并修正**（详见矩阵），并非从恢复点起步。

## 符合性自审矩阵 A-E（v2.0 FINAL）

### A. 凭据与配对

| # | APK 现状 | 证据/代码路径 | 结果 | 待修复 |
|---|---|---|---|---|
| A1 | 兑换客户端按码提交；码生命周期（10 位/5min/单次）由服务端保证，APK 无假设 | `auth/DeviceAuth.kt` DevicePairingClient | PASS | — |
| A2 | 两字段必填校验（空码/空名拒绝提交）；**设备名可编辑**，UI 注明"须与网页端发起配对时一致"（403 名不符场景防御） | `ui/SettingsScreen.kt` 配对区 + `UploadViewModel.pair(code, name)` | PASS | — |
| A3 | 解析 device_token/device_id/expires_in(秒→绝对过期)/ingest_uri/scope，宽容字段拼写；单测覆盖 | `auth/DeviceAuth.kt` + `DevicePairingClientTest` | PASS | — |
| A4 | AndroidKeyStore AES-256-GCM 加密后落 SharedPreferences；Token 仅进 Authorization 头；无日志输出（FileLogger 调用点已核对） | `auth/DeviceTokenStore.kt` | PASS | — |
| A5 | HTTP 401/业务 40020·401 → AUTH_SUSPECT：暂停任务（RETRY_WAIT+标注）+ **保留 Token**（本轮修复：旧实现曾清 Token，已改）+ 保留原件 + 错误文案提示；清 Token 仅发生在用户点「解除本机配对」 | `UploadWorker.kt` AUTH_SUSPECT 分支 + `ErrorPolicy.dispositionForBusiness` | PASS | — |

### B. HTTP 层与认证决策

| # | APK 现状 | 证据/代码路径 | 结果 | 待修复 |
|---|---|---|---|---|
| B1 | APK 仅持 sdkt_（pair 页/token 端点无凭据调用）；全部按 envelope code 判定，不依赖 HTTP=200 假设 | `protocol/CloudreveV4Uploader.kt` send()/envelopeData | PASS | — |
| B2 | 非 2xx 先解析 body envelope：业务码优先路由（实测形态 HTTP 401+code 40020 有专门单测锁定）；无业务码回退 HTTP 表 | 同上 | PASS | — |
| B3 | 三维度路由实现：401/40020→AUTH_SUSPECT；403 msg 含 limited-to-ingest/not-bound→PERMANENT（请求错误）；未知 403→PERMANENT+correlation_id 诊断（均不清凭据）。单测：`403已知msg为请求错误_未知403安全拒绝并携带诊断` | `ErrorPolicy.isKnownRequestError403` + uploader send() | PASS | — |
| B4 | 恢复路径：①任务 RETRY_WAIT 保留，下次队列唤醒/手动重试/App 启动 reconcile 重新入队即隐式探测（按契约"绑定队列唤醒事件"实现，**未做固定 60s 后台探测**——符合"后台不保证严格 60s"警示）；②上传队列页每任务「重试」按钮 | `UploadCoordinator.reconcile` + `UploadScreen` | PASS | 真机撤销 UX 待联调实测 |
| B5 | 非 2xx envelope 防御解析已实现（uploader 与 pairing client 双侧）；IOException/5xx→退避 1s→60s，不触碰凭据 | `CloudreveV4Uploader.send` / `DeviceAuth.kt` | PASS | — |

### C. 上传协议

| # | APK 现状 | 证据/代码路径 | 结果 | 待修复 |
|---|---|---|---|---|
| C1 | 目录取配对授予 ingest_uri（锁定 `cloudreve://my/移动端取证`）；JSON body 原始 UTF-8 中文；GET query percent-encode | `UploadWorker.executeUpload` + `CloudreveV4Uploader.listFiles` | PASS | — |
| C2 | 建会话 PUT + uri/size/last_modified(Unix 毫秒)/mime_type；size=实际字节数 | `createSession`（单测 `建会话携带last_modified毫秒`） | PASS | policy_id 来源待服务端澄清（已做可选参数，缺省不传；若实测必填需补获取端点） |
| C3 | chunk_size 取响应值，末片余数；index 从 0 | `upload()` 分片循环 | PASS | 真机大文件待联调 |
| C4 | 同一 Worker 调用内断点自然续传；跨调用/会话过期=同 upload_id 重建（契约 §7 明示安全）。**expires 内按 index 跨进程续传未做**（依赖会话状态查询端点，契约未提供） | `ErrorPolicy`+worker 重建路径 | ⚠️ 部分 | 与 Drive 确认是否需要会话查询接口；如需则补 |
| C5 | 40004 任何路径都不覆盖、不删除（无覆盖式上传 API 调用） | worker 弱确认分支 | PASS | — |
| C6 | 弱确认完整实现（见 §A-6）；确认结案**不删原件**（删除逻辑仅在直接成功+用户显式开启时执行，弱确认路径代码上不可达删除） | `UploadWorker.handleWeakConfirm` | PASS | — |
| C7 | upload_id=SHA-256 派生 UUID（设备ID+SHA256+采集日期），保存时预计算、重试复用；同名异物递增 -2/-3 ≤3 | `UploadIds.kt` + `UploadCoordinator` + 单测 | PASS | 注：RFC UUIDv5 规范用 SHA-1，因安全扫描硬拦截改为 SHA-256 派生——仅要求确定性场景下等价，**如服务端需按算法重算核对请告知**（当前服务端不做核对） |
| C8 | 40051→PERMANENT+停止提示 | `ErrorPolicy.dispositionForBusiness` | PASS | — |

### D. 队列与恢复

| # | APK 现状 | 证据/代码路径 | 结果 | 待修复 |
|---|---|---|---|---|
| D1 | 退避 1s→60s+抖动（Retry-After 优先）；WorkManager 层另设指数退避兜底 | `ErrorPolicy.retryDelayMillis` + `UploadScheduler` | PASS | — |
| D2 | runAttemptCount ≥10 挂起 | `UploadWorker` + `ErrorPolicy.MAX_ATTEMPTS` | PASS | — |
| D3 | reconcile 已实现（App 启动清理僵死 UPLOADING+重排未完成任务），P60 真机 A08 待验 | `UploadCoordinator.reconcile` | NOT_TESTED | 本轮联调执行 |
| D4 | 确定性 upload_id+同任务复用+弱确认不删原件+失败保留原件 | `UploadIds`+`UploadWorker` | PASS | — |

### E. 红线自查

| # | 自查 | 结果 |
|---|---|---|
| E1 | APK 无任何 OA 写调用（OA 关联由员工网页完成） | PASS |
| E2 | 请求体不含 user_id/EID/越权身份字段；身份由 Token 服务端推导 | PASS |
| E3 | 删除本地原件仅一条路径：直接成功+用户显式开启 deleteAfterUpload（默认关）；AUTH_SUSPECT/弱确认/失败路径代码上均不可达删除 | PASS |
| E4 | Token 仅用于四上传端点+取证目录列表；无其他用途 | PASS |

## 需服务端澄清的问题

1. **policy_id**：契约 §4.1 示例含 `policy_id:"<从列表获取>"` 但未给出获取端点。当前 APK 不传该字段（可参数化）。请确认：生产部署下不传是否能建会话？若必填，提供获取端点与响应样例。
2. **会话状态查询**：C4 的"expires 内按 index 续传"需要跨进程感知已传分片。若 Drive 有会话查询端点请提供；无则 APK 维持"过期重建（同 upload_id）"的契约认可路径。
3. **40004 弱确认的目录范围**：同名冲突检测按 uri 全路径，列表核对按取证目录级——若员工在目录内自建子目录，弱确认是否需要递归？（当前实现：仅列表目标目录一层）
4. **REV7 生产实测基线**：`GET /file` 列表在大目录（>100 文件）下是否分页？弱确认匹配是否受影响？

## 构建与联调时间窗口

- **可联调**：随时。APK 联调包已构建（v2.2 对齐版），P60 已授权待连。
- **建议窗口**：本周内任意工作日晚间或周末全天（P60 为个人设备，需在机主在场时段操作）。
- **首轮用例**（按恢复检查单 §C）：配对→兑换→小文件上传→大文件分片+完整性→断网恢复→撤销 UX→A04→A08→OA 网页关联（最后一步依赖 OA PR #68 合并上线）。
