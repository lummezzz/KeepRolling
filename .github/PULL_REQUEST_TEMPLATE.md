## 改动说明 / What changed

<!-- 一段话说清改了什么；超过一个主题请拆成多个 PR -->

## 为什么改 / Why

<!-- 根因、症状、或要解决的问题。仿真规则类改动请附上验证方式 -->

## 关联 Issue / Related issue

<!-- 有关联 Issue 时用 `Closes #编号`，合并后会自动关闭；没有就写「无」 -->

Closes #

## 改动类型 / Type of change

- [ ] 🐛 Bug 修复 / Bug fix
- [ ] ✨ 新功能 / New feature
- [ ] ⚡ 渲染或仿真性能 / Performance
- [ ] ♻️ 重构（不改行为）/ Refactor
- [ ] 📝 文档 / Docs
- [ ] 🔧 构建、依赖或模板 / Chore
- [ ] 🔊 音频 / 触觉 / Audio & haptics
- [ ] 🌐 国际化文案 / i18n

## 自检清单 / Checklist

> 本仓库**尚未接入 CI**。以下命令请**你本人**在本机运行并确认结果；维护者不会代跑构建或测试。

- [ ] 已运行 `gradlew.bat assembleDebug`（macOS / Linux 为 `./gradlew assembleDebug`），结果通过
- [ ] 已运行 `gradlew.bat lint` 与 `gradlew.bat test`，结果通过
- [ ] 已在真机或模拟器上手动验证过受影响的交互
- [ ] 模型层仍是纯 Kotlin，未引入 Compose / Android 类型
- [ ] 未在组合期读取每帧变化的 State；画布十层顺序未改动
- [ ] 新增 / 修改的文案已做中英双语
- [ ] 一个提交对应一个逻辑改动，提交信息写清了「改了什么、为什么」

## 真机验证 / How verified

<!-- 设备、Android 版本、具体操作步骤与观察结果 -->

## 截图 / Screenshots

<!-- 界面类改动建议附前后对比；渲染类改动必附 -->

| 修改前 | 修改后 |
|---|---|
| | |

## 素材与授权 / Assets & licensing

- [ ] 本次改动**未**新增音频 / 图片素材
- [ ] 或：已在下方写明新增素材的**来源与授权**

<!-- 素材来源、授权说明（没有就留空） -->

## 安全确认 / Security

- [ ] PR 描述、代码、提交信息、日志与截图中**未**包含密钥、keystore、密码、令牌、本机绝对路径或个人信息
- [ ] 未引入网络请求或凭据读取（本项目无 INTERNET 权限、无账号体系）

---

> 📌 审查通过后**由维护者合并**。除非维护者明确要求，请不要自行合并到 `main`。
> 完整流程见 [`CONTRIBUTING.md`](/CONTRIBUTING.md)。
