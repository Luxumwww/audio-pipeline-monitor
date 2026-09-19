package com.audioprobe.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.audioprobe.audio.AudioFormats
import com.audioprobe.audio.AudioSnapshot
import com.audioprobe.audio.BluetoothInfo
import com.audioprobe.audio.ChainLink
import com.audioprobe.audio.ChainNote
import com.audioprobe.audio.FlingerOutputThread
import com.audioprobe.priv.PrivilegeState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProbeScreen(viewModel: ProbeViewModel) {
    val privilege by viewModel.privilege.collectAsStateWithLifecycle()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val latency by viewModel.latencyMs.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("音频链路监视器") },
                actions = {
                    IconButton(onClick = { viewModel.toggleRunning() }) {
                        Text(if (running) "⏸" else "▶", fontSize = 18.sp)
                    }
                    IconButton(onClick = { viewModel.refreshNow() }) {
                        Text("⟳", fontSize = 18.sp)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                PrivilegeCard(
                    state = privilege,
                    onRequest = viewModel::requestPermission,
                    onReconnect = viewModel::reconnect,
                )
            }

            error?.let { message ->
                item { ErrorCard(message) }
            }

            val snap = snapshot
            if (snap == null) {
                item { HintCard("等待第一次采样…") }
            } else {
                if (snap.links.isEmpty()) {
                    item { IdleCard(snap) }
                } else {
                    item { SectionHeader("正在播放 · ${snap.links.size} 条链路") }
                    items(snap.links, key = { it.trackId }) { link -> ChainCard(link) }
                }

                if (snap.threads.isNotEmpty()) {
                    item { SectionHeader("全部输出线程 (${snap.threads.size})") }
                    items(snap.threads, key = { it.handle }) { thread -> ThreadCard(thread) }
                }

                if (snap.bluetooth.devices.isNotEmpty()) {
                    item { SectionHeader("蓝牙 A2DP") }
                    item { BluetoothCard(snap.bluetooth) }
                }

                item { SectionHeader("诊断") }
                item { DiagnosticsCard(snap, latency) }
            }
        }
    }
}

// ---------------------------------------------------------------- privilege banner

@Composable
private fun PrivilegeCard(
    state: PrivilegeState,
    onRequest: () -> Unit,
    onReconnect: () -> Unit,
) {
    val accent = when (state) {
        is PrivilegeState.Ready -> AccentGood
        is PrivilegeState.Failed -> AccentBad
        PrivilegeState.PermissionRequired -> AccentWarn
        else -> AccentInfo
    }
    val title = when (state) {
        PrivilegeState.BackendMissing -> "未检测到 Shizuku"
        PrivilegeState.BackendNotRunning -> "Shizuku 未运行"
        PrivilegeState.PermissionRequired -> "需要 Shizuku 授权"
        PrivilegeState.Connecting -> "正在启动特权服务…"
        is PrivilegeState.Ready -> "特权已就绪 · uid ${state.uid} · Shizuku API ${state.version}"
        is PrivilegeState.Failed -> "特权服务出错"
    }
    val detail = when (state) {
        PrivilegeState.BackendMissing ->
            "读取音频链路必须拥有 android.permission.DUMP，普通应用没有。" +
                "请先安装 Shizuku 并按无线调试方式启动。"
        PrivilegeState.BackendNotRunning ->
            "Shizuku 已安装但没有运行。打开 Shizuku 应用，按「通过无线调试启动」操作后回到这里。"
        PrivilegeState.PermissionRequired ->
            "在 Shizuku 中允许本应用使用 API，然后返回。"
        PrivilegeState.Connecting ->
            "正在让 Shizuku 拉起以 shell 身份运行的命令服务。"
        is PrivilegeState.Ready -> "命令以 uid ${state.uid} 执行，dumpsys 已可读取。"
        is PrivilegeState.Failed -> state.message
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(accent)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (state) {
                PrivilegeState.PermissionRequired -> {
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onRequest) { Text("请求授权") }
                }
                PrivilegeState.BackendNotRunning, PrivilegeState.BackendMissing -> {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onReconnect) { Text("重新检测") }
                    }
                }
                is PrivilegeState.Failed -> {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onReconnect) { Text("重试") }
                }
                else -> Unit
            }
        }
    }
}

