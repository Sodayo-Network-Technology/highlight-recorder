# Sodayo Android × Drive API 契约 **v2.2 FINAL**（FROZEN，2026-10-21 封版）

> **唯一规范来源**：APK 开发方只需阅读本文档。取代 v2/v2.1 及 2026-10-10 预留版。
> 权威来源：后端源码（`656c7a5`）+ 隔离 staging 真实响应实测 + 生产实测（2026-10-13/10-21）。
> 证据分级标注：**[生产实测]**=生产 curl 原文；**[隔离实测]**=隔离 staging；**[源码]**=后端 Go 源码锚点；**[待核]**=推定未测。
> 后端 API 已 FROZEN，本文档只描述、不改接口。**v2.2 final 变更**：认证三维度决策表（§1）、403 分类（§1/§6/§7）、单位源码核实（§4）、404 配对码修正确认。

## 1. 全局约定

- **传输**：生产 `https://sh.sodayo.net:8621`（APK 禁止关闭 TLS 校验）；隔离联调 `https://…:18443`（开发 CA）或 `http://127.0.0.1:15990`。
- **HTTP 与业务码三态区分（2026-10-21 生产实测修正）**：
  1. **JSON 业务码**：成败由 `code` 判定（`0`=成功）。业务码 401/403/40020 等是**载荷内编码**。
  2. **HTTP 状态码——两种形态并存（生产实测范围：JWT 端点 4 个+设备端点拒绝路径 1 个；其余端点待核）**：
     - **网页 JWT 端点**（**已验证实测**：pair、device/token（无凭据）、file/upload（无凭据）、file/source（无凭据）共 4 条路径）：HTTP **恒 200**，业务码在载荷（含 401 Login required 也是 HTTP 200）。**list/revoke/bind 未逐一实测**——推定与 pair 一致（同 serializer 包装层），**待核**。
     - **设备 Token 端点**（sdkt_ 调 upload 四端点）：中间件拒绝时返回**真实 HTTP 401/403 + JSON 业务码**（**已验证实测**：无效 sdkt_ → HTTP 401+code 40020，1 条路径；其余 403 拒绝路径未逐一实测，**待核**）；成功时 HTTP 200。
     - 真 HTTP 404=路径不存在（已验证）。
  3. **网络层异常**：连接拒绝/DNS/TLS/读写超时——无业务语义，按重试策略处理。
- **APK 实现要点——认证错误三维度决策表（v2.2 终版，rev4→final 冲突修复）**：

  客户端收到非成功响应时，按三个维度决定动作（**禁止仅凭 HTTP 状态码无差别处理**）：
  ①认证上下文（Authorization 前缀：`sdkt_` vs JWT）；②真实 HTTP 状态码；③JSON envelope 业务 code。

  | 场景 | 判定 | 动作 |
  |---|---|---|
  | sdkt_ + HTTP 401 / code=40020、401 | **认证拒绝**（Token 无效/撤销/冻结/权限回收，服务端不区分） | → **AUTH_SUSPECT**：暂停关联队列、保留 sdkt_ Token、保留本地原视频、提示用户检查 |
  | sdkt_ + HTTP 403 + code=403 + msg 含 `limited to ingest folder` / `not bound to this device` | **请求/权限错误**（URI 越界、会话绑定不符——是本次请求的问题，不代表 Token 失效） | → 请求错误：修正客户端逻辑（路径 bug/会话 id 错），**不触发 AUTH_SUSPECT**，不清理凭据 |
  | sdkt_ + HTTP 403，**无法识别**（msg 不匹配已知模式） | **未知拒绝**（服务端未提供稳定区分字段，诚实标注无法进一步区分） | → 安全拒绝：本次操作标记失败+保留凭据与原件+显示诊断信息（含 correlation_id 供回报）；**不误报成功，不触发 AUTH_SUSPECT**，不清理凭据 |
  | JWT + HTTP 200 + code=401 | JWT 失效 | 清除 JWT（APK 通常不持有 JWT；不影响设备 Token） |
  | 网络异常 / 5xx | 传输层 | 退避重试（§7），不触碰任何凭据 |

  **AUTH_SUSPECT 恢复**：①前台期间建议每 60s 受限鉴权探测（GET 取证目录列表 1 条 URI）；②UI 手动重试；③队列网络恢复重试。⚠️ **后台探测的现实性（rev4 final）**：Android 后台受 Doze/系统调度限制，WorkManager **不能保证严格每 60s 执行**——探测应设计为"每次队列引擎唤醒/前台恢复时顺带执行"，而非依赖固定周期后台任务。
