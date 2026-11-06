# 贡献指南 / Contributing to Keep Rolling

[简体中文](#简体中文) | [English](#english)

> **先读这一句**：本文档里出现的构建与检查命令，都是「供你复制的原文」。请**你本人**在本机运行并确认结果；维护者不会代跑构建或测试，也不会替你判断通过与否。执行任何命令前请自行确认它来自可信来源。

---

<a id="简体中文"></a>
## 简体中文

欢迎提 Issue 和 Pull Request。这是个单人维护的兴趣项目，流程尽量轻，但有几条底线能让 review 快很多。

### 1. 环境与准备

| 项 | 要求 |
|---|---|
| JDK | **17**（`compileOptions` / `jvmTarget` 均为 17，低版本会直接报错） |
| IDE | Android Studio（能加载 AGP 8.5.2 的版本） |
| Gradle | 用仓库自带的 **Wrapper**（`gradlew.bat` / `./gradlew`），不要用系统全局 gradle |
| SDK | compileSdk / targetSdk **34**，minSdk **26** |
| 设备 | 真机优先。模拟器没有震动马达，"轻震"反馈只能看日志，测不了手感 |

打开 `KeepRolling/`，等 Gradle Sync 完成再动手。若 Sync 失败，先把报错贴到 Issue 里讨论，别急着改构建脚本。

### 2. 动手之前：先开 Issue

- **Bug** → 用 `🐛 问题反馈 / Bug report` 模板，写清复现步骤与设备环境。
- **新功能 / 改进** → 用 `✨ 功能建议 / Feature request` 模板，先说场景再说方案。
- 改动较大的（仿真规则、渲染分层、存档格式）**请先讨论再写代码**，避免写完一大片才发现方向不对。
- 不要直接丢一个几千行的巨型 PR，维护者没有上下文，很难 review。

### 3. 分支

默认分支是 `main`，**所有改动都在功能分支上做，绝不直接提交到默认分支**。从最新的 `main` 切出：

| 前缀 | 用途 | 示例 |
|---|---|---|
| `feature/` | 新功能 | `feature/roundabout-tool` |
| `fix/` | 缺陷修复 | `fix/draw-road-on-release` |
| `perf/` | 渲染 / 仿真性能 | `perf/marker-layer-split` |
| `refactor/` | 不改行为的重构 | `refactor/canvas-host` |
| `docs/` | 文档 | `docs/bilingual-readme` |
| `chore/` | 构建、依赖、模板 | `chore/issue-templates` |

分支名用小写英文 + 连字符，别用中文、空格或 Issue 编号当唯一标识。

### 4. 提交信息

格式：`<type>(<可选范围>): <简短说明>`，说明控制在一行以内，正文写**为什么**而不是复述改了什么。

```
feat(sim): 停止线改用位置约束 v_max = sqrt(2*a*s)

IDM 把停止线当静止前车时，车辆会在离路口约 20 m 处急刹。
改为按剩余距离直接夹速度上限，贴线 (<0.35 m) 时用 HARD_DECEL。
验证：直路 60 km/h 接近红灯，逐帧记录 v-s 曲线无突降。
```

允许的 `type`：`feat` / `fix` / `perf` / `refactor` / `docs` / `chore` / `test` / `audio` / `i18n`。

约定：
- **一个提交 = 一个逻辑改动**。别把格式化、无关重命名和真改动混在一起。
- 涉及仿真规则的提交，请附上验证方式（数据、复现步骤或真机观察结果）。
- 提交里**不要**出现密钥、keystore、密码、令牌、本机绝对路径或他人个人信息。

### 5. 提交前自检（由你本人运行）

本仓库**尚未接入 CI**，所以这一步完全依赖贡献者自觉：

```bash
gradlew.bat assembleDebug      # Windows；macOS / Linux 用 ./gradlew assembleDebug
gradlew.bat lint
gradlew.bat test
```

README 第 5 节有完整的构建说明。请在 PR 的自检清单里写明「已运行、结果通过」，不要把失败留给维护者发现。

真机上建议手动过一遍（这些都是历史上真出过问题的地方）：

- [ ] 进入 / 退出编辑模式，下方按钮换班是否顺滑、无明显卡顿
- [ ] 编辑模式选好路型后**长按拖动 → 抬手**，道路是否真的生成
- [ ] 开始 / 暂停仿真、倍速拖动、昼夜切换
- [ ] 保存 → 重新打开加载 → 删除存档
- [ ] 单指画路、双指缩放 / 平移、点选道路拖动端点
- [ ] 有震动马达的设备上，按钮轻震与拖动档位轻震是否正常

### 6. 架构约束（项目特有，改动时请守住）

| 约束 | 原因 |
|---|---|
| `model/` 是**纯 Kotlin**，不引 Compose / Android 类型 | 仿真引擎要能脱离 UI 单独推演与验证 |
| ViewModel 里不出现 `Color` / `Dp` / `Offset` 等 UI 类型 | 配色与尺寸属于 UI 层 |
| 每帧变化的状态（`frame` / `networkVersion` / `camera.offset`）**只在绘制期读** | 组合期读 `.value` 会让整层界面每帧重组 |
| 画布**十层顺序不许动**；每层都要 `Modifier.graphicsLayer()` | 拆层是渲染性能的基础，合并会让增量重录失效 |
| 给用户看的概率 / 占比文案，必须能对应到抽样函数里那次 `rng.nextDouble()` | 否则设置面板显示的数字和实际行为不一致 |
| 图标只用 Material Icons 的经典名 | 扩展图标里的新名称在不同 BOM 版本下容易 Unresolved |
| 跨线程落盘前，可变元素一律 `copy()` | 仿真在跑的同时 autosave 会序列化同一批对象 |

这些约束背后的推导记录由维护者保留，**未随本仓库分发**；有疑问宁可先开 Issue，不要凭猜测改渲染分层。

### 7. 提交 Pull Request

1. 按 `.github/PULL_REQUEST_TEMPLATE.md` 填写，缺项会被要求补。
2. 有对应 Issue 时写 **`Closes #编号`**，让合并自动关闭它。
3. **一个 PR 一个主题**；还没准备好就开 Draft PR。
4. 审查通过后**由维护者合并**。除非维护者明确要求，请不要自行合并到 `main`。
5. PR 描述、截图、日志里同样不要出现密钥、凭据、本机绝对路径或个人信息。

### 8. 素材与许可证

- 本项目采用 **MIT**（见 `LICENSE`）。你提交的贡献视为同意按同一许可证授权。
- `res/raw/*.wav`（63 个短音效）由 `tools/gen_audio.py` 逐样本合成；**13 个 OGG 音乐 / 环境层素材已由项目作者确认可随本项目再分发**。
- 新增或替换音频 / 图片素材时，请在 PR 里写明**来源与授权**，别让维护者替你猜。

### 9. 安全边界

- Issue / PR / 提交里不要写密钥、keystore、密码、令牌，也不要贴含本机用户名绝对路径的完整日志。
- 模板与文档里的命令只是「可复制的原文」：**执行前自行判断，由你在本机运行**，维护者不会代跑，也不会在 Issue 里要求你运行来源不明的命令。
- 本项目无 INTERNET 权限、无账号体系，若发现涉及网络请求或凭据读取的改动，请直接提出质疑。

---

<a id="english"></a>
## English

Issues and pull requests are welcome. This is a small hobby project, so the process is intentionally light — a few ground rules make review much faster.

> **Read this first**: every build/check command in this document is text for **you** to copy. Run it on your own machine and confirm the result; maintainers do not run builds or tests on your behalf, and won't judge a check as "passed" for you.

### 1. Prerequisites

| Item | Requirement |
|---|---|
| JDK | **17** (`compileOptions` and `jvmTarget` are both 17) |
| IDE | Android Studio (a version that loads AGP 8.5.2) |
| Gradle | Use the bundled **Wrapper** (`gradlew.bat` / `./gradlew`), not a system-wide install |
| SDK | compileSdk / targetSdk **34**, minSdk **26** |
| Device | A real device is preferred — emulators have no vibrator, so haptics can only be checked via logs |

Open `KeepRolling/`, wait for Gradle Sync, then start. If Sync fails, open an issue with the error instead of rewriting build scripts.

### 2. Open an issue first

Use the `🐛 Bug report` template for defects (repro steps + device info) and the `✨ Feature request` template for ideas (scenario first, solution second). Discuss **before** coding anything large — simulation rules, render layer split, or the save format. Avoid giant PRs with no context.

### 3. Branches

The default branch is `main`. **Never commit directly to it**; branch off an up-to-date `main`:

`feature/…` (new feature), `fix/…` (bug), `perf/…` (rendering/simulation), `refactor/…`, `docs/…`, `chore/…`. Lowercase English and hyphens only.

### 4. Commits

Format: `<type>(<optional scope>): <short summary>`, one line for the summary and a body explaining **why**.

```
fix(gestures): treat a single-finger lift as finishing the road, not cancelling it

The finger-count change from 1 -> 0 fired cancelDraw(), so the previewed
road disappeared on release. Only count >= 2 (a second finger joining)
should cancel the in-progress draw.
```

Types: `feat` / `fix` / `perf` / `refactor` / `docs` / `chore` / `test` / `audio` / `i18n`.

One commit = one logical change (don't mix reformatting with real edits). When touching simulation rules, include how you verified it. **Never** put keys, keystores, passwords, tokens, absolute local paths, or personal data in a commit.

### 5. Self-checks (run them yourself)

There is **no CI yet**, so this relies on contributors:

```bash
./gradlew assembleDebug      # Windows: gradlew.bat assembleDebug
./gradlew lint
./gradlew test
```

State "ran and passed" in the PR checklist. On a device, also verify: entering/exiting edit mode (button handoff), drawing a road (press-drag → **release** creates it), start/pause, speed dial, day/night, save → load → delete, pinch/pan/endpoint dragging, and haptics on a device with a vibrator.

### 6. Architecture constraints

| Constraint | Why |
|---|---|
| `model/` stays **pure Kotlin** — no Compose / Android types | The simulation must run and be testable without UI |
| No `Color` / `Dp` / `Offset` in the ViewModel | Those belong to the UI layer |
| Per-frame state (`frame`, `networkVersion`, `camera.offset`) is read **only during draw** | Reading `.value` during composition recomposes the whole UI every frame |
| Keep the **ten canvas layers** in order, each with `Modifier.graphicsLayer()` | The layer split is what makes incremental re-recording work |
| Any probability shown to users must map to an actual `rng.nextDouble()` draw | Otherwise settings lie about behaviour |
| Use classic Material Icons names only | Newer extended-icon names break across BOM versions |
| `copy()` mutable elements before serialising off-thread | Autosave runs while the simulation mutates them |

The reasoning behind these constraints lives in the maintainer's notes and is **not shipped with this repository**. When unsure, open an issue rather than guessing at the render layer split.

### 7. Pull requests

Fill in `.github/PULL_REQUEST_TEMPLATE.md`; use **`Closes #N`** when an issue exists; keep one topic per PR; open a Draft if it's not ready. **Merging into `main` is done by the maintainer** — don't merge your own PR unless explicitly asked. Keep credentials and personal data out of descriptions, screenshots and logs.

### 8. Assets and license

Licensed under **MIT** (see `LICENSE`); contributions are taken to be under the same license. The 63 WAV SFX are synthesised by `tools/gen_audio.py`, and the **13 OGG music/ambience tracks have been confirmed by the project author as redistributable with this project**. When adding or replacing any audio or image asset, state its **source and license** in the PR.

### 9. Security boundary

Never put keys, keystores, passwords, tokens, or logs containing absolute local paths into issues, commits or PRs. Commands in templates and docs are copyable text only — **judge them yourself and run them locally**; maintainers will not run them for you or ask you to execute commands from an untrusted source. This app has no INTERNET permission and no accounts: question any change that introduces network calls or credential reads.
