# 新用户欢迎笔记（67）— 设计文档

- 日期：2026-09-15
- 状态：设计已确认（用户需求原文："默认在新用户的主页添加一个笔记来介绍这个软件的主要功能"），实现中
- 关联：backlog 67、`README.md` 功能列表（欢迎笔记内容需与之一致）
- 目标：新用户首次进入主页即看到一篇功能导览笔记；零新增依赖、无 DB 迁移、不打扰老用户。

## 1. 触发时机与幂等

- 一次性标记存于 `data/settings/OnboardingStore`（SharedPreferences `onboarding`，key `welcome_seeded`）。
- 应用启动时（`MyNoteApp.onCreate`）在 `applicationScope` 异步执行 `WelcomeNoteSeeder.seedIfNeeded()`：
  1. 已标记 → 直接返回；
  2. 主页存在可见笔记（`deletedAt IS NULL` 的 count > 0）→ 只写标记，不建笔记（**老用户升级不受影响**）；
  3. 否则用 `NoteRepository.saveNote` 落一篇欢迎笔记，再写标记。
- 标记在成功路径之后写入：插入异常时下次启动重试；用户删除欢迎笔记后不会再次生成。
- 仅在"主页可见笔记为空"时触发；回收站里有笔记但主页为空的新用户仍会得到欢迎笔记（空主页正是需要引导的场景）。

## 2. 内容

- 标题：`欢迎使用 MyNote`。
- 正文纯文本，按功能分段（笔记与搜索 / 图文混排 / 分类 / 历史 / 主题 / 备份与导出 / AI 助手 / 权限说明），与 README 功能列表保持同一口径；提示可随时删除。
- AI 段落明确：需在「设置 → AI 助手」填写自己的 DeepSeek API Key，回答可插入 / 替换 / 复制 / 存为新笔记。
- 正文常量集中在 `WelcomeNoteSeeder`，便于随版本更新文案（已发放的欢迎笔记不回写）。

## 3. 数据与接线

- `NoteDao.countVisible(): Int`（`SELECT COUNT(*) WHERE deletedAt IS NULL`，不读正文）。
- `AppContainer` 暴露 `onboardingStore` 与 `welcomeNoteSeeder`。
- `MyNoteApp.onCreate` 启动协程调用，异常静默（不阻塞启动、不崩溃）。

## 4. 测试策略

- `OnboardingStoreTest`（Robolectric）：默认 false、写入后 true、跨实例持久。
- `WelcomeNoteSeederTest`（Robolectric + 内存 Room）：
  1. 空库 → 创建欢迎笔记（标题与关键内容断言）；
  2. 重复调用 → 不重复创建；
  3. 已有可见笔记 → 不创建但写标记（二次删除后也不补建）；
  4. 已标记 + 空库 → 不创建；
  5. 仅回收站有笔记 → 创建（countVisible 语义）。
- 全量单测 + debug/release 构建；版本 1.7.2。

## 5. 风险

| 风险 | 缓解 |
|---|---|
| 老用户升级被塞笔记 | 仅"可见笔记为空"时创建；有笔记只写标记 |
| 启动写入异常影响启动 | 协程静默执行，异常不外抛 |
| 欢迎文案与功能脱节 | 文案集中在 seeder，随版本演进；README 为口径基准 |

## 修订记录

- 2026-09-15：初版。
- 2026-09-15（实现）：`OnboardingStore` + `WelcomeNoteSeeder` + `NoteDao.countVisible()` + `MyNoteApp` 启动调用完成；单测 7 个（存储 2 / 种子器 5）；全量 463 全绿，版本 1.7.2。
