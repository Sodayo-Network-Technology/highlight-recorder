package com.highlightrecorder.ui

import android.media.MediaCodecList
import android.media.MediaFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.highlightrecorder.data.AudioSource
import com.highlightrecorder.data.needsMic
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(viewModel: MainViewModel, uploadViewModel: UploadViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsState()
    val upload by uploadViewModel.settings.collectAsState()
    val pair by uploadViewModel.pairState.collectAsState()
    var pairCode by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }

    val hevcSupported = remember {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any {
                it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
            }
        }
    }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.updateSettings { it.copy(audioSource = AudioSource.MIC) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall)

        // ---- 回退时长 ----
        Section("回退时长: ${settings.rewindSeconds} 秒") {
            Slider(
                value = settings.rewindSeconds.toFloat(),
                onValueChange = { v ->
                    viewModel.updateSettings { it.copy(rewindSeconds = (v / 15).roundToInt() * 15) }
                },
                valueRange = 15f..300f,
            )
            val memMb = settings.videoBitrateBps / 8L * (settings.rewindSeconds + 2) / 1_000_000
            val valveMb = minOf(768, maxOf(300, memMb * 3 / 2))
            Text("缓冲内存预估: 约 ${memMb} MB(码率 × 时长;激烈场景码率冲高,超过 ${valveMb}MB 安全阀将自动缩短实际缓冲时长)", style = MaterialTheme.typography.bodySmall)
        }

        // ---- 分辨率 ----
        Section("分辨率") {
            ChipRow(
                options = listOf(
                    0 to "跟随屏幕", 720 to "720p", 1080 to "1080p",
                    1440 to "2K", 2160 to "4K",
                ),
                selected = settings.resolutionShortEdge,
            ) { v -> viewModel.updateSettings { it.copy(resolutionShortEdge = v) } }
            Text(
                "游戏场景建议 720p/1080p;2K/4K 与「跟随屏幕」开销大,需配合高码率," +
                    "设备不支持时会自动降到编码器上限",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // ---- 帧率 ----
        Section("帧率") {
            ChipRow(
                options = listOf(
                    30 to "30 fps", 60 to "60 fps", 90 to "90 fps", 120 to "120 fps",
                ),
                selected = settings.frameRate,
            ) { v -> viewModel.updateSettings { it.copy(frameRate = v) } }
            Text(
                "90/120 fps 需要屏幕高刷与编码器性能支持,实际帧率以设备能力为准;" +
                    "高帧率建议同步提高码率",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // ---- 码率 ----
        Section("码率: %.1f Mbps".format(settings.videoBitrateBps / 1_000_000f)) {
            ChipRow(
                options = listOf(4_000_000 to "低", 8_000_000 to "中", 16_000_000 to "高", -1 to "自定义"),
                selected = if (settings.videoBitrateBps in listOf(4_000_000, 8_000_000, 16_000_000)) {
                    settings.videoBitrateBps
                } else {
                    -1
                },
            ) { v ->
                if (v > 0) viewModel.updateSettings { it.copy(videoBitrateBps = v) }
                else viewModel.updateSettings { it.copy(videoBitrateBps = 12_000_000) }
            }
            if (settings.videoBitrateBps !in listOf(4_000_000, 8_000_000, 16_000_000)) {
                Slider(
                    value = settings.videoBitrateBps / 1_000_000f,
                    onValueChange = { v ->
                        viewModel.updateSettings { it.copy(videoBitrateBps = (v * 1_000_000).toInt()) }
                    },
                    valueRange = 1f..60f,
                )
            }
            val perHourMb = settings.videoBitrateBps / 8L * 3600 / 1_000_000
            Text("每小时素材约 ${perHourMb} MB(仅缓冲最近片段落盘)", style = MaterialTheme.typography.bodySmall)
        }

        // ---- 编码器 ----
        Section("编码器") {
            ChipRow(
                options = buildList {
                    add("video/avc" to "H.264")
                    if (hevcSupported) add("video/hevc" to "H.265 (HEVC)")
                },
                selected = settings.videoMime,
            ) { v -> viewModel.updateSettings { it.copy(videoMime = v) } }
            if (!hevcSupported) {
                Text("本机不支持 H.265 编码", style = MaterialTheme.typography.bodySmall)
            }
        }

        // ---- 音频来源 ----
        Section("音频来源") {
            ChipRow(
                options = listOf(
                    AudioSource.INTERNAL to "内录",
                    AudioSource.MIC to "麦克风",
                    AudioSource.INTERNAL_AND_MIC to "内录+麦克风",
                    AudioSource.MUTE to "静音",
                ),
                selected = settings.audioSource,
            ) { v ->
                when {
                    v.needsMic() && !viewModel.permissions.value.mic ->
                        micLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    else -> viewModel.updateSettings { it.copy(audioSource = v) }
                }
            }
            Text("内录仅 Android 10+ 且目标 App 允许时有效;混音时内录不可用则自动退化为纯麦克风", style = MaterialTheme.typography.bodySmall)
            if (android.os.Build.VERSION.SDK_INT < 29 &&
                (settings.audioSource == AudioSource.INTERNAL || settings.audioSource == AudioSource.INTERNAL_AND_MIC)
            ) {
                Text(
                    "⚠ 本机系统低于 Android 10,不支持内录,录制将无声;请改用「麦克风」",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // ---- 悬浮窗 ----
        Section("悬浮窗外观") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("彻底隐藏悬浮球")
                    Text(
                        "隐藏后仍正常录制,从本页恢复显示",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.overlayHidden,
                    onCheckedChange = { on -> viewModel.updateSettings { it.copy(overlayHidden = on) } },
                )
            }
            Text("透明度 %.0f%%".format(settings.overlayAlpha * 100), style = MaterialTheme.typography.bodySmall)
            Slider(
                value = settings.overlayAlpha,
                onValueChange = { v -> viewModel.updateSettings { it.copy(overlayAlpha = v) } },
                valueRange = 0f..1f,
            )
            if (settings.overlayAlpha < 0.05f) {
                Text(
                    "完全透明时悬浮球仍在原位可点,建议配合记住位置或改用彻底隐藏",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text("大小 %.2fx".format(settings.overlayScale), style = MaterialTheme.typography.bodySmall)
            Slider(
                value = settings.overlayScale,
                onValueChange = { v -> viewModel.updateSettings { it.copy(overlayScale = v) } },
                valueRange = 0.75f..1.5f,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("贴边时半隐藏", modifier = Modifier.weight(1f))
                Switch(
                    checked = settings.overlayEdgeHide,
                    onCheckedChange = { on -> viewModel.updateSettings { it.copy(overlayEdgeHide = on) } },
                )
            }
        }

        // ---- 回收站 ----
        Section("回收站: ${settings.trashAutoDeleteDays} 天后自动彻底删除") {
            Slider(
                value = settings.trashAutoDeleteDays.toFloat(),
                onValueChange = { v ->
                    viewModel.updateSettings { it.copy(trashAutoDeleteDays = v.roundToInt().coerceIn(1, 90)) }
                },
                valueRange = 1f..90f,
            )
            Text("视频库删除的视频会先进入回收站,到期自动清理", style = MaterialTheme.typography.bodySmall)
        }

        // ---- 云端上传 ----
        Section("云端上传(Sodayo SH Drive)") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("保存后自动上传")
                    Text(
                        "保存成功即入队,断网自动重试;状态见「上传队列」",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = upload.enabled,
                    onCheckedChange = { on -> uploadViewModel.updateSettings { it.copy(enabled = on) } },
                )
            }
            OutlinedTextField(
                value = upload.serverUrl,
                onValueChange = { v -> uploadViewModel.updateSettings { it.copy(serverUrl = v) } },
                label = { Text("服务器地址") },
                placeholder = { Text("https://drive.example.com") },
                singleLine = true,
                isError = upload.serverUrl.isNotEmpty() && !upload.serverUrlValid,
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri),
                supportingText = {
                    if (upload.serverUrl.isNotEmpty() && !upload.serverUrlValid) {
                        Text("仅允许 https 公网地址,拒绝内网/本机地址")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            // ---- 设备配对(交接单 §1.1):网页端发起配对 → 10 位码输入此处 ----
            Text("设备配对", fontWeight = FontWeight.Bold)
            Text(
                if (pair.paired) {
                    val expiry = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                        .format(java.util.Date(pair.expiresAtMs))
                    "已配对 设备#${pair.deviceId} · 凭据有效期至 $expiry"
                } else {
                    "未配对。请先在 Drive 网页端发起配对,把 10 位配对码输入此处(5 分钟内有效)。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (pair.paired) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!pair.paired) {
                OutlinedTextField(
                    value = pairCode,
                    onValueChange = { v -> pairCode = v.trim().take(12) },
                    label = { Text("配对码") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { uploadViewModel.pair(pairCode) },
                        enabled = !pair.busy && pairCode.isNotBlank(),
                    ) {
                        Text(if (pair.busy) "配对中…" else "立即配对")
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.TextButton(
                        onClick = { uploadViewModel.unpair() },
                    ) { Text("解除本机配对") }
                }
                Text(
                    "注:服务端撤销请到 Drive 网页端「设备管理」操作",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            pair.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = upload.token,
                onValueChange = { v -> uploadViewModel.updateSettings { it.copy(token = v) } },
                label = { Text("访问凭据(开发期备用,配对后可留空)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = upload.targetFolder,
                onValueChange = { v -> uploadViewModel.updateSettings { it.copy(targetFolder = v) } },
                label = { Text("上传目标目录") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("仅 Wi-Fi 上传", modifier = Modifier.weight(1f))
                Switch(
                    checked = upload.wifiOnly,
                    onCheckedChange = { on -> uploadViewModel.updateSettings { it.copy(wifiOnly = on) } },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("上传成功后删除本地原件")
                    Text("默认保留,双保险", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = upload.deleteAfterUpload,
                    onCheckedChange = { on -> uploadViewModel.updateSettings { it.copy(deleteAfterUpload = on) } },
                )
            }
            Text(
                "说明:设备绑定授权由 Drive 侧开发中,当前使用测试实例令牌;" +
                    "上传不等于取证关联,仍需在 OA 处罚记录页手动关联。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---- 赞助 ----
        SponsorSection()

        Spacer(Modifier.height(4.dp))
        androidx.compose.material3.OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("返回")
        }

        // 版本号
        val context = LocalContext.current
        val versionName = remember {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "unknown"
        }
        Text(
            "高光回录 v$versionName · by owen88ob",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** 爱发电赞助档位,点击跳转对应下单页。 */
private data class SponsorTier(
    val emoji: String,
    val name: String,
    val price: String,
    val desc: String,
    val url: String,
)

private val sponsorTiers = listOf(
    SponsorTier(
        "☕", "请杯咖啡", "￥5.00 /月", "感谢您的支持!",
        "https://ifdian.net/order/create?plan_id=46e7342a93ba11f1ab6b5254001e7c00&product_type=0&remark=&affiliate_code=",
    ),
    SponsorTier(
        "🍔", "吃顿好的", "￥15.00 /月", "!!!感谢您的支持~~~",
        "https://ifdian.net/order/create?plan_id=78c5003a93ba11f1bb9f52540025c377&product_type=0&remark=&affiliate_code=",
    ),
    SponsorTier(
        "🚀", "强力发电", "￥50.00 /月", "感谢支持!有任何问题请联系 18022867417@163.com",
        "https://ifdian.net/order/create?plan_id=963e89b093ba11f19e7952540025c377&product_type=0&remark=&affiliate_code=",
    ),
)

@Composable
private fun SponsorSection() {
    val context = LocalContext.current
    Section("赞助作者 owen88ob") {
        Text(
            "高光回录是 GPL-3.0 开源软件,完全免费。如果它帮你留下了精彩瞬间,欢迎请作者喝杯咖啡 ☕",
            style = MaterialTheme.typography.bodySmall,
        )
        sponsorTiers.forEach { tier ->
            Card(
                onClick = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(tier.url),
                            ),
                        )
                    }
                },
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tier.emoji, style = MaterialTheme.typography.titleLarge)
                    Column(modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp)) {
                        Text(tier.name, fontWeight = FontWeight.Bold)
                        Text(tier.desc, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        tier.price,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun <T> ChipRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}
