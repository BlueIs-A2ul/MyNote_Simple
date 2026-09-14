# MyNote 主页「未分类」筛选入口实施计划

> **For agentic workers:** 配套设计文档 `docs/superpowers/specs/2026-09-14-uncategorized-tab-design.md`。改动是单一链路（DAO → Repository → ViewModel → Screen），文件耦合紧、总量小，由主线程串行实现，不拆分并行代理。步骤使用 `- [ ]` 跟踪。

**Goal:** 主页分类 tab 增加「未分类」入口，支持查看并整体归类所有未分类笔记；发布 1.4.1。

**Architecture:** 新增纯 Kotlin 类型 `CategoryFilter`（All / Uncategorized / Single）替代「`null` 兼表全部」的旧表示法；DAO 增加 `categoryId IS NULL` 查询；ViewModel 持有筛选状态并分流数据源；Screen 据此渲染 tab、空态与排序可用性。

**Tech Stack:** Kotlin、Compose Material3、Room(KSP)、Robolectric/JUnit4。无新依赖、无新权限、无 Room 迁移。

---

### Task 1: 数据层

**Files:** Modify `app/src/main/java/com/mynote/app/data/db/NoteDao.kt`、`app/src/main/java/com/mynote/app/data/repository/NoteRepository.kt`

- [ ] `NoteDao` 增加未分类查询（排序与 `observeByCategory` 一致）：
```kotlin
/** 未分类笔记（categoryId 为空）：排序与 observeByCategory 保持一致。 */
@Query("SELECT * FROM notes WHERE deletedAt IS NULL AND categoryId IS NULL ORDER BY pinned DESC, updatedAt DESC")
fun observeUncategorized(): Flow<List<NoteEntity>>
```
- [ ] `NoteRepository` 增加转发：
```kotlin
fun observeUncategorized(): Flow<List<NoteEntity>> = noteDao.observeUncategorized()
```

### Task 2: 筛选类型

**Files:** Create `app/src/main/java/com/mynote/app/ui/notes/CategoryFilter.kt`

- [ ] 新增 sealed interface（`All` / `Uncategorized` / `Single(categoryId)`），带中文 KDoc 说明「未分类」= `categoryId IS NULL`。

### Task 3: NotesViewModel

**Files:** Modify `app/src/main/java/com/mynote/app/ui/notes/NotesViewModel.kt`

- [ ] `selectedCategoryId: MutableStateFlow<Long?>` → `selectedFilter: MutableStateFlow<CategoryFilter>`，初值 `CategoryFilter.All`。
- [ ] `onCategorySelect(id: Long?)` → `onFilterSelect(filter: CategoryFilter)`。
- [ ] `notes` 的 `combine`/`flatMapLatest` 改为按 `CategoryFilter` 分流（搜索优先 → Single → Uncategorized → All 带排序）。
- [ ] 增加 `val newNoteCategoryId: Long?`，供 FAB / 空态按钮传参。

### Task 4: NotesScreen

**Files:** Modify `app/src/main/java/com/mynote/app/ui/notes/NotesScreen.kt`

- [ ] `selectedCategoryId` 收集 → `selectedFilter`；删除 `selectedTab`（改用 `CategoryFilter` 等价比较）。
- [ ] tab 列表与标签：`All →「全部」`、`Uncategorized →「未分类」`、`Single → 分类名`；`onSelect = viewModel::onFilterSelect`。
- [ ] FAB 与空态动作改用 `viewModel.newNoteCategoryId`。
- [ ] 排序可用性：`sortEnabled = selectedFilter is CategoryFilter.All && query.isBlank()`。
- [ ] 行内分类名：条件由 `selectedCategoryId == null && query.isBlank()` 改为 `selectedFilter is CategoryFilter.All && query.isBlank()`。
- [ ] 空态文案分场景（搜索 / 未分类 / 分类 / 全部）。
- [ ] 悬空筛选回落：`LaunchedEffect(categories)` + `categoriesLoaded` 守卫，分类被删后回到「全部」。

### Task 5: 单测

**Files:** Modify `app/src/test/java/com/mynote/app/data/db/NoteDaoTest.kt`、`app/src/test/java/com/mynote/app/ui/notes/NotesViewModelTest.kt`

- [ ] `NoteDaoTest`：
  - [ ] `observeUncategorizedReturnsOnlyNotesWithoutCategory`（含分类的、未分类的、软删除的未分类笔记三种数据，只返回未分类且未删除的）
  - [ ] `observeUncategorizedOrdersPinnedFirst`（置顶优先，其次更新时间倒序）
- [ ] `NotesViewModelTest`：
  - [ ] `filterAllObservesAllNotes`
  - [ ] `filterUncategorizedObservesOnlyUncategorizedNotes`
  - [ ] `filterSingleObservesOnlyThatCategoryNotes`
  - [ ] `newNoteCategoryIdFollowsFilter`（All/Uncategorized → null；Single → id）
- [ ] 说明：tab 文案、空态文案、悬空回落属纯 UI 行为，按既有约定不加 Robolectric 组合测试。

### Task 6: 版本与文档

**Files:** Modify `app/build.gradle.kts`、`README.md`、`docs/backlog.md`

- [ ] `appVersionName = "1.4.1"`、`appVersionCode = 1_04_01`。
- [ ] README：版本行 1.4.1、单测数量、release 产物名 `MyNote-1.4.1-release.apk`；「功能」小节分类一行补充「未分类」入口说明。
- [ ] `docs/backlog.md`：新增条目 16「主页未分类筛选入口」并置为已完成（v1.4.1）。

### Task 7: 验证

- [ ] `.\gradlew :app:testDebugUnitTest` 全量通过（新用例计入总数）。
- [ ] `.\gradlew :app:assembleRelease` 产出 `MyNote-1.4.1-release.apk`。
- [ ] `.\gradlew :app:assembleDebug` 通过（编译无警告回归）。

---

## 修订记录

- 2026-09-14 计划创建。用户指定本次版本号为 1.4.1（patch），与 AGENTS.md「功能递增 minor」的默认规则不同，以用户指定为准。
- 2026-09-14 执行无偏差。补充说明两点实现细节：`CategoryFilter.kt` 实际落在 `ui/notes/` 包内（与 `NoteUndoController.kt` 同级）；新增 `NotesViewModelTest.filterUncategorizedIgnoresDeletedNotes` 一条，覆盖软删除笔记不出现在未分类列表（原计划只有 DAO 层覆盖）。
