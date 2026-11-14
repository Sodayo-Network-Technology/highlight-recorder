# Android 取证客户端 · UAT 构建与安装（tag android-rc-uat-v1）

## 构建（在 highlight-recorder 仓库根目录）

```bash
git clone -b android-rc-uat-v1 https://github.com/Sodayo-Network-Technology/highlight-recorder.git
cd highlight-recorder

# 签名凭据经环境注入（绝不入仓；未设置 → 产出未签名 APK）
# 首次生成（密钥存仓外，示例路径）：
#   keytool -genkeypair -keystore ~/android-uat.keystore -alias uat -keyalg RSA -keysize 2048 -validity 3650
export RC_KEYSTORE_PATH=/path/to/android-uat.keystore
export RC_STORE_PASSWORD=<该密钥的口令>
export RC_KEY_ALIAS=uat

./gradlew assembleRc          # 产物：app/build/outputs/apk/rc/app-rc.apk
```

## 安装与使用（员工日常）

1. 安装 APK → 完成 onboarding 授权（悬浮窗/通知）
2. 设置 → 云端上传：**服务器地址**填正式域名（`https://…`，仅 https+公网，客户端强制校验）→ 主管在 Drive 网页端发起配对 → 输入 10 位码 → 立即配对
3. 主页「开始循环录制」→ 违规发生时点悬浮球保存 → 自动上传（断网自动重试，见「上传队列」）
4. 上传后到 OA 网页把录像关联到处罚记录（App 不写 OA）

## 验收状态（如实）
- 模拟器全链已通（配对 UI→真录制→sdkt_ 上传→OA 关联）
- **正式 HTTPS 域名 + 真机的 App UI 配对验收待做**（协议级验证不替代 App 级验收）
- 正式发布签名属 Owner 决策（当前 rc 构建用 UAT 专用 env 凭据）
