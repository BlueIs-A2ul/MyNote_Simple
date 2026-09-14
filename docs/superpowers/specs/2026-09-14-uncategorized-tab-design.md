# 主页「未分类」筛选入口设计

> 目标版本：1.4.1（patch）｜日期：2026-09-14｜来源：用户需求（主页分类增加「未分类」，便于把未分类笔记整体归类）

## 1. 背景与问题

主页分类 tab 由 `NotesScreen.kt` 的 `listOf<CategoryEntity?>(null) + categories` 生成，用 `null` 同时表达两件事：「全部」这个 tab、以及「没有选中分类」这个状态。

这种「一个 null 担两职」的表示法把「不筛选」占死了，于是 `categoryId IS NULL` 的笔记没有任何专属入口：

- 新建笔记时没选分类 → `categoryId = null`；
- 所属分类被删除 → `NoteRepository.deleteCategory` 调 `noteDao.clearCategory`，把该分类下笔记的 `categoryId` 置空。

两类笔记混在「全部」里，用户想把它们**整体归类**只能一条条点开编辑页改分类。已有的「多选 → 移动到分类」批量能力（backlog #12，v1.4.0）缺的正是「先把未分类笔记筛出来」这一步。

## 2. 目标与非目标

**目标**

1. 主页 tab 增加「未分类」入口，选中后列表只显示 `categoryId IS NULL` 且未删除的笔记。
2. 该 tab 下新建笔记默认未分类（与「全部」tab 一致，不误挂到别处）。
3. 与既有批量操作闭环：进入「未分类」→ 长按 → 全选 → 移动到分类，即可整体归类。
4. 空态文案区分场景，避免「未分类没有笔记」时显示「还没有笔记 / 写第一条」造成误解。
5. 顺带修掉一个既有缺陷：所选分类被删除后筛选条件悬空（列表恒为空、且「全部」被高亮）。

**非目标（本次不做）**

- 不在「未分类」tab 上做笔记数量徽标（其余 tab 都没有计数，保持视觉一致）。
- 不让「未分类」/分类 tab 支持排序模式切换：现状 `observeByCategory` 固定 `pinned DESC, updatedAt DESC`，排序菜单也只在「全部」tab 可用；本次保持同一套语义，不新增 6 条排序查询。
- 不改 Room schema（`categoryId IS NULL` 是既有列上的过滤条件，无需迁移）。

## 3. 方案

### 3.1 用显式筛选类型替换「null 双关」

新增 `ui/notes/CategoryFilter.kt`（纯 Kotlin，可脱离 Android 测试）：

```kotlin
sealed interface CategoryFilter {
    data object All : CategoryFilter                 // 全部
    data object Uncategorized : CategoryFilter       // 未分类（categoryId IS NULL）
    data class Single(val categoryId: Long) : CategoryFilter  // 某个分类
}
```

`NotesViewModel` 把 `selectedCategoryId: MutableStateFlow<Long?>` 换成 `selectedFilter: MutableStateFlow<CategoryFilter>`，分流逻辑：

```kotlin
val notes = combine(query, selectedFilter, sortMode) { q, filter, _ -> q to filter }
    .flatMapLatest { (q, filter) ->
        when {
            q.isNotBlank() -> repository.search(q)                       // 搜索优先，作用于全部
            filter is CategoryFilter.Single -> repository.observeByCategory(filter.categoryId)
            filter is CategoryFilter.Uncategorized -> repository.observeUncategorized()
            else -> repository.observeNotes(sortMode.value)
        }
    }
```

搜索优先级不变（搜索时忽略 tab），排序模式仍只作用于「全部」。

派生属性给「新建笔记」用，避免 UI 端再做模式判断：

```kotlin
/** 新建笔记的预选分类：仅「某个分类」下有值。 */
val newNoteCategoryId: Long?
    get() = (selectedFilter.value as? CategoryFilter.Single)?.categoryId
```

### 3.2 数据层