- **包装结构（envelope）**：
  ```json
  // ✅ 可解析示例（5 条，生产/隔离实测原文；rev4 精确复核 code 0/40020/404/40004/40051 各 1）
  {"code":0,"data":{"pair_code":"617b876e63","expires_in":300},"msg":""}
  {"code":40020,"msg":"device token invalid, expired or revoked"}
  {"code":404,"msg":"pair code invalid or expired","correlation_id":"9987de05-5c20-42c0-91e7-000000000000"}
  {"code":40004,"msg":"Object existed","correlation_id":"1f4d5407-318b-4bcc-b364-4b5a88dc4bca"}
  {"code":40051,"msg":"Insufficient capacity","correlation_id":"6fc34be5-28c3-48fb-b254-54a2f6e5aaf5"}
  ```
  ```json
  // ⚠️ 结构示意（含占位符，不可直接 JSON.parse；字段名真实、值端点各异）
  {"code": <int>, "data": <obj|null>, "msg": "<string>", "correlation_id": "<uuid 仅错误时>"}
  {"code":40013,"msg":"Invalid Content-Length (expected: <N>)","correlation_id":"<uuid>"}
  ```
  `correlation_id` 仅错误响应携带（排障用，可展示给用户复制）；`data` 可为 null。
- **认证头**：`Authorization: Bearer <jwt 或 sdkt_…>`。

## 2. 三种凭据（用途与寿命）

| 凭据 | 获取 | 寿命 | 用途 |
|---|---|---|---|
| 网页 JWT | OA SSO 登录 Drive | 会话期 | 配对码生成/设备列表/撤销/证据绑定（**设备不具备**） |
| 配对码（10 位） | 网页「设备管理」生成，人工输入设备 | **5 分钟，单次**（并发兑换恰一成功） | 激活设备 |
| 设备 Token（`sdkt_` 前缀） | 配对码兑换，明文仅返回一次 | **90 天**（7776000s） | 设备上传与取证目录列表（scope=ingest） |

## 3. 设备授权端点（JSON 逐字段实测）

### 3.1 生成配对码（网页 JWT）
`POST /api/v4/sodayo/device/pair`
```json
// 请求（device_name 必填 max=64，兑换时必须一致）
{"device_name": "p60-sprint"}
// 响应
{"code":0,"data":{"pair_code":"617b876e63","expires_in":300},"msg":""}
```
失败（业务码）：`403` 无 clouddrive.basic（**权限在配对生成阶段即拦截**）；`40001` 缺 device_name。

### 3.2 兑换设备 Token（设备，无既有凭据）
`POST /api/v4/sodayo/device/token`
```json
// 请求
{"pair_code":"c9f653611a","device_name":"p60-sprint"}
// 响应
{"code":0,"data":{"device_id":2,"device_token":"sdkt_dcHM…","expires_in":7776000,
  "ingest_uri":"cloudreve://my/移动端取证","scope":"ingest"},"msg":""}
```
失败：**`404`**（CodeNotFound，msg="pair code invalid or expired"或"pair code already used"——源码 `serializer.CodeNotFound=404`+生产实测原文；**不是 40404**，40404=CodeObjectExist 仅用于上传同名冲突）；`403` 设备名不符/员工非 active/无 clouddrive.basic（fail-closed）。

### 3.3/3.4 列表与撤销（网页 JWT）
`GET /api/v4/sodayo/device` → 完整 envelope：
```json
{"code":0,"data":{"devices":[{"id":2,"name":"p60-sprint","last_used_at":"2026-10-21T12:00:00Z","expires_at":"2027-01-19T12:00:00Z","status":"active"}]},"msg":""}
```
`DELETE /api/v4/sodayo/device/:id` → `{"code":0,"data":{"revoked":true},"msg":""}`。**撤销后原 Token 下一次请求即 40020**（实测）。