// ---------------------------------------------------------------- idle / hints

@Composable
private fun IdleCard(snapshot: AudioSnapshot) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp)) {
            Text("当前没有正在播放的音频", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                "播放任意音频后，这里会显示它从应用输出到硬件的完整链路。" +
                    "已识别 ${snapshot.threads.size} 个输出线程、${snapshot.players.size} 个播放器配置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HintCard(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Text(
            text,
            Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(AccentBad)
                Spacer(Modifier.width(8.dp))
                Text("采样失败", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(6.dp))
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------------------------------------------------------- the chain card

@Composable
private fun ChainCard(link: ChainLink) {
    val thread = link.thread
    val devices = thread.devices.map(AudioFormats::deviceLabel)
    val codec = link.bluetooth?.codec

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(AccentGood)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        link.appLabel,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildList {
                            link.packageName?.let(::add)
                            add("uid ${link.uid}")
                            add("pid ${link.pid}")
                            add("session ${link.session}")
                        }.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            StageRow(
                index = "①",
                title = "应用输出 (AudioTrack)",
                primary = listOf(
                    AudioFormats.rateLabel(link.sourceSampleRate),
                    AudioFormats.label(link.sourceFormatName),
                    AudioFormats.channelLabel(link.sourceChannelMask),
                ).joinToString(" · "),
                secondary = "${AudioFormats.usageLabel(link.usage)} · " +
                    AudioFormats.contentTypeLabel(link.contentType) +
                    " · " + AudioFormats.streamTypeLabel(link.streamType),
            )

            Connector(hopLabel(link.sourceSampleRate, thread.sampleRate))

            StageRow(
                index = "②",
                title = "混音线程 ${thread.name}",
                primary = listOf(
                    AudioFormats.rateLabel(thread.sampleRate),
                    "处理格式 " + AudioFormats.label(thread.processingFormatName),
                ).joinToString(" · "),
                secondary = buildList {
                    if (thread.flagNames.isNotEmpty()) {
                        add(thread.flagNames.joinToString("/", transform = AudioFormats::outputFlagLabel))
                    }
                    add("HAL 帧 ${thread.halFrameCount}")
                    if (thread.standby) add("standby")
                }.joinToString(" · "),
            )

            Connector(if (codec != null) "→ ${codec.name} 编码器" else null)

            StageRow(
                index = "③",
                title = "输出设备",
                primary = if (devices.isEmpty()) "未知" else devices.joinToString(" + "),
                secondary = thread.devices.joinToString(" | "),
            )

            Connector(null)

            if (codec != null) {
                StageRow(
                    index = "④",
                    title = "蓝牙实际输出 · ${codec.name}",
                    primary = listOfNotNull(
                        codec.sampleRate?.let(AudioFormats::rateLabel) ?: "采样率未知",
                        codec.bitsPerSample?.let { "编码输入 $it bit" },
                        codec.channelMode,
                    ).joinToString(" · "),
                    // Keep the PCM numbers visible: they are the last lossless stage and
                    // the reason a rate conversion may have happened before encoding.
                    secondary = "A2DP 压缩传输；AudioFlinger 侧 HAL 为 " +
                        "${AudioFormats.rateLabel(thread.sampleRate)} / " +
                        AudioFormats.label(thread.halFormatName),
                )
            } else {
                StageRow(
                    index = "④",
                    title = "硬件实际输出 (HAL)",
                    primary = listOf(
                        AudioFormats.rateLabel(thread.sampleRate),
                        AudioFormats.label(thread.halFormatName),
                    ).joinToString(" · "),
                    secondary = "HAL 格式 ${thread.halFormatValue.toString(16)} · " +
                        AudioFormats.channelLabel(thread.channelMask),
                )
            }

            if (link.notes.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    link.notes.forEach { NoteRow(it) }
                }
            }
        }
    }
}

