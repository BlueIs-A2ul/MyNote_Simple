# 主页分类筛选记忆实施计划

> 设计：`docs/superpowers/specs/2026-11-26-home-filter-memory-design.md`。目标版本 1.14.1。

## 分支与隔离

- 分支 `note-filter-memory-1126`，worktree `.worktrees/note-filter-memory-1126`（已建，签名文件已复制）。

## 任务

- [x] 1. 编解码：`CategoryFilter.encodeToken()` / `decodeCategoryFilter(token)`，token 常量集中定义；写 `CategoryFilterCodecTest`（含脏值表）。
- [x] 2. 存储：`data/settings/NoteFilterStore.kt`（`note_filter_settings` / `note_filter`）；写 `NoteFilterStoreTest`。
- [x] 3. ViewModel：注入 `filterStore`、初值改为解码结果、`onFilterSelect` 写回、`init` 一次性校验已删除分类；更新 `factory`。
- [x] 4. 接线：`AppContainer.noteFilterStore` → `AppNavHost` factory 传参。
- [x] 5. 测试：`NotesViewModelTest` 用例前复位偏好；新增「冷启动恢复」「选择即持久化」「已删分类回落」「已存分类不被误清」四个用例。
- [x] 6. 版本与文档：`app/build.gradle.kts` 1.14.1 / 11401；README 版本行、APK 名、测试数；AGENTS.md 测试数。
- [x] 7. 验证：`:app:testDebugUnitTest` 全绿 → `:app:assembleDebug` 通过 → 主树 `git merge --ff-only` 收尾。

## 修订记录

- 2026-11-26：初版（7 项任务）。
- 2026-11-26：实现完成。全量单测 740 全绿（新增 13：编解码 6 / 存储 3 / VM 4），`assembleDebug` 通过；本机无连接设备与 AVD，未做手工重启走查（以「构造即恢复」的单测替代）。实现中的一处取舍：启动校验从界面挪到 ViewModel 的一次性查询，因为界面用「分类列表非空」判加载完成，分类被删到一个不剩时会漏判；另一个改动是给 `NoteViewModelTest.setup` 补筛选偏好复位，避免用例顺序依赖。