## 4. 设备上传端点（四端点完整协议，Bearer=sdkt_）

**URI 白名单（服务端强制）**：仅 `cloudreve://my/移动端取证` 及其子路径；根目录/`../`/白名单外全 403。

### 4.1 创建上传会话
`PUT /api/v4/file/upload`
```json
// 请求
{"uri":"cloudreve://my/移动端取证/<唯一文件名>.mp4","size":188332892,
 "last_modified":1791800000000,"mime_type":"video/mp4"}
// 响应
{"code":0,"data":{"session_id":"2c2a8028-…","chunk_size":26214400,
 "expires":1792599970,"storage_policy":{…}},"msg":""}
```
- **字段单位（[源码] 源码核实 v2.2 final）**：`size`=字节（int64）；`last_modified`=**Unix 毫秒**时间戳（源码 `time.UnixMilli`，upload.go:84——示例 1791800000000=13 位毫秒）；`expires` 响应字段=**Unix 秒**时间戳（response.go:154，非相对秒数）；`chunk_size`=字节（实测 26214400）。
- `size` 为最终文件精确字节数；`expires`（Unix 秒时间戳）前可续传。
- 失败：`403` 白名单外；`40004` 同名已存在；`40051` 容量/配额不足；`401/40020` 凭据失效。

### 4.2 分片上传
`POST /api/v4/file/upload/{session_id}/{index}` · `Content-Type: application/octet-stream`
- body=原始字节；**实际字节数必须与该分片声明一致**（Content-Length 不符=40013）。
- index 从 0 递增；最后一片为余数（例：188332892B/26214400 → **7 片完整+末片 4,832,092B 共 8 片**；2026-10-21 勘误：此前示例末片数值有误，7×26214400=183,500,800，188332892−183500800=4,832,092）。
- 分片累计达 `size` 即自动合并完成；**完成后再发分片=40011**。
- **会话-设备绑定**：会话由本设备创建方可用；无效/他人会话=403 not bound。

### 4.3 目录列表（设备 Token 可用）
`GET /api/v4/file?uri=cloudreve://my/移动端取证` → 完整 envelope：
```json
{"code":0,"data":{"files":[{"type":0,"id":"Amhx","name":"sprint-big.mp4","size":188332892,
  "created_at":"2026-10-21T12:00:00Z","updated_at":"1970-01-22T01:43:20Z",
  "metadata":{},"path":"cloudreve://my/移动端取证/sprint-big.mp4","owned":true}],"parent":{…}},"msg":""}
```
- `id` = file hash id（证据绑定用）；`type`: 0=文件 1=目录；`size`=字节。

### 4.4 建目录（一般无需——配对时服务端自动建「移动端取证」）
`POST /api/v4/file/create` · `{"uri":"cloudreve://my/移动端取证/<dir>","type":"folder"}`

## 5. URI 编码与 UTF-8 规则（实测）

- JSON body 内 uri 使用**原始 UTF-8 中文**（"移动端取证"不转义）；percent-编码形态会被白名单拒（403）。
- GET query 的 uri 参数按标准 percent-encoding 传输（服务端自动解码后匹配）。
- 文件名建议 `取证-<原名>-<upload_id>.<ext>`，upload_id 稳定生成（见交接单 §5）。

## 6. 撤销/冻结/权限拒绝处理（v2.2 final：按 §1 三维度决策表分流）

**结论（v2.2 final）**：服务端 40020 **不区分**设备撤销（不可逆）/员工冻结（可逆）/OA 权限回收（可逆）——APK 一律进入 **AUTH_SUSPECT**（暂停队列+保留 Token+保留原视频+提示）。**403 类按 §1 决策表分流**：URI 越界等已知 msg=请求错误不触发 AUTH_SUSPECT；未知 403=安全拒绝+诊断。仅用户明确"重新配对"时覆盖旧 Token。

