# 音频链路监视器 (Audio Pipeline Monitor)

监视 Android **当前正在播放的音频**走过了哪条链路：应用交出的是什么采样率/位深，
AudioFlinger 把它重采样成了什么，最后从硬件（扬声器 / USB DAC / 蓝牙耳机）出去的实际
格式是什么。USB 输出与蓝牙输出都已支持。

> **作者**：[Luxumwww](https://github.com/Luxumwww)
> **实现**：与 AI 编码代理 **[DeepSeek Harness](https://github.com/deepseek-ai)** 结对完成
> —— 全部代码、真机 dump 采集与解析、30 个单元测试，以及第 6 节里那批「只有真机才暴露」
> 的问题定位都由它落地；需求、取舍与真机验收由作者完成。

> 已在真机上验证：
> - **vivo 机型（MediaTek）/ Android 17 (SDK 37)** — 验证了蓝牙链路
> - **vivo 机型（MediaTek）/ Android 16 (SDK 36)** — 两台设备的 dump 格式有差异，都已适配

---

## 1. 它长什么样

以 Salt Player 通过 LDAC 蓝牙耳机播放 44.1 kHz 文件为例：

```
com.salt.music · session 857

① 应用输出 (AudioTrack)          44.1 kHz · 32 bit 浮点 PCM · 立体声
                                  MEDIA 媒体 · UNKNOWN · STREAM_MUSIC 音乐
        ↓ 重采样 44.1 kHz → 48 kHz
② 混音线程 AudioOut_15           48 kHz · 处理格式 32 bit 整数 PCM
                                  深度缓冲 · HAL 帧 2048
        ↓ → LDAC 编码器
③ 输出设备                       蓝牙音频 (A2DP)   AUDIO_DEVICE_OUT_BLUETOOTH_A2DP
        ↓
④ 蓝牙实际输出 · LDAC             96 kHz · 编码输入 32 bit · STEREO

   ● AudioFlinger 重采样 44.1 kHz → 48 kHz
   ● 送入 LDAC 编码器前再次转换 48 kHz → 96 kHz
   ● 蓝牙是压缩传输，最终链路为 LDAC 编码，不是无损 PCM
```

USB DAC 时 ④ 变成 `硬件实际输出 (HAL)`，直接给出 DAC 实际跑的采样率与位深。

---

## 2. 为什么需要 Shizuku

这些数据全部来自 `dumpsys`，而 `dumpsys media.audio_flinger` 受
`android.permission.DUMP` 保护，**只有 shell(2000) / root 持有**，普通应用进程读不到。

Shizuku 通过无线调试（或 root）拿到 adb shell 身份，再把这份身份借给应用。
本项目用的是 Shizuku 的 **user service** 机制：

- `Shizuku.newProcess()` 在 Shizuku API 13 里已经被改成 `private`，官方计划在 API 14 移除，
  所以没有走这条路；
- 改用 `Shizuku.bindUserService()`：Shizuku 会在自己的 `app_process` 里以 **uid 2000**
  启动我们 APK 中的 `ProbeService`，由它执行 `dumpsys`。

应用界面上「特权已就绪 · uid 2000」就是这个身份的确认。

### 使用前的准备

1. 安装 [Shizuku](https://github.com/RikkaApps/Shizuku) 并启动它
   （无线调试方式：`adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh`）。
2. 打开本应用 → 弹出授权框时选「始终允许」。
3. 播放任意音频，界面即开始显示链路。

---

## 3. 数据来源与解析

| 来源 | 取什么 |
|---|---|
| `dumpsys media.audio_flinger` | 每个输出线程的 `Sample rate` / `HAL format` / `Processing format` / `Output devices`，以及每个线程下的 live track 表（`Format`、`SRate`、`Chn mask`、`Session`、`BitPerfect`） |
| `dumpsys audio` | `AudioPlaybackConfiguration` 的 session → package 映射（AudioFlinger 只知道 uid/pid） |
| `dumpsys bluetooth_manager --print` | `mCodecConfig`：当前协商的蓝牙编码器、采样率、编码输入位深、声道模式 |

合并逻辑在 `SnapshotBuilder`：一条 track 找到它所在的输出线程 → 线程找到输出设备 →
如果设备是蓝牙，再挂上编码器这一级。

**关于位深**：蓝牙是压缩链路，`mBitsPerSample` 指的是编码器输入位深，不是无损 PCM 位深，
界面上标注为「编码输入 xx bit」并给出说明。USB / 扬声器则是真正的 PCM 位深。

---

## 4. 构建

```powershell
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.7.6-hotspot"   # 需要 JDK 17+
$env:ANDROID_HOME="D:\Android\Sdk"
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`
（已复制一份到 `dist/audio-pipeline-monitor-debug.apk`）

`local.properties` 里已指向 `D:/Android/Sdk`。

---

## 5. 测试

**30 个单元测试**，跑在真机抓下来的 dump 上，不依赖模拟数据。

> **仓库里不含测试样本。** 原始 `dumpsys` 输出中，除了被 ROM 脱敏的 A2DP MAC 之外，
> 其它位置仍带有真实蓝牙 MAC、设备型号、build 号和完整应用列表，不适合公开。
> 请按 `app/src/test/java/com/audioprobe/audio/Fixtures.kt` 的说明，把你自己抓的 dump
> 放进 `app/src/test/resources/fixtures/`（该目录已被 `.gitignore` 忽略）。
> **缺样本时受影响的测试会跳过而不是失败** —— 30 个用例里 9 个无需样本，其余 21 个 skip，
> 所以 fresh clone 依然是绿色的。

| 测试 | 覆盖 |
|---|---|
| `AudioFlingerParserTest` | Android 16 / 17 两种列布局、live track、Local log 不得被当成实时轨道 |
| `AudioServiceParserTest` | players 列表、piid → 包名回填 |
| `BluetoothParserTest` | 未连接时保留的上次协商结果、活跃设备带 `(Active)` 后缀 |
| `DumpTrimmerTest` | **裁剪后的 dump 必须解析出与原始 dump 完全一致的结果** |
| `SnapshotBuilderTest` | USB 链路（合成 dump，无实体 DAC）、设备名映射、解析自检 |
| `RegexPortabilityTest` | 禁止正则里出现裸花括号（见下） |

---

## 6. 开发中踩到并已修掉的坑

这些都是真机上才暴露出来的问题，记录在此以免复发。

1. **命令 marker 不能用 shell 元字符。**
   最初用 `<<<AUDIOPROBE:FINGER>>>` 做分段标记，`sh` 把 `>>>` 当成重定向，
   整条命令直接 `syntax error: unexpected '>'`。现在用 `__AUDIOPROBE_SECTION_*__`。

2. **binder 回包放不下。**
   三份 dump 合计约 66 万字符，AIDL 的 `String` 按 UTF-16 编组后超过 binder 事务缓冲区，
   调用直接失败（`Transaction failed on small parcel...`）。
   现在由特权进程内 `DumpTrimmer` 先裁剪再回传（约 1 万字符），并有 `MAX_CHARS` 兜底。

3. **Android 16 的 track 行多一个 `Type` 列。**
   Android 17 上该列为空，Android 16 上会打印 `S`，严格的列正则会把整行丢掉。
   现在把 `Type` 匹配成可选的非捕获前缀。

4. **活跃蓝牙设备的标题带后缀。**
   形如 `=== A2dpStateMachine for XX:XX:XX:XX:FA:02 (Active) ===`。
   只认裸 MAC 的正则会**恰好跳过唯一在播放的那台设备**。

5. **`toIntOrNull(16)` 不接受 `0x` 前缀。**
   `dumpsys audio` 打印 `channelMask=0x3`，直接按 16 进制解析会得到 null 并被兜底成 0。
   统一走 `ParseUtil.parseHexInt()`。

6. **正则里的裸花括号在 Android 上编译失败（最隐蔽的一个）。**
   `Regex("""mCodecConfig:\s*\{(.*)}""")` 在桌面 JVM 上合法，但 Android 的 regex 引擎拒绝它。
   由于这是对象里的 `val`，异常发生在 `<clinit>`，ART 会把这个类**永久标记为失败**：

   ```
   Rejecting re-init on previously-failed class com.audioprobe.audio.BluetoothParser:
     java.lang.ExceptionInInitializerError
     at void com.audioprobe.audio.BluetoothParser.<clinit>()
   ```

   症状离原因很远（解析器静默不工作、蓝牙这一级凭空消失）。
   现在该处改用普通字符串操作，并由 `RegexPortabilityTest` 扫描全部 `Regex` 字面量，
   禁止裸 `{` / `}`（只允许转义、字符类内、或 `{n,m}` 量词）。

---

## 7. 代码结构

```
app/src/main/aidl/com/audioprobe/IProbeService.aidl   特权接口（exec / sampleDumps）
app/src/main/java/com/audioprobe/
  ProbeService.kt          以 uid 2000 运行的 user service，执行 dumpsys 并裁剪
  priv/PrivilegeBackend.kt 提权后端接口（可插拔）
  priv/ShizukuBackend.kt   Shizuku 实现：权限、绑定、自愈
  data/ProbeEngine.kt      一次采样：跑命令 → 分段 → 交给解析层
  audio/DumpTrimmer.kt     特权侧裁剪（必须：否则 binder 放不下）
  audio/AudioFlingerParser.kt / AudioServiceParser.kt / BluetoothParser.kt
  audio/AudioSnapshot.kt   模型 + 合并成「链路」
  audio/AudioFormats.kt    格式/设备/usage 名称表
  ui/                      Compose 界面
```

### 关于 Stellar

[Stellar](https://github.com/roro2239/Stellar) 是另一个 Shizuku 实现，但它用的是自己的命名空间
`roro.stellar.shizuku`，不是 drop-in 替换，需要单独接 SDK。

提权层已经抽象成 `PrivilegeBackend` 接口（`state` / `connect` / `requestPermission` /
`exec` / `sampleDumps`），**加一个 `StellarBackend` 实现即可**，音频解析与界面完全不用动。
目前只实现了 `ShizukuBackend`。

---

## 8. 已知限制

- **解析器针对 vivo Android 16/17 的 dump 格式**，并带有宽松回退（能取出源采样率/格式）。
  其他 ROM 若列布局不同，可能只解析出部分字段。
- 应用内置**自检**：系统的 `N Tracks` 声明数与实际解析数不一致时，诊断区会提示
  「音频轨道解析不完整」，并可直接查看/复制当前解析用的 dump，便于适配新 ROM。
- 蓝牙编码器的实时码率（如 LDAC 990/660 kbps）没有采集，`mCodecConfig` 里不包含。
- 文件本身的位深（例如 FLAC 是 16bit）不等于应用交给框架的位深：播放器常以 float 输出，
  界面显示的是**框架实际收到/输出的格式**，这也是"实际输出"能回答的问题。

---
哦鲸鲸...
---

## 10. 许可证

MIT，见 [LICENSE](LICENSE)。
