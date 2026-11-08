# 批次 F/G/H 快修（条目 77 / 82 / 83 / 89 / 91）— 设计文档

- 日期：2026-11-08
- 状态：已实现（用户要求「把能修复的先修复」，从 backlog 中挑出「问题严重但改动小」的条目）
- 范围：修复批（补丁版）；无新增依赖、无 DB 迁移、不改存储结构、不动 Room 版本
- 来源：`docs/backlog.md` 条目 77（批次 F 隐私）、82/83（批次 G AI）、89/91（批次 H 搜索/多选）

## 1. 问题与根因

| # | 现象 | 根因 |
|---|---|---|
| 77 | 尚未点保存的明文 API Key 随旋转/进程重建进入 Activity 的 saved instance state，与本页「Key 仅本机 Keystore 加密保存」的承诺相悖 | `SettingsScreen` 的 `apiKeyInput` 用 `rememberSaveable`，Bundle 由系统进程持有且可能被序列化落盘 |
| 82 | 看门狗超时/重发后旧 HTTP 流仍在跑，服务端继续生成、token 照常计费后被丢弃 | 看门狗分支只收尾 UI，未调 `session.stop()`；`AiApiSession.send()` 只 `job.cancel()` 而阻塞中的 `readLine()` 不响应协程取消 |
| 83 | 服务商忽略 `stream:true` 返回整段普通 JSON 时，有效回答被静默丢弃，落库一条 `content=""` 的 `done` 消息且不出现重试按钮 | `AiEvent.Done("")` 直接走 `finalizeAssistant`，无判空 |
| 89 | 搜 `_` / `%` 命中几乎全部笔记却一处都不高亮；字面 `%` 永远搜不准 | 三处 `LIKE '%' \|\| :query \|\| '%'` 无 `ESCAPE`；高亮用的却是未 trim 的原始 query |
| 91 | 搜 a → 选中 A → 改搜 b → 删除，删掉的是用户屏幕上看不见的 A；选中项被别处删除后计数虚高 | `onQueryChange` 不清多选，批处理按 id 重读且不与可见集取交集 |

## 2. 决策

| 决策点 | 结论 | 理由 |
|---|---|---|
| 77 的状态容器 | 改 `remember`（旋转后清空未保存输入）；`keyVisible` 等非敏感 UI 状态仍可 saveable | 明文 Key 是唯一敏感项；已保存的 Key 存在 SharedPreferences（密文），不受影响 |
| 82 的超时语义 | 看门狗超时分支先 `session.stop()` 再收尾 | `stop()` 已同时做「取消协程 + 断连」，与用户主动停止同一路径 |
| 82 的重发语义 | `send()` 在旧 job 仍活跃时先 `streamer.cancel()` 再 `job.cancel()` | 只在确有在跑的上一条流时断连，避免误关已完成的连接（也避免无谓的 cancel 计数） |
| 83 的判定 | `Done` 全文与已收分片都为空 → 按失败收尾：不落库、保留 user 消息、给横幅「回答为空，请重试」 | 与既有「空失败不落库」口径一致；横幅即重试入口的提示 |
| 83 的兜底 | 全文为空但已收分片非空 → 正常 completed 落库（用分片拼接） | 部分服务商在末帧不带全文，这是既有行为，不能被空判空吞掉 |
| 89 的转义 | 仓库层 `escapeLikePattern`：`\` → `\\`、`%` → `\%`、`_` → `\_`（反斜杠最先）；DAO 三处 `LIKE` 补 `ESCAPE '\'` | 零依赖、纯函数可单测；转义点放在仓库层，DAO 仍只接收已转义的 query |
| 89 的高亮口径 | 高亮用 `query.trim()`，与仓库层同口径 | 修掉「尾随空格能搜到却不高亮」 |
| 91 的收敛点 | `onQueryChange`（值变化时）清多选 + `notes` 流用 `onEach` 按可见集裁剪选中集 | 前者堵住「改搜索词后删除看不见的笔记」；后者兜住「笔记被别处删除/移出筛选」 |
| 91 的实现位置 | 裁剪放在 `stateIn` 之前的 `onEach`，不额外起收集协程 | 保持 `WhileSubscribed` 生命周期语义，不常驻订阅 |

## 3. 非目标

- 条目 78（导出/导入脱离 composition 作用域）需要把协程搬到 VM，改动面较大，本批不做。
- 条目 85（流式 Markdown 节流）、84（历史裁剪配对）、86/87（明文 http / 链接 scheme）不做。
- 条目 92（首帧加载态统一）、90（保留期文案）不做。
- 不引入 Compose UI 测试依赖：条目 77 属纯屏幕状态，无法在本仓库现有测试设施下覆盖，靠代码审查 + 真机旋转验证。

## 4. 验收

- 全量单测通过；`assembleDebug` 通过。
- 关键回归用例：
  - `searchTreatsPercentAsLiteral` / `searchTreatsUnderscoreAsLiteral` / `searchTreatsBackslashAsLiteral`
  - `watchdogTimeoutStopsStream`（fake.stopCount == 1）
  - `emptyDoneIsTreatedAsFailureWithoutStoringMessage` / `emptyDoneKeepsStreamedText`
  - `resendCancelsPreviousStream`（cancelCount == 1）
  - `onQueryChangeClearsSelection` / `selectionIsPrunedWhenNoteLeavesList`