| 触发 | 服务端表现（实测/源码） | APK 统一动作 |
|---|---|---|
| 设备撤销 | 40020 即时（device.status 翻转，不可逆；源码 `device.Status != "active"` 拒绝） | → AUTH_SUSPECT：暂停任务+保留 Token+提示。持续失败时用户发起重新配对（唯一恢复路径） |
| 员工冻结 | 40020 即时（`user.Status != active` 拒绝；源码级确认逐请求实时查、无缓存无吊销副作用——冻结期间 40020 是**请求时拒绝**而非 Token 作废） | → AUTH_SUSPECT：同上。**解冻后同 Token 自动恢复**（前提见下方），无需用户操作 |
| 员工解冻 | 同 Token 恢复可用。**前提条件（rev3 勘误 7）**：①Token 本身未过期（`expires_at` 90 天窗口内）；②设备未被另行撤销（`device.status=="active"`）；③OA 权限仍有效（`clouddrive.basic`，含 ≤5min 缓存窗口）；④APK 在冻结期间**未清除** Token（若已清除则须重新配对） | 无动作（Token 保留则自动恢复）；**AUTH_SUSPECT 恢复触发（v2.2 final）**：①前台期间建议 60s 频率受限鉴权探测（**后台不保证严格 60s**——Doze/WorkManager 调度限制，改为队列引擎唤醒/前台恢复时顺带执行）；②UI"重试"手动入口；③队列网络恢复重试 |
| OA 权限回收 | 40020/401（≤5 分钟缓存延迟后开始拒绝） | → AUTH_SUSPECT：同上。权限恢复后同 Token 恢复（前提同解冻） |
| 越界 URI | 403 limited to ingest folder（隔离实测） | 客户端 bug，修正路径；不清凭据 |
| 无 clouddrive.basic | 403（配对生成阶段即拒，隔离实测） | 提示联系管理员；此阶段尚无 Token 可清 |

## 7. 重试/停止规则（40004/40011/40051）

| 码 | 语义 | 规则 |
|---|---|---|
| 40004 | 同名对象已存在（**不保证是本次内容，原件不被覆盖**） | 进入待确认→按交接单 §5.2 核对流程；不得直接判成功 |
| 40011 | 会话无效（已完成**或**过期/异常，两种语义不可区分） | 同上；若核对未过（文件不存在/size 不符）且会话 `expires` 已过 → **同 upload_id 重建会话**重传（见下方 upload_id 策略） |
| 40051 | 容量不足 | 停止重试，提示用户 |
| 40013 | 分片长度不符 | 重新切片（程序 bug 信号） |
| 40020/401/HTTP 401（sdkt_） | **认证拒绝**（撤销/冻结/权限回收不可区分） | → AUTH_SUSPECT（§1 决策表+§6）；403 另见下行 |
| 403（sdkt_，msg 含 limited to ingest/not bound） | **请求/权限错误**（非 Token 失效） | 修正客户端 bug（路径/会话）；不触发 AUTH_SUSPECT |
| 403（sdkt_，无法识别） | **未知拒绝**（服务端无稳定区分字段） | 安全拒绝：标记失败+保留凭据原件+显示 correlation_id 诊断；不误报成功、不触发 AUTH_SUSPECT |
| 5xx/网络异常 | 传输层（无业务语义） | 指数退避 1s→60s；会话 expires 内续传；连续 10 次挂起队列 |

**upload_id 复用策略（勘误 5）**：40011 过期后**不换 upload_id**——同一任务重试必须用同一确定性 upload_id（UUIDv5(设备ID+本地文件 SHA256+采集日期)），因为：
1. 服务端按 uri（含 upload_id）判定同名——换新 id 会生成第二个不同名文件（重复上传）；
2. 会话过期≠文件名失效——旧会话可能已传部分分片但未合并（服务端无残留文件），新会话从 chunk 0 重传是安全且正确的；
3. 仅在 40004 弱确认流程确认"同名异物"（列表中同 name 但 size 不符）时才生成 `upload_id-2`（递增 ≤3，见交接单 §5.1）。

## 8. 最小权限与 OA 绑定边界

- 设备 Token 仅 ingest 范围（上传+取证目录列表）；不能浏览他人空间/根目录/建白名单外目录（全 403 实测）。
- **OA 处罚记录关联（bind）仅网页 JWT**（设备 Token 调用=401）；服务端三重校验：记录真实性（实时查 OA）+ `panel.punishments` 权限 + 文件 owner。
- APK 不向 OA 写任何数据；不以设备侧 user_id/EID 为权威身份。
