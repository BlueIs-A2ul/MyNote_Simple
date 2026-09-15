# 新用户欢迎笔记（67）实现计划

**Goal:** 按 `docs/superpowers/specs/2026-09-15-new-user-welcome-note-design.md` 实现新用户欢迎笔记，发布 1.7.2。

**约定：** 中文注释/提交；零新增依赖；无 DB 迁移；不主动 push。

## Task 1：存储与种子器

- 新增 `data/settings/OnboardingStore.kt`：`isWelcomeSeeded()` / `markWelcomeSeeded()`（prefs `onboarding`）。
- 新增 `data/repository/WelcomeNoteSeeder.kt`：`seedIfNeeded()` + 标题/正文常量（正文覆盖笔记、图文、分类、历史、主题、备份导出、AI、权限说明）。
- `NoteDao` 增 `countVisible()`（`deletedAt IS NULL`）。

## Task 2：接线

- `AppContainer`：新增 `onboardingStore`、`welcomeNoteSeeder`。
- `MyNoteApp.onCreate`：`applicationScope.launch { runCatching { seeder.seedIfNeeded() } }`。

## Task 3：测试

- `OnboardingStoreTest`、`WelcomeNoteSeederTest`（用例见设计 §4）。

## Task 4：发布收尾

- 版本 1.7.2（`versionCode 1_07_02`）、README 版本行与测试数、backlog 修订记录、全量单测与 debug/release 构建、提交。

## 修订记录

- 2026-09-15：初版。
- 2026-09-15（实现）：Task 1–4 全部完成；新增 7 个单测；全量 463 全绿，debug/release 构建通过，版本 1.7.2。