`NoteDao` 增加一条查询，排序与 `observeByCategory` 完全一致（置顶优先 + 更新时间倒序）：

```kotlin
@Query("SELECT * FROM notes WHERE deletedAt IS NULL AND categoryId IS NULL ORDER BY pinned DESC, updatedAt DESC")
fun observeUncategorized(): Flow<List<NoteEntity>>
```

`NoteRepository.observeUncategorized()` 直接转发。

### 3.3 UI

- tab 列表：`listOf(CategoryFilter.All, CategoryFilter.Uncategorized) + categories.map { CategoryFilter.Single(it.id) }`；
  标签：`All → 「全部」`、`Uncategorized → 「未分类」`、`Single → 分类名`。
- 「未分类」tab 常驻显示（不做条件隐藏）：若按「有未分类笔记才显示」处理，用户在该 tab 下把最后一条归好类后 tab 会消失、选中态悬空，反而更困惑。
- 排序菜单：`sortEnabled = selectedFilter is CategoryFilter.All && query.isBlank()`（与原「仅全部 tab 可用」一致）。
- 行内分类名：仍只在整个「全部」tab 且非搜索态显示（「未分类」tab 下所有行都无分类，显示无意义）。
- 空态文案分场景：
  - 搜索中 → 「没有匹配的笔记」（无动作按钮）
  - 未分类 → 「没有未分类的笔记」+「写第一条」
  - 某分类 → 「这个分类还没有笔记」+「写第一条」
  - 全部 → 「还没有笔记」+「写第一条」
- 修正悬空筛选：分类被删除后，若当前筛选指向不存在的分类，自动回落到「全部」。

```kotlin
// 分类列表首次非空后才做校验，避免 categories 初始 emptyList() 误判
var categoriesLoaded by remember { mutableStateOf(false) }
LaunchedEffect(categories) {
    if (categories.isNotEmpty()) categoriesLoaded = true
    val f = selectedFilter
    if (categoriesLoaded && f is CategoryFilter.Single && categories.none { it.id == f.categoryId }) {
        viewModel.onFilterSelect(CategoryFilter.All)
    }
}
```

## 4. 验收标准

1. 「未分类」tab 只列出 `categoryId IS NULL` 且 `deletedAt IS NULL` 的笔记；置顶优先、其次更新时间倒序。
2. 该 tab 下点 FAB / 空态按钮新建，落库 `categoryId = null`（导航参数仍用 `-1L` 哨兵）。
3. 多选「全选」只选中当前 tab 可见笔记；批量移动到某分类后，这些笔记从「未分类」列表消失。
4. 搜索时忽略 tab（与现状一致）；「未分类」tab 下排序菜单不可用。
5. 删除当前选中的分类后，筛选自动回到「全部」，不再出现「列表空 + 全部高亮」。
6. 全量单测通过；版本 1.4.1、README 版本行与产物名同步。

## 5. 影响面与风险

| 文件 | 改动 |
|---|---|
| `ui/notes/CategoryFilter.kt` | 新增：筛选类型 |
| `data/db/NoteDao.kt` | 新增 `observeUncategorized()` |
| `data/repository/NoteRepository.kt` | 新增 `observeUncategorized()` 转发 |
| `ui/notes/NotesViewModel.kt` | `selectedCategoryId` → `selectedFilter`、`newNoteCategoryId` |
| `ui/notes/NotesScreen.kt` | tab 构造/标签、排序可用性、空态文案、悬空筛选回落 |

风险点：

- `onCategorySelect(id: Long?)` 被 `onFilterSelect(filter)` 取代，`NotesViewModel` 对外 API 变化 → 编译期即可暴露，无遗留调用方（全仓仅 `NotesScreen` 一处调用）。
- 未分类判定依赖 `categoryId IS NULL`；历史快照里 `NoteRevisionEntity.categoryId` 可为 null 但不参与列表筛选，无需处理。
- 备份导入的笔记若 `categoryId` 指向本地不存在的分类，现状不会被清理，本次不改（超出范围）。
