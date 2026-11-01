# AI 助手 system 提示词自定义 实施计划

设计：`docs/superpowers/specs/2026-10-30-ai-custom-prompt-design.md`
分支：`ai-custom-prompt-1030`（worktree `.worktrees/ai-custom-prompt-1030`）

## 步骤

1. **提示词解析**（`data/ai/AiPromptBuilder.kt`）
   - `SYSTEM_PROMPT` 注释改为「内置默认」；新增 `MAX_SYSTEM_PROMPT_CHARS = 2_000` 与 `resolveSystemPrompt(custom)`。
2. **存储**（`data/settings/AiSettingsStore.kt`）
   - 新增 `KEY_SYSTEM_PROMPT = "system_prompt"`（全局不分服务商）、`customSystemPrompt()`、`setCustomSystemPrompt()`（trim + 截断 + 空白清除）。
3. **请求组装**（`data/ai/AiApiMessageBuilder.kt`）
   - `build(...)` 末尾新增默认参数 `systemPrompt`，prepend 用它。
4. **ViewModel 接线**（`ui/ai/AiChatViewModel.kt`）
   - 发送与重试两处传入 `resolveSystemPrompt(settingsStore.customSystemPrompt())`。
5. **设置页状态**（`ui/settings/SettingsViewModel.kt`）
   - `customSystemPrompt` StateFlow + `setSystemPrompt` / `resetSystemPrompt` / `refreshSystemPrompt`。
6. **编辑页**（新增 `ui/settings/PromptEditScreen.kt`）
   - 顶栏「系统提示词」+ 多行输入 + 计数 2000 + 恢复默认（二次确认）+ 内置默认只读展示。
7. **设置页入口**（`ui/settings/SettingsScreen.kt`）
   - AI 段「深度思考」后新增行（摘要 + 编辑）；新增 `onOpenPromptEditor` 参数；返回设置页时 `refreshSystemPrompt()`。
8. **路由**（`ui/navigation/AppNavHost.kt`）
   - 新增 `composable("settings/prompt")`。
9. **单测**
   - store：往返 / trim / 空白清除 / 超长截断 / 全局不分服务商；
   - builder：传入自定义时首条 system 即该文本；不传时仍为内置默认；
   - resolve：空白回退、超长截断；
   - settings VM：保存-读取-恢复默认状态流一致。
10. **验证**
    - `.\.gradlew :app:testDebugUnitTest`（全绿，既有 646 个不得回归）
    - `.\.gradlew :app:assembleDebug`
11. **收尾**
    - 主树 `git merge --ff-only ai-custom-prompt-1030`，删除 worktree 与分支。

## 验证命令

```
.\.gradlew :app:testDebugUnitTest
.\.gradlew :app:assembleDebug
```

## 修订记录

- 2026-10-30 初版。范围冻结：只接管 system 提示词，笔记包装/正文预算/默认文案不动。