@Composable
private fun hopLabel(from: Int, to: Int): String? {
    if (from <= 0 || to <= 0 || from == to) return null
    return "重采样 ${AudioFormats.rateLabel(from)} → ${AudioFormats.rateLabel(to)}"
}

@Composable
private fun StageRow(index: String, title: String, primary: String, secondary: String?) {
    Row {
        Text(
            index,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(24.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                primary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (!secondary.isNullOrBlank()) {
                Text(
                    secondary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The little vertical "↓" between stages, optionally annotated with what happened. */
@Composable
private fun Connector(label: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 24.dp)) {
        Text("↓", color = MaterialTheme.colorScheme.outline, fontSize = 14.sp)
        if (label != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                fontSize = 11.sp,
                color = AccentWarn,
                modifier = Modifier
                    .background(AccentWarn.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun NoteRow(note: ChainNote) {
    val color = when (note.level) {
        ChainNote.Level.GOOD -> AccentGood
        ChainNote.Level.RESAMPLED -> AccentWarn
        ChainNote.Level.CONVERTED -> AccentWarn
        ChainNote.Level.INFO -> AccentInfo
        ChainNote.Level.WARN -> AccentBad
    }
    Row(verticalAlignment = Alignment.Top) {
        Dot(color)
        Spacer(Modifier.width(8.dp))
        Text(
            note.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ---------------------------------------------------------------- secondary cards

@Composable
private fun ThreadCard(thread: FlingerOutputThread) {
    val devices = thread.devices.map(AudioFormats::deviceLabel)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(if (thread.activeTracks.isNotEmpty()) AccentGood else MaterialTheme.colorScheme.outline)
                Spacer(Modifier.width(8.dp))
                Text(thread.name, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Text(
                    "${thread.declaredActiveTrackCount}/${thread.declaredTrackCount} 活动",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                listOf(
                    AudioFormats.rateLabel(thread.sampleRate),
                    AudioFormats.label(thread.halFormatName),
                    if (devices.isEmpty()) "未知设备" else devices.joinToString("+"),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
            val flags = thread.flagNames.joinToString("/", transform = AudioFormats::outputFlagLabel)
            if (flags.isNotBlank()) {
                Text(
                    flags,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BluetoothCard(info: BluetoothInfo) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            info.devices.forEach { device ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(if (device.connected && device.playing) AccentGood else MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.width(8.dp))
                        Text(device.address, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.weight(1f))
                        Text(
                            buildString {
                                append(if (device.connected) "已连接" else "未连接")
                                if (device.playing) append(" · 播放中")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    device.codec?.let { codec ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            listOfNotNull(
                                codec.name,
                                codec.sampleRate?.let(AudioFormats::rateLabel),
                                codec.bitsPerSample?.let { "$it bit" },
                                codec.channelMode,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (!device.connected) {
                            Text(
                                "链路已断开，这是上一次协商结果",
                                style = MaterialTheme.typography.labelSmall,
                                color = AccentWarn,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsCard(snapshot: AudioSnapshot, latencyMs: Long) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "采样耗时 ${latencyMs} ms · 输出线程 ${snapshot.threads.size} · " +
                    "播放器配置 ${snapshot.players.size} · " +
                    "更新于 ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(snapshot.timestampMs))}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (snapshot.errors.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                snapshot.errors.forEach { ErrorLine(it) }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起原始 dump" else "查看原始 dump")
                }
                TextButton(onClick = {
                    copyToClipboard(context, snapshot.rawFlinger)
                }) { Text("复制") }
            }

            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                        .padding(8.dp),
                ) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Column(Modifier.horizontalScroll(rememberScrollState())) {
                            Text(
                                snapshot.rawFlinger,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                maxLines = Int.MAX_VALUE,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorLine(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Dot(AccentWarn)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelSmall)
    }
}

// ---------------------------------------------------------------- primitives

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp, start = 2.dp),
    )
}

@Composable
private fun Dot(color: Color) {
    Box(
        Modifier
            .size(8.dp)
            .background(color, RoundedCornerShape(4.dp))
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText("audio_flinger", text))
    Toast.makeText(context, "已复制 audio_flinger dump", Toast.LENGTH_SHORT).show()
}
