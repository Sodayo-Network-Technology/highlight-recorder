# 项目交接说明（Sodayo 取证回录 APK）

> **交接日期：** 2026-11-01
> **交接性质：** 全权移交——后续开发、联调、维护均由接手开发者负责
> **上一任联系方式：** 经项目负责人（Sodayo 组织 admin：Pigeon2333）转达
> **读完本文约 10 分钟，可立即接手开发；联调细节见 `docs/sodayo/` 内契约与报告。**

---

## 1. 这个项目是什么

MC 服务器（FAPIXEL，网易基岩版）协管**取证工具**：协管巡查时开着 App 循环录屏（即时回放），发现违规一键保存最近 N 秒；保存后**自动上传**到员工本人的 Sodayo SH Drive 取证目录；员工再在 OA 网页把录像关联到处罚记录。核心价值：不错过时机 + 上传自动化（防协管不上传）。

**三方分工**：录制与上传由本 APK 负责；存储由 SH Drive（Cloudreve V4 fork）负责；处罚关联由 OA 网页负责。APK **不写 OA、不碰处罚数据**。

- 基线：fork 自 [owen88ob/highlight-recorder](https://github.com/owen88ob/highlight-recorder)（GPL-3.0，**修改后分发 APK 须向获得者提供源码**——本仓库即合规载体，保持公开/对协管开放即可）
- 本仓库：https://github.com/Sodayo-Network-Technology/highlight-recorder （GitHub Fork 关系保留）
- 生产服务端：`https://sh.sodayo.net:8621`（REV7 v2，2026-10-21 上线；标准 TLS 证书，**禁止关闭校验**）

## 2. 提交历史速览（4 个自有 commit，全部已推送）

| commit | 内容 |
|---|---|
| `918b462` | 云端上传模块全量：Room+WorkManager 队列、Cloudreve 协议客户端、设置页/队列页 UI |
| `6be07fc` | 设备授权：sdkt_ Token（Keystore 加密）、配对 UI、契约初版对齐 |
| `cf930dd` | 契约 v2.2 FINAL 对齐：AUTH_SUSPECT/403 分类/弱确认/确定性 upload_id |
| `1825d22` | 2026-10-22 服务端交接落实：page_size=1000、UNRESOLVED 状态、业务 404 回退 |

上游录制能力（环形缓冲/悬浮球/保存）未做侵入改动，仅 `RecordingService.saveClip()` 加了 3 行挂接 + Manifest 权限。

## 3. 架构与代码地图

```
com.highlightrecorder
├─ (上游保留) buffer/ capture/ overlay/ service/ ui/(原有页面) data/
└─ upload/                          ← 全部新增,与上游解耦
   ├─ UploadCoordinator.kt          # 保存成功挂接点:落盘→流式SHA256→确定性upload_id→入库→入队
   ├─ UploadWorker.kt               # 前台 Worker:哈希→三步协议→错误分流(AUTH_SUSPECT/弱确认/退避)
   ├─ UploadScheduler.kt            # WorkManager 唯一队列(按 clientUploadId)
   ├─ UploadTaskRepository.kt       # 状态机:QUEUED/UPLOADING/RETRY_WAIT/DONE/FAILED/UNRESOLVED
   ├─ UploadSettings.kt             # 独立 DataStore(开关/地址/令牌/仅WiFi/删原件[默认关])
   ├─ BaseUrlValidator.kt           # 仅 https 公网地址(拒私网/环回/内嵌凭据)——勿放宽
   ├─ ErrorPolicy.kt                # 契约错误分流 + WeakConfirm.decide() 四分支决策
   ├─ UploadIds.kt                  # SHA-256 派生确定性 upload_id + -2/-3 换名(≤3)
   ├─ Deps.kt                       # 进程单例(含单并发 Mutex)
   ├─ auth/DeviceTokenStore.kt      # sdkt_ Token: AndroidKeyStore AES-GCM 加密落盘
   ├─ auth/DeviceAuth.kt            # 配对客户端(10位码兑换) + SodayoDeviceCredentialProvider
   ├─ db/UploadTaskDb.kt            # Room 实体/DAO(队列唯一事实来源,进程死重启可重建)
   └─ protocol/                     # CloudreveV4Uploader.kt(纯JVM可MockWebServer单测)
                                    # + CloudreveProtocol.kt(端点集中,契约若有变只改这)
```

UI 接线：主页加「上传队列」入口（`UploadScreen`），设置页加「云端上传」配置区+配对入口（`SettingsScreen`/`UploadViewModel`）。

## 4. 契约要点速记（唯一规范：`docs/sodayo/API契约-v2.2-FINAL.md`）

**务必先通读契约全文**。最容易踩的红线：

1. **HTTP 401/业务码 40020 → AUTH_SUSPECT**：暂停任务 + **保留 Token**（员工冻结可逆，清了就得重新配对）+ 保留原件。清 Token 唯一入口=用户点「解除本机配对」。
2. **403 按 msg 分类**：`limited to ingest`/`not bound`=客户端 bug（修代码，不动凭据）；未知 403=安全拒绝+correlation_id 诊断。
3. **40004/40011 → 弱确认**：查列表（**显式 page_size=1000**）核对 name+size；相符才结案且**永不删原件**；列表可能截断（≥1000 条）→ UNRESOLVED 待人工，**不自动重传**；无候选且列表完整 → 同 upload_id 重建会话重传。
4. **upload_id 确定性**（设备ID+SHA256+日期）：同任务重试绝不换 id（换了=重复文件）；同名异物才 -2/-3 递增 ≤3。
5. **uri 编码**：JSON body 内原始 UTF-8 中文；GET query 参数 percent-encode。
6. **建会话 PUT**、分片 POST（末片余数自动完成，无显式 complete）、`last_modified` Unix **毫秒**、无服务端会话查询端点（重启后=新建会话重传，勿复用旧 session_id）。
7. **双层判定**：先解析 JSON envelope 业务码，无业务码再看 HTTP 状态；不假定任何端点 HTTP 恒定。

## 5. 构建环境

```bash
# 要求:JDK 17+（本机用 21）;Android SDK platform 35 / build-tools 35
# ⚠️ 本机 gradlew wrapper 因 SSL 证书问题不可用,直接用本地 Gradle 发行版:
cd highlight-recorder
JAVA_HOME="D:\Program Files\Java\jdk-21" D:\tools\gradle-8.10.2\bin\gradle.bat :app:testDebugUnitTest   # 52 测试
JAVA_HOME="D:\Program Files\Java\jdk-21" D:\tools\gradle-8.10.2\bin\gradle.bat :app:assembleDebug       # 出 APK
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

- `local.properties` 指向 `C:/Users/HP/AppData/Local/Android/Sdk`（**不入库**，新机器按自己 SDK 路径重写）
- 新机器若 wrapper 可用（无 SSL 拦截）可直接 `./gradlew`，Gradle 8.10.2 / AGP 8.7.3 / Kotlin 2.0.21
- 真机：华为 P60（LNA-AL00，Android 12），USB 调试已授权；装机测试历史截图见 reports/04

## 6. 当前状态与待办

**已完成**：全部客户端开发（52 单测全绿）；P60 真机 A01–A06 REAL PASS（录制/保存/队列/重试/状态 UI）；契约 v2.2 + 10-22 服务端交接全量对齐。

**待办（按优先级）**：

| # | 事项 | 依赖 |
|---|---|---|
| 1 | P60 真机联调序列 1–8（配对→小/大文件上传→断网→撤销→弱确认） | 仅需员工在 Drive 网页生成 10 位配对码（**直接 clouddrive.basic 授权账号**；组继承方案 B 未上线会被拒=已知限制） |
| 2 | A04（旋转/锁屏/采集终止）、A08（重启队列恢复）真机补测 | 无依赖 |
| 3 | §E 9–11 evidence-space 相关 | REV8 上线（候选已冻结待批） |
| 4 | OA 网页关联取证验收 | OA PR #68 合并部署 |
| 5 | （可选）正式版签名 keystore 生成与发布包 | 项目负责人拍板分发方式 |

**联调注意**：失败用例回传格式=HTTP 状态+JSON code+correlation_id+脱敏日志；测试 APK 的 SHA 随报告附上。

## 7. 文档索引（本仓库 `docs/sodayo/`）

| 文件 | 内容 |
|---|---|
| `API契约-v2.2-FINAL.md` | **唯一规范来源**（端点/字段/错误码/三维度决策表），冲突时以它为准 |
| `服务端联调交接-20261022.md` | 服务端最新答复（四项澄清、分页实测、测试序列 §E） |
| `reports/01–06` | 项目全程决策与证据链：可行性→动工→开发状态→真机报告→符合性矩阵→回执 |
| （仓库外）`E:\FAPIXEL小游戏中国版\应用软件\SODAYO-ANDROID-APK-HANDOFF-20261021-REV6` | Drive 侧完整交接包（适配指南/恢复检查单/勘误史），关键内容已收入本目录 |

## 8. 红线（违反即破坏取证可信度）

- AUTH_SUSPECT / 弱确认 / 失败 / UNRESOLVED 路径**绝不自动删除本地原视频**；删除仅一处：直接成功 + 用户显式开启 `deleteAfterUpload`（默认关）。
- APK 不写 OA、不传 user_id/EID（身份由 Token 服务端推导）。
- Token 只进 `Authorization` 头，不出 URL/日志；服务器地址校验（https+公网）勿放宽。
- 端点改动只动 `protocol/CloudreveProtocol.kt`，协议行为改动必须先改/补单测（`app/src/test/.../upload/`，MockWebServer 全链路）。

祝顺利。—— 上一任开发者（ZCode 会话代理，经 Pigeon2333 转交）
