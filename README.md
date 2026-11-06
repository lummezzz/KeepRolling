# Keep Rolling · 滚滚车流

[简体中文](#简体中文) | [English](#english)

> 一个俯视视角的地面交通微观模拟器。像玩沙盒一样在空白画布上「画」出道路，把断头路端点设成开放路口，按下开始，看成百上千辆车自己等红灯、跟车、变道、堵死、绕路。
> 仅 Android，单 Activity，纯本地运行，无账号、无网络、无遥测。

[![Latest release](https://img.shields.io/github/v/release/lummezzz/KeepRolling)](https://github.com/lummezzz/KeepRolling/releases/latest)  
**[⬇ 下载 APK](https://github.com/lummezzz/KeepRolling/releases/latest)** · [全部版本](https://github.com/lummezzz/KeepRolling/releases) · Android 8.0 (API 26)+

---

<a id="简体中文"></a>
## 简体中文

### 1. 项目简介

Keep Rolling 是一个用 Kotlin + Jetpack Compose 从零手写的**交通流微观仿真沙盒**。它不是视频播放，也不是预置地图：路网由用户现场绘制，车辆由仿真引擎按固定步长 `dt = 1/60 s` 逐帧推演，UI 只读每帧发布的不可变快照（`SimFrame`）来绘制。

- **打开即画布**：没有启动页、教程页、登录页；全屏沉浸、竖屏、单 Activity。
- **两种模式**：观看模式（VIEW）与编辑模式（EDIT），底栏两组按钮做「换班」动画。
- **默认中文**，支持一键切换英文，全部界面文案双语。
- **纯本地**：整个 App 只申请了一个权限 `android.permission.VIBRATE`（按钮轻震），没有 INTERNET 权限，路网存档写在 App 私有目录。

| 项 | 值 |
|---|---|
| 包名 / applicationId | `com.keeprolling.app` |
| 版本 | `versionName 0.1.0` / `versionCode 1` |
| 平台 | Android 8.0（API 26）及以上，竖屏 |
| 语言 | Kotlin，UI 为 Jetpack Compose（非 XML 布局） |
| 架构 | MVVM：ViewModel（StateFlow）+ 纯 Kotlin 模型层 + 十层自绘画布 |
| 源码规模 | 40 个 `.kt` 文件，约 1.96 万行（不含资源） |
| 资源 | `res/raw` 76 个音频文件（约 29 MB） |
| 权限 | 仅 `VIBRATE` |

### 2. 功能特性

#### 2.1 路网编辑

- **6 种路型**：直线 / 曲线 × 单 / 双 / 三车道（每方向最多 3 条，车道宽 3.5 m）。
- **三级吸附**：端点吸附既有节点（7 m）、吸附到既有路面内部（6 m）、否则吸附 10 m 网格；预览与最终生成共用同一套逻辑，所见即所得。
- **自动成网**：新路与既有路求交并在交点打断重建，自动形成十字 / 丁字 / 转角路口；圆弧被打断后按母弧半径反算子弧曲率，保证仍在同一圆上；匝道与直线相切。
- **选中后可改**：拖端点移动道路、拖曲率手柄调弯、一键改车道数、删除；断头路端点可「开放」成出入口，开放路口可「封闭」，编号一经分配永久保留。
- **红绿灯**：度 ≥ 3 的路口可增删灯，三套相位规则可选（默认「直行与转弯分开放行」）。
- **撤销 / 重做**：快照栈深度 40；另有防抖自动暂存，冷启动恢复上次路网。

#### 2.2 仿真引擎

- **生成**：每个开放路口独立配置生成间隔（1~10 秒/辆）、三车型比例、速度分布曲线（9 个控制点，40~120 km/h，Catmull-Rom 平滑 + 逆变换采样）、路口热度（1~10，按热度加权抽目的地）。
- **跟驰**：IDM 智能驾驶员模型 + 每辆车随机反应时间；**停止线是位置约束** `v_max = √(2·b·s)`，而不是「一辆静止的前车」（否则车会离路口二十米就急刹）。
- **横向**：按转向 + 车道数决定合法车道，目标车道在进入路段时算一次并粘住整段；被前车压住 1.5 s 且邻道有空当则自主变道，等红灯时禁止变道。
- **路由**：每 2 秒用 Dijkstra 重算「最短里程场」与「实时耗时场」；新车首段只按里程场走，避免「刚出生就绕远」。
- **路口**：三套信号规则、2 秒黄灯、右转免等灯、绿灯起步的「接力」延迟；无灯路口按几何走廊让行，双车互锁超 2 秒时让转弯车倒车 4~6 m 解死锁。
- **自主改道**：5 秒速度环形缓冲，四档预设（关 / 灵敏 / 标准 / 保守）；堵住时用耗时场换路并进入冷却。
- **到达与崩溃**：到达目的地后 0.45 s 淡出；某开放路口持续堵塞超过阈值（默认 15 s）判定网络崩溃，弹出非遮挡红色横幅，点它回编辑模式。

#### 2.3 观看与交互

- 开始 / 暂停有前置校验（无路、无开放路口、只有一个开放路口都会提示而不启动）。
- 点车 = 锁定跟随 + 车辆信息卡（实时速度 / 预设速度 / 起始路口 / 目标路口）；统计面板 6 行（运行时间、在跑 / 到达车辆数、累计换路 / 变道、帧率）。
- 倍速 6 档 `0.5 / 1 / 1.5 / 2 / 3 / 5`；昼夜三模式（白天 / 夜间 / AUTO 随仿真时间渐变）；「去UI」380 ms 淡出全部界面、单击唤回。
- 手势统一在一个 `awaitEachGesture` 状态机：长按 0.4 s 画路、单指平移、双指锚点缩放、双击复位、轻点命中、两档拖动触觉。
- 动画手感：底栏换班滑入、按压三件套（音效 + 微缩放 + 轻震，三者独立可关）、圆环扩散**只给「开始 / 暂停」**这一个按钮。

#### 2.4 音频与存档

- BGM 按场景分袋随机循环（白天观看 4 首 / 夜间 4 首 / 编辑 2 首 / 崩溃 1 首）+ 昼夜两条环境底噪；切场景淡入淡出，音乐关闭只静音、保留播放位置。
- 63 个 WAV 短音效由 `tools/gen_audio.py` 逐样本合成（固定种子、零第三方版权）；13 个 OGG 为音乐 / 环境层素材。
- 音乐、音效、轻震是**三个独立开关**，均随设置持久化。
- 存档为 JSON（`kotlinx.serialization`）：保存弹窗上部是**用路网数据重绘**的贴边缩略图，下部名称输入 + 当前时间；加载弹窗是两列可上下滑的海报墙，点海报再叠一层确认框（深红警告）。

### 3. 技术栈

| 层 | 选型 |
|---|---|
| 语言 / JDK | Kotlin 2.0.20 / JDK 17 |
| UI | Jetpack Compose，BOM `2024.09.03`，Material 3 + `material-icons-extended` |
| 构建 | Gradle 8.7（Kotlin DSL）、Android Gradle Plugin 8.5.2、Compose Compiler Plugin 2.0.20 |
| Android | `compileSdk 34` / `targetSdk 34` / `minSdk 26` |
| 异步 / 状态 | Kotlin Coroutines + `StateFlow` / `MutableStateFlow`，仿真跑在 `Dispatchers.Default` |
| 序列化 | `kotlinx-serialization-json 1.7.3`（存档与撤销快照） |
| 宿主 | `activity-compose 1.9.2`、`lifecycle-viewmodel-compose 2.8.6` |
| 绘制 | 自建 `Canvas` 十层叠绘 + `graphicsLayer` / `RenderNode` 复用，无 OpenGL、无游戏引擎 |
| 音频 | `SoundPool`（低延迟音效）+ `MediaPlayer`（BGM 交叉淡化） |
| 存储 | App 私有目录 `filesDir/networks/*.json`，无数据库、无 SharedPreferences 依赖 |

分层纪律：Canvas **不做业务判断**（只画快照），ViewModel **不出现 Color / Dp**，模型层 **不引用 Compose**。

### 4. 工程结构

```
KeepRolling/
├─ settings.gradle.kts          # 模块 + 依赖仓库（已配阿里云镜像，失败回落官方源）
├─ build.gradle.kts             # 插件版本
├─ gradle.properties            # Gradle 守护进程参数
├─ gradle/wrapper/              # Gradle 8.7 发行包（腾讯云镜像）
├─ app/
│  ├─ build.gradle.kts          # SDK 版本、依赖、release 构建类型
│  └─ src/main/
│     ├─ AndroidManifest.xml    # 仅 VIBRATE 权限，portrait，全屏主题
│     ├─ res/                   # 图标、样式、raw/ 76 个音频
│     └─ java/com/keeprolling/app/
│        ├─ MainActivity.kt            # 单 Activity、沉浸全屏、音频生命周期钩子
│        ├─ model/                     # 纯 Kotlin 仿真引擎
│        │  ├─ Vector2.kt / RoadPath.kt        # 向量、直线 / 圆弧路径
│        │  ├─ RoadNetwork.kt                  # 节点、边、邻接表、addRoad 七步
│        │  ├─ VehicleType.kt                 # 车型规格、JunctionConfig、SimSettings
│        │  ├─ Vehicle.kt / TrafficSimulator.kt  # 车辆运行态、IDM / 信号 / 路由 / 崩溃
│        │  ├─ JunctionGeometry.kt / Scenery.kt  # 路口几何、街景生成
│        ├─ viewmodel/SimulatorViewModel.kt   # 全部状态与用户动作、仿真协程、存档
│        ├─ storage/NetworkRepository.kt      # JSON 存档 + 自动暂存
│        ├─ audio/                           # SoundCenter / BgmTrack / Sfx
│        └─ ui/
│           ├─ MainScreen.kt / SimCanvas.kt  # 根布局、十层画布
│           ├─ CameraState.kt / Gestures.kt / DayCycle.kt / UiPalette.kt
│           ├─ VehicleDraw.kt / SceneryRender.kt
│           ├─ i18n/Messages.kt             # StrKey 枚举 + 中英映射
│           └─ components/                  # 底栏、上下文按钮、弹窗、滑块、触觉等
├─ tools/                       # 音频合成与自检脚本（gen_audio.py 等）
└─ docs/打包指南.md             # 本地打包与签名说明
```

### 5. 安装与使用

#### 5.1 环境要求

| 工具 | 版本 |
|---|---|
| Android Studio | Koala (2024.1) 或更新 |
| JDK | 17（Android Studio 自带，Gradle JDK 选 17） |
| Android SDK | Platform 34 + 对应 Build-Tools |
| 设备 | Android 8.0+ 真机或模拟器（建议真机，触觉反馈需要硬件振动器） |

> 国内网络：`settings.gradle.kts` 已配阿里云镜像，`gradle-wrapper.properties` 指向腾讯云 Gradle 镜像。若镜像不同步，把阿里云两行删掉改回 `google()` / `mavenCentral()`（需代理）。

#### 5.2 从源码构建（请在你自己的机器上执行）

1. Android Studio → **File → Open** → 选择本目录（含 `settings.gradle.kts` 的那一层）。
2. 等待 Gradle Sync 完成（首次下载 Gradle 8.7 与依赖，约 3~10 分钟）。
3. 手机开启「开发者选项 + USB 调试」，点 ▶ Run。

命令行（在项目根目录执行）：

```bash
# macOS / Linux
./gradlew assembleDebug
./gradlew installDebug          # 已连接设备时直接安装

# Windows
gradlew.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。也可手动安装：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Release 包**：当前 `app/build.gradle.kts` 的 `release` 只关掉了混淆（`isMinifyEnabled = false`），**尚未配置签名**。需要按 `docs/打包指南.md` 生成 keystore、创建不提交的 `keystore.properties`、在 `signingConfigs` 里接上，再执行 `gradlew.bat assembleRelease`。不要用 debug 签名包对外分发。

#### 5.3 上手流程（第一次运行）

1. 底部点 **编辑** ✎ → 进入浅灰底的编辑模式。
2. 点 **道路** → 左侧展开 6 个圆钮，选一种路型（例如「直线·单车道」）。
3. **单指按住画布约 0.4 秒** → 出现起点同心圆 → 拖动（虚线预览跟手）→ **抬手生成**道路。端点会自动吸附网格 / 既有路面。
4. 画 2~3 条相交的路，点断头路的**红色小圆圈** → 浮出「开放」→ 变成绿色开放路口。至少开放 **2 个**。
5. 点开放路口 → 「出入口设置」里调生成间隔、车型比例、速度分布、路口热度；点勾 ✔ 应用。
6. 底栏点 **返回** ✔ → 回观看模式 → 点 **开始/暂停** ▶，车流开始跑。
7. 观看中点任意车辆 = 锁定跟随；点开放路口可实时调参；双指缩放、双击复位。

#### 5.4 常用手势

| 手势 | 编辑模式 | 观看模式 |
|---|---|---|
| 单指拖动 | 平移画布（已选路型时按住 0.4 s 则开始画路） | 平移画布 |
| 双指 | 以两指中点为锚缩放 + 平移 | 同左 |
| 双击空白 | 视角复位到整个路网 | 同左 |
| 轻点 | 命中道路 / 端点 / 路口 / 信号灯 | 命中车辆 / 开放路口 |
| 拖端点 / 曲率手柄 | 移动道路 / 调弯 | — |
| 双指轻点 | 撤销 | — |

观看模式点底部 **设置** ⚙ 展开左侧设置行，从上到下为：倍速 · 统计 · 自主规划 · 昼夜 · 去UI ‖ 语言 · 音频 · 保存 · 加载。所有「向右展开」的按钮行为一致：点开一排圆钮、选中即收起。

### 6. 配置项

配置分三层，都在源码或应用内可调，**不需要任何环境变量或云端配置**。

#### 6.1 构建期（`*.gradle.kts` / `gradle.properties`）

| 位置 | 键 | 当前值 | 说明 |
|---|---|---|---|
| `app/build.gradle.kts` | `minSdk` / `targetSdk` / `compileSdk` | 26 / 34 / 34 | 最低 Android 8.0 |
| | `versionCode` / `versionName` | 1 / `0.1.0` | 发版前递增 |
| | `isMinifyEnabled` | `false` | release 暂不混淆 |
| `build.gradle.kts` | AGP / Kotlin / Compose 插件 | 8.5.2 / 2.0.20 / 2.0.20 | 版本需与 Gradle 8.7 配套 |
| `settings.gradle.kts` | `repositories` | 阿里云 + google + mavenCentral | 国内镜像，失败回落官方源 |
| `gradle/wrapper/gradle-wrapper.properties` | `distributionUrl` | 腾讯云 `gradle-8.7-bin.zip` | 可改回 `services.gradle.org` |
| `gradle.properties` | `org.gradle.jvmargs` | `-Xmx2048m` | 大工程可调高 |

#### 6.2 全局仿真设置（`model/VehicleType.kt` → `SimSettings`，设置面板内实时生效、随存档持久化）

| 字段 | 默认 | 说明 |
|---|---|---|
| `speedMultiplier` | `1.0` | 倍速档位 `0.5 / 1 / 1.5 / 2 / 3 / 5` |
| `reactionTimeSec` | `1.0` | 反应时间基准，每车再随机 ±20% |
| `crashBlockSeconds` | `15.0` | 持续堵塞多少秒判定崩溃（面板 5~30 可调） |
| `gridSnap` | `true` | 画路是否吸附 10 m 网格 |
| `simpleRenderThreshold` | `800` | 车辆数超过此值简化渲染 |
| `showStats` | `false` | 右上角统计面板 |
| `dayMode` | `""` | `DAY` / `NIGHT` / `AUTO`；空串按旧 `nightMode` 推断 |
| `trafficLightsEnabled` | `true` | 十字 / 丁字路口自动配灯 |
| `autoReroute` / `rerouteWindowSec` / `rerouteSpeedRatio` / `rerouteCooldownSec` | `true` / `5.0` / `0.30` / `10.0` | 自主改道四档预设覆盖这三项 |
| `language` | `"ZH"` | `ZH` / `EN` |
| `musicEnabled` / `sfxEnabled` / `hapticEnabled` | 均 `true` | 三个独立开关 |
| `bgmVolume` / `sfxVolume` | `0.55` / `0.85` | 内部音量，界面未暴露滑块 |

#### 6.3 逐路口配置（`JunctionConfig`，出入口设置对话框内可调）

| 字段 | 默认 | 说明 |
|---|---|---|
| `spawnIntervalSec` | `8` | 1~10 秒/辆 |
| `carRatio` / `busRatio` / `truckRatio` | `0.60 / 0.25 / 0.15` | 三段式双滑块，和为 1 |
| `speedCurve` | `[0,0,0,0.5,1,0.5,0,0,0]` | 9 个控制点对应 40/50/…/120 km/h 的相对概率，峰值 80 |
| `heat` | `5` | 1~10，目的地按热度加权 |
| `speedMeanKmh` / `speedSigmaKmh` | `60.0 / 10.0` | **仅兼容旧存档**，不参与计算 |

#### 6.4 数据与路径

- 存档目录：`context.filesDir/networks/`（`SavedNetwork` 结构 = `name + network + configs + settings + savedAtMillis`）。
- 自动暂存：`autosave.json`，改动后防抖写入，冷启动恢复，不出现在加载列表里。
- 撤销栈：路网 + 路口配置的 JSON 深拷贝，深度 40，仅在内存中。
- ⚠️ 存档在 App 私有目录，**卸载 App 会一并删除**。

### 7. 示例

#### 示例 A：最小可玩路网（约 1 分钟）

```
编辑 → 道路 → 直线·单车道
  ├─ 画一条东西向长路（约 100 m）
  └─ 画一条南北向长路，穿过它（自动打断成十字路口）
点十字路口 → 加灯 → 红绿灯设置选「规则二」
点东西向路的左右两个断头端点 → 各点一次「开放」
点南北向路的下端点 → 「开放」（共 3 个开放路口）
点其中一个开放路口 → 生成间隔调到 3 秒/辆、热度调到 8
返回 → 开始 → 观察车流；把倍速切到 3x，堵到崩溃后点横幅回编辑加车道
```

#### 示例 B：命令行构建与安装

```bash
gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s KeepRollingHaptic      # 真机排查触觉反馈是否触发
```

#### 示例 C：一个存档 JSON 的形状

```json
{
  "name": "十字环线测试",
  "network": {
    "nodes": { "1": { "id": 1, "x": 0.0, "y": 0.0, "type": "OPEN" } },
    "edges": [ { "id": 1, "from": 1, "to": 2, "curvature": 0.0, "lanesPerDirection": 1 } ],
    "nextNodeId": 7, "nextEdgeId": 6, "nextJunctionNumber": 4
  },
  "configs": {
    "1": {
      "spawnIntervalSec": 3,
      "carRatio": 0.6, "busRatio": 0.25, "truckRatio": 0.15,
      "speedCurve": [0.0, 0.0, 0.0, 0.5, 1.0, 0.5, 0.0, 0.0, 0.0],
      "heat": 8
    }
  },
  "settings": { "speedMultiplier": 1.0, "dayMode": "AUTO", "trafficLightsEnabled": true },
  "savedAtMillis": 1762372800000
}
```

> 上例为**结构示意**（省略了 `SimSettings` 的其余字段与 `network` 的信号覆盖表）；实际文件由 `NetworkRepository` 用 `prettyPrint` 写出，反序列化时 `ignoreUnknownKeys = true`，因此手工编辑增删字段是安全的。

#### 示例 D：换依赖源 / 升版本

```kotlin
// settings.gradle.kts：删掉两行 maven("https://maven.aliyun.com/...")，只留
google()
mavenCentral()

// app/build.gradle.kts：发版前改这里
versionCode = 2
versionName = "0.2.0"
```

### 8. 隐私与数据

- 清单里**没有 `INTERNET` 权限**，App 无法联网；不收集、不上传任何数据。
- 唯一权限 `VIBRATE` 是 normal 级，安装即授予，不需要运行时申请，也不会弹对话框。
- 所有路网与设置只存在本机 App 私有目录；无账号、无广告、无第三方 SDK、无分析埋点。

### 9. 已知限制

- **仅 Android**，无 iOS / 桌面 / Web 版本；固定竖屏。
- 仿真是 2D 俯视，不支持立交 / 分层路网；路口为平面交叉。
- 车流规模大时依赖设备的 GPU / CPU；`simpleRenderThreshold` 会自动简化渲染。
- 音频资源约 29 MB，会打进 APK；`release` 构建类型尚未启用混淆与签名配置。
- 存档没有导出 / 导入功能，卸载即丢失。

### 10. 许可证

本项目采用 **MIT License**，全文见仓库根目录的 [`LICENSE`](LICENSE)。你可以自由使用、复制、修改、合并、发布、分发、再授权乃至出售本软件，条件是保留版权声明与许可声明；软件按「原样」提供，不含任何明示或暗示的担保。

第三方组件与素材：

| 来源 | 许可 | 备注 |
|---|---|---|
| AndroidX / Jetpack Compose / Material Icons | Apache-2.0 | 通过 Maven 依赖引入，未复制源码 |
| Kotlin / kotlinx-serialization | Apache-2.0 | 同上 |
| `res/raw/*.wav`（63 个短音效） | 本项目自行合成 | 由 `tools/gen_audio.py` 逐样本生成，固定随机种子，无第三方版权 |
| `res/raw/*.ogg`（13 个音乐 / 环境层） | **已确认可再分发** | 项目作者已确认允许随本项目按 MIT 一并分发；部分素材由作者提供的母版转码，来源记录由维护者保留在工程外层资料中，未随 Git 仓库分发 |
| 应用图标 | 待核实 | 见工程外层素材目录；二次分发整个仓库前建议补一条来源说明 |

> 新增或替换音频 / 图片素材时，请在 PR 中写明来源与授权（见 [`CONTRIBUTING.md`](CONTRIBUTING.md) 第 8 节）。若某个素材无法确认授权，应替换为自有素材或在 `NOTICE` 中单独声明，不要默默混进仓库。

### 11. 贡献

欢迎提 Issue 与 PR。完整流程见 [`CONTRIBUTING.md`](CONTRIBUTING.md)：先开 Issue 讨论、在 `feature/xxx` 或 `fix/xxx` 分支上改动、按 `<type>: <说明>` 写提交信息，PR 中用 `Closes #N` 关联 Issue。结构化的 Issue / PR 模板位于 [`.github/`](.github/)。

本仓库**尚未接入 CI**，提交前请**自行**运行构建与静态检查并确认结果通过：

```bash
gradlew.bat assembleDebug
gradlew.bat lint
gradlew.bat test
```

约定：分支用 `feature/xxx` 或 `fix/xxx`，不要在默认分支上直接改；提交信息写清「改了什么、为什么」，涉及仿真规则时请附上验证方式；模型层保持纯 Kotlin（不引 Compose），ViewModel 不出现 `Color` / `Dp`。

### 12. 下载

预编译 APK 以 **GitHub Release 附件**的形式提供，**不提交进 Git 仓库**（否则每次 clone 都要多背几十 MB 二进制，且无法优雅地删除历史）。

- **下载最新版**：<https://github.com/lummezzz/KeepRolling/releases/latest>
- **全部版本**：<https://github.com/lummezzz/KeepRolling/releases>
- **当前版本**：`v0.1.0`（`versionName 0.1.0` / `versionCode 1`），APK 约 40 MB，最低 Android 8.0（API 26）

安装步骤：下载 `.apk` → 系统提示时允许「安装未知来源应用」→ 打开安装。Release 页每个附件旁都显示了 SHA-256，可自行用 `sha256sum` 核对。也可以按第 5 节从源码构建。

---

<a id="english"></a>
## English

### 1. About

Keep Rolling is a **microscopic traffic-flow sandbox** written from scratch in Kotlin + Jetpack Compose. The road network is drawn by the user at runtime; vehicles are advanced by a fixed-step simulation (`dt = 1/60 s`) on a background dispatcher, and the UI only renders immutable `SimFrame` snapshots published at ~60 Hz. No video playback, no preset maps.

- **Open straight into the canvas** — no splash, tutorial, or login screen. Full-screen immersive, portrait, single Activity.
- **Two modes** — VIEW and EDIT, with a staggered "shift change" animation between the two bottom button groups.
- **Chinese by default**, one-tap English switch; every UI string is bilingual.
- **Fully offline** — the only permission is `android.permission.VIBRATE` (haptic taps). No INTERNET permission; saves live in the app-private directory.

| Item | Value |
|---|---|
| Package / applicationId | `com.keeprolling.app` |
| Version | `versionName 0.1.0` / `versionCode 1` |
| Platform | Android 8.0 (API 26)+, portrait |
| Language / UI | Kotlin, Jetpack Compose (no XML layouts) |
| Architecture | MVVM: ViewModel (StateFlow) + pure-Kotlin model + ten-layer custom Canvas |
| Source size | 40 `.kt` files, ~19,600 lines (excluding resources) |
| Assets | 76 audio files in `res/raw` (~29 MB) |
| Permissions | `VIBRATE` only |

### 2. Features

#### 2.1 Network editing

- **Six road tools**: straight / curved × 1 / 2 / 3 lanes per direction (lane width 3.5 m, max 3).
- **Three-level snapping**: existing node (7 m), onto an existing road surface (6 m), otherwise a 10 m grid. Preview and final geometry share the same snapping code — WYSIWYG.
- **Automatic topology**: intersections are detected and both roads are split and rebuilt into cross / T / corner junctions; a split arc re-derives its curvature from the parent radius so it stays on the same circle; ramps are built tangent to straight roads.
- **Selection editing**: drag endpoints, drag a curvature handle, change lane count, delete, open/close dead-end entrances (junction numbers are permanent once assigned).
- **Traffic lights**: junctions with degree ≥ 3 can get/remove signals, with three phase-rule presets (default: separate straight and turning phases).
- **Undo / redo** with a 40-deep snapshot stack, plus debounced autosave restored on cold start.

#### 2.2 Simulation engine

- **Spawn**: per-entrance interval (1–10 s), three vehicle-class ratios, a 9-point speed distribution curve (40–120 km/h, Catmull-Rom + inverse-CDF sampling), and junction heat (1–10) used to weight destinations.
- **Car following**: IDM with per-vehicle randomized reaction time. The stop line is a **position constraint** `v_max = √(2·b·s)`, not a stationary lead vehicle (otherwise cars brake 20 m early).
- **Lateral**: legal lanes derived from turn direction and lane count, computed once per arc and latched; autonomous lane changes when blocked for 1.5 s with a gap nearby; no lane changes while queuing at a red light.
- **Routing**: Dijkstra rebuilds a shortest-distance field and a live-travel-time field every 2 s; newly spawned vehicles use the distance field for their first arc.
- **Intersections**: three signal rules, 2 s amber, free right turns, staggered green-launch "relay"; unsignalized junctions yield via a geometric corridor, and deadlocks are broken by backing the turning car up 4–6 m.
- **Autonomous rerouting**: a 5-second speed ring buffer with four presets (Off / Sensitive / Standard / Conservative).
- **Arrival & collapse**: vehicles fade out over 0.45 s on arrival; an entrance continuously blocked past a threshold (default 15 s) collapses the network and raises a non-blocking red banner that returns to EDIT.

#### 2.3 Viewing & interaction

- Start/Pause validates preconditions (no roads, no open entrances, only one open entrance → toast instead of starting).
- Tap a vehicle to lock-follow it and show an info card (live speed / desired speed / origin / destination); a six-row stats panel (elapsed time, active / arrived vehicles, cumulative reroutes / lane changes, FPS).
- Six speed multipliers `0.5 / 1 / 1.5 / 2 / 3 / 5`; three day modes (Day / Night / AUTO driven by simulation time); "Hide UI" fades all chrome out in 380 ms and a tap brings it back.
- One unified `awaitEachGesture` state machine: 0.4 s long-press to draw, one-finger pan, two-finger anchored zoom, double-tap to fit, tap hit-testing, two-tier drag haptics.
- Motion: staggered bottom-bar shift, a press triple (sound + micro-scale + light haptic, each independently switchable), and an expanding ring on **Start/Pause only**.

#### 2.4 Audio & saves

- Scene-bagged BGM with random rotation (4 day-view / 4 night-view / 2 edit / 1 collapse) plus day/night ambience; cross-fades on scene change; muting music keeps the playback position.
- 63 WAV short SFX are synthesized sample-by-sample by `tools/gen_audio.py` (fixed seed, no third-party copyright); 13 OGG files are the music/ambience layer.
- Music, SFX and haptics are **three independent switches**, all persisted.
- Saves are JSON (`kotlinx.serialization`): the save dialog shows a full-bleed thumbnail **re-drawn from network data** plus a name field and the current timestamp; the load dialog is a two-column scrollable poster wall, and tapping a poster stacks a confirmation dialog with a dark-red warning line.

### 3. Tech stack

| Layer | Choice |
|---|---|
| Language / JDK | Kotlin 2.0.20 / JDK 17 |
| UI | Jetpack Compose, BOM `2024.09.03`, Material 3 + `material-icons-extended` |
| Build | Gradle 8.7 (Kotlin DSL), AGP 8.5.2, Compose Compiler Plugin 2.0.20 |
| Android | `compileSdk 34` / `targetSdk 34` / `minSdk 26` |
| Async / state | Coroutines + `StateFlow`, simulation on `Dispatchers.Default` |
| Serialization | `kotlinx-serialization-json 1.7.3` (saves and undo snapshots) |
| Host | `activity-compose 1.9.2`, `lifecycle-viewmodel-compose 2.8.6` |
| Rendering | Ten stacked custom `Canvas` layers with `graphicsLayer` / RenderNode reuse; no OpenGL, no game engine |
| Audio | `SoundPool` (low-latency SFX) + `MediaPlayer` (cross-faded BGM) |
| Storage | App-private `filesDir/networks/*.json`; no database, no SharedPreferences |

Layering rule: the Canvas makes **no business decisions** (it draws snapshots), the ViewModel contains **no Color / Dp**, and the model layer **never imports Compose**.

### 4. Project layout

```
KeepRolling/
├─ settings.gradle.kts        # modules + repositories (Aliyun mirrors, official fallback)
├─ build.gradle.kts           # plugin versions
├─ gradle.properties          # Gradle daemon options
├─ gradle/wrapper/            # Gradle 8.7 distribution (Tencent mirror)
├─ app/
│  ├─ build.gradle.kts        # SDK levels, dependencies, release build type
│  └─ src/main/
│     ├─ AndroidManifest.xml  # VIBRATE only, portrait, fullscreen theme
│     ├─ res/                 # launcher icons, styles, raw/ 76 audio files
│     └─ java/com/keeprolling/app/
│        ├─ MainActivity.kt           # single Activity, immersive mode, audio lifecycle
│        ├─ model/                    # pure-Kotlin simulation engine
│        ├─ viewmodel/SimulatorViewModel.kt
│        ├─ storage/NetworkRepository.kt
│        ├─ audio/                    # SoundCenter / BgmTrack / Sfx
│        └─ ui/                      # MainScreen, SimCanvas (10 layers), Gestures,
│                                    # CameraState, DayCycle, i18n/Messages, components/
├─ tools/                     # audio synthesis & verification Python scripts
└─ docs/打包指南.md            # local build & signing guide (Chinese)
```

### 5. Install & use

#### 5.1 Requirements

| Tool | Version |
|---|---|
| Android Studio | Koala (2024.1) or newer |
| JDK | 17 (bundled with Android Studio; set Gradle JDK to 17) |
| Android SDK | Platform 34 + matching Build-Tools |
| Device | Android 8.0+ device or emulator (a real device is recommended for haptics) |

> For mainland-China networks, `settings.gradle.kts` already uses Aliyun mirrors and the wrapper points at a Tencent mirror. If a mirror is out of sync, remove the two Aliyun lines and fall back to `google()` / `mavenCentral()` (a proxy may be needed).

#### 5.2 Build from source (run these on your own machine)

1. Android Studio → **File → Open** → select this directory (the one containing `settings.gradle.kts`).
2. Wait for Gradle Sync (first run downloads Gradle 8.7 + dependencies, ~3–10 min).
3. Enable **Developer options + USB debugging**, then click ▶ Run.

Command line, from the project root:

```bash
# macOS / Linux
./gradlew assembleDebug
./gradlew installDebug

# Windows
gradlew.bat assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. Manual install:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Release builds**: the `release` block currently only disables minification (`isMinifyEnabled = false`) and has **no signing config**. Follow `docs/打包指南.md` to create a keystore, write an uncommitted `keystore.properties`, wire up `signingConfigs`, then run `gradlew.bat assembleRelease`. Never distribute a debug-signed APK.

#### 5.3 First run

1. Tap **Edit** ✎ → light-grey editing mode.
2. Tap **Road** → six round tools expand on the left; pick one (e.g. straight, single lane).
3. **Long-press the canvas ~0.4 s** → concentric start marker → drag (dashed preview follows) → **lift to create**. Endpoints snap to the grid or existing roads.
4. Draw 2–3 intersecting roads; tap a **red dead-end circle** → "Open" → it becomes a green entrance. Open **at least two**.
5. Tap an entrance → adjust spawn interval, class ratios, speed curve and heat in the entrance dialog → confirm ✔.
6. Tap **Back** ✔ → VIEW → tap **Start/Pause** ▶ to run.
7. Tap any vehicle to follow it; tap an entrance to tune it live; pinch to zoom, double-tap to fit.

#### 5.4 Gestures

| Gesture | EDIT | VIEW |
|---|---|---|
| One-finger drag | Pan (with a road tool selected, 0.4 s hold starts drawing) | Pan |
| Two fingers | Anchor-zoom on the midpoint + pan | Same |
| Double-tap empty area | Fit whole network | Same |
| Tap | Hit-test roads / endpoints / junctions / signals | Hit-test vehicles / entrances |
| Drag endpoint / curvature handle | Move road / adjust curve | — |
| Two-finger tap | Undo | — |

In VIEW, the **Settings** ⚙ button expands the left settings row, top to bottom: Speed · Stats · Auto reroute · Day/Night · Hide UI ‖ Language · Audio · Save · Load. Every "expand to the right" control behaves the same way: a row of round buttons, collapsing as soon as one is selected.

### 6. Configuration

Configuration lives at three levels; **no environment variables or cloud config are required**.

#### 6.1 Build time (`*.gradle.kts`, `gradle.properties`)

| Location | Key | Current | Note |
|---|---|---|---|
| `app/build.gradle.kts` | `minSdk` / `targetSdk` / `compileSdk` | 26 / 34 / 34 | Android 8.0 minimum |
| | `versionCode` / `versionName` | 1 / `0.1.0` | Bump before release |
| | `isMinifyEnabled` | `false` | Minification off for now |
| `build.gradle.kts` | AGP / Kotlin / Compose plugin | 8.5.2 / 2.0.20 / 2.0.20 | Must match Gradle 8.7 |
| `settings.gradle.kts` | `repositories` | Aliyun + google + mavenCentral | Mirrors with official fallback |
| `gradle-wrapper.properties` | `distributionUrl` | Tencent `gradle-8.7-bin.zip` | Can revert to `services.gradle.org` |
| `gradle.properties` | `org.gradle.jvmargs` | `-Xmx2048m` | Raise for larger builds |

#### 6.2 Global simulation settings (`SimSettings`, `model/VehicleType.kt`; live-editable in the settings sheet, persisted with saves)

| Field | Default | Meaning |
|---|---|---|
| `speedMultiplier` | `1.0` | Steps `0.5 / 1 / 1.5 / 2 / 3 / 5` |
| `reactionTimeSec` | `1.0` | Base reaction time, each vehicle randomized ±20% |
| `crashBlockSeconds` | `15.0` | Sustained blockage before collapse (5–30 in UI) |
| `gridSnap` | `true` | Snap new roads to the 10 m grid |
| `simpleRenderThreshold` | `800` | Vehicle count above which rendering simplifies |
| `showStats` | `false` | Top-right stats panel |
| `dayMode` | `""` | `DAY` / `NIGHT` / `AUTO`; empty falls back to legacy `nightMode` |
| `trafficLightsEnabled` | `true` | Auto-signal cross / T junctions |
| `autoReroute` / `rerouteWindowSec` / `rerouteSpeedRatio` / `rerouteCooldownSec` | `true` / `5.0` / `0.30` / `10.0` | Overridden by the four reroute presets |
| `language` | `"ZH"` | `ZH` / `EN` |
| `musicEnabled` / `sfxEnabled` / `hapticEnabled` | all `true` | Three independent switches |
| `bgmVolume` / `sfxVolume` | `0.55` / `0.85` | Internal mix levels, no UI slider |

#### 6.3 Per-entrance settings (`JunctionConfig`, entrance dialog)

| Field | Default | Meaning |
|---|---|---|
| `spawnIntervalSec` | `8` | 1–10 s per vehicle |
| `carRatio` / `busRatio` / `truckRatio` | `0.60 / 0.25 / 0.15` | Two-handle three-segment slider, sums to 1 |
| `speedCurve` | `[0,0,0,0.5,1,0.5,0,0,0]` | 9 control points for 40/50/…/120 km/h relative probability, peak at 80 |
| `heat` | `5` | 1–10, weights destination selection |
| `speedMeanKmh` / `speedSigmaKmh` | `60.0 / 10.0` | **Legacy compatibility only**, unused in computation |

#### 6.4 Data & paths

- Saves: `context.filesDir/networks/` — `SavedNetwork = name + network + configs + settings + savedAtMillis`.
- Autosave: `autosave.json`, written with debounce after each change, restored on cold start, hidden from the load list.
- Undo stack: deep JSON copies of network + configs, depth 40, memory only.
- ⚠️ Saves live in the app-private directory — **uninstalling the app deletes them**.

### 7. Examples

#### Example A: minimal playable network (~1 minute)

```
Edit → Road → straight, single lane
  ├─ draw a long east-west road (~100 m)
  └─ draw a north-south road crossing it (auto-split into a cross junction)
Tap the junction → Add signal → choose "Rule 2"
Tap both dead ends of the east-west road → "Open" on each
Tap the south end of the north-south road → "Open" (3 entrances total)
Tap one entrance → interval 3 s, heat 8
Back → Start → watch; switch to 3x, let it jam, then tap the banner
  to return to EDIT and add lanes
```

#### Example B: build & install

```bash
gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s KeepRollingHaptic      # verify haptic fallback on a real device
```

#### Example C: shape of a save file

```json
{
  "name": "Cross loop test",
  "network": {
    "nodes": { "1": { "id": 1, "x": 0.0, "y": 0.0, "type": "OPEN" } },
    "edges": [ { "id": 1, "from": 1, "to": 2, "curvature": 0.0, "lanesPerDirection": 1 } ],
    "nextNodeId": 7, "nextEdgeId": 6, "nextJunctionNumber": 4
  },
  "configs": {
    "1": {
      "spawnIntervalSec": 3,
      "carRatio": 0.6, "busRatio": 0.25, "truckRatio": 0.15,
      "speedCurve": [0.0, 0.0, 0.0, 0.5, 1.0, 0.5, 0.0, 0.0, 0.0],
      "heat": 8
    }
  },
  "settings": { "speedMultiplier": 1.0, "dayMode": "AUTO", "trafficLightsEnabled": true },
  "savedAtMillis": 1762372800000
}
```

> Structural illustration only (remaining `SimSettings` fields and the signal override maps are omitted). Real files are written with `prettyPrint` and decoded with `ignoreUnknownKeys = true`, so hand-editing fields is safe.

#### Example D: switch repositories / bump version

```kotlin
// settings.gradle.kts: drop the two maven("https://maven.aliyun.com/...") lines, keep
google()
mavenCentral()

// app/build.gradle.kts: before a release
versionCode = 2
versionName = "0.2.0"
```

### 8. Privacy & data

- **No `INTERNET` permission** — the app cannot reach the network; nothing is collected or uploaded.
- The only permission, `VIBRATE`, is normal-level: granted at install time, no runtime prompt.
- All networks and settings stay in the app-private directory. No accounts, ads, third-party SDKs, or analytics.

### 9. Known limitations

- **Android only**; no iOS / desktop / web build; portrait is fixed.
- 2D top-down simulation only — no grade separation, overpasses, or layered networks.
- Large vehicle counts depend on device GPU/CPU; `simpleRenderThreshold` degrades rendering automatically.
- ~29 MB of audio is packaged into the APK; the `release` build type has no minification or signing config yet.
- No save export/import; uninstalling loses all saves.

### 10. License

Licensed under the **MIT License** — see [`LICENSE`](LICENSE) in the repository root. You may use, copy, modify, merge, publish, distribute, sublicense and/or sell copies of the software, provided the copyright and permission notices are retained; the software is provided "as is", without warranty of any kind.

Third-party components and assets:

| Source | License | Note |
|---|---|---|
| AndroidX / Jetpack Compose / Material Icons | Apache-2.0 | Maven dependencies, no source copied in |
| Kotlin / kotlinx-serialization | Apache-2.0 | Same |
| `res/raw/*.wav` (63 SFX) | Own work | Synthesized by `tools/gen_audio.py`, fixed seed, no third-party copyright |
| `res/raw/*.ogg` (13 music / ambience) | **Redistribution confirmed** | The project author has confirmed these may be distributed with the project under MIT; some were transcoded from author-supplied masters. Provenance records are kept outside the Git repository |
| Launcher icon | To verify | See the asset folder outside the Gradle project; add a provenance note before redistributing the whole repo |

> When adding or replacing audio/image assets, state their source and license in the PR (see [`CONTRIBUTING.md`](CONTRIBUTING.md) §8). If an asset's license cannot be confirmed, replace it with an owned one or declare it separately in a `NOTICE` file.

### 11. Contributing

Issues and PRs are welcome. See [`CONTRIBUTING.md`](CONTRIBUTING.md) for the full workflow: open an issue first, work on a `feature/xxx` or `fix/xxx` branch, use `<type>: <summary>` commits, and link the issue with `Closes #N`. Structured issue/PR templates live in [`.github/`](.github/).

There is **no CI yet**, so **run and confirm** the build and static checks yourself before submitting:

```bash
gradlew.bat assembleDebug
gradlew.bat lint
gradlew.bat test
```

Conventions: use `feature/xxx` or `fix/xxx` branches rather than committing directly to the default branch; describe both *what* and *why* in commit messages; include a verification method when changing simulation rules; keep the model layer pure Kotlin (no Compose) and the ViewModel free of `Color` / `Dp`.

### 12. Downloads

Prebuilt APKs ship as **GitHub Release assets** and are **never committed to the repository** (that would add tens of megabytes of binaries to every clone, and rewriting history later is painful).

- **Latest release**: <https://github.com/lummezzz/KeepRolling/releases/latest>
- **All releases**: <https://github.com/lummezzz/KeepRolling/releases>
- **Current**: `v0.1.0` (`versionName 0.1.0` / `versionCode 1`), ~40 MB APK, Android 8.0 (API 26)+

To install: download the `.apk`, allow "install unknown apps" when the system asks, then open it. Every asset on the release page shows a SHA-256 you can verify with `sha256sum`. You can also build from source as described in §5.
