# 主页分类筛选记忆设计文档

> 用户需求：主页左上角抽屉选择的分类筛选（全部 / 未分类 / 某个分类），退出应用再打开总会回到「全部」，希望能记住上次的选择。目标版本 **1.14.1**（行为微调，patch 递增）。

## 背景与目标

现状：筛选项存在 ViewModel 内存里（`NotesViewModel._selectedFilter`，初值 `CategoryFilter.All`）。ViewModel 挂在 NotesRoute 的返回栈条目上，因此**应用内**跳转（编辑笔记、设置、回收站、日历）本来就会保留筛选；真正丢失的只有两种时机：

| 场景 | 现状 | 目标 |
|---|---|---|
| 应用内跳转返回 | 保留 | 保持 |
| 旋转屏幕 | 保留 | 保持 |
| 退出应用重进 / 后台被系统回收 | 回到「全部」 | 恢复上次选择 |
| 上次选的分类已被删除 | —（重启后本来就回「全部」） | 静默回「全部」 |
| 上次选的分类在重启后仍存在 | — | 直接显示，不闪一下「全部」 |

目标：只加「最后一次选择」这一个持久化值，不改变筛选语义，不新增设置项，不新增依赖。

## 存储设计

新增 `data/settings/NoteFilterStore.kt`，与 `NoteSortStore` 同构（SharedPreferences + 单键）：

- 命名空间：`note_filter_settings`
- 键：`note_filter`
- 值：由 `CategoryFilter.encodeToken()` 生成的字符串

| 筛选 | token |
|---|---|
| 全部 | `all` |
| 未分类 | `uncategorized` |
| 分类 id = 42 | `category:42` |

解码 `decodeCategoryFilter(token)` 对 null、空串、未知前缀、非法/非正 id 一律回落 `All`——**任何脏值都不能让主页卡在空列表**。token 里只存 id 不存分类名，分类改名后记忆自然跟着新名字走。

分层取舍：store 只处理字符串，`CategoryFilter` 属于 ui 层，编解码放在 `ui/notes/CategoryFilter.kt`，避免 data → ui 的反向依赖（data 层现有的 `NoteSortStore` 依赖 `data.db.NoteSortMode`，方向是 data → data，不构成先例）。

## 回落规则（静默，无提示）

1. **解码失败**：直接 `All`。
2. **分类已不存在**：ViewModel 构造时用一次性查询 `repository.observeCategories().first()` 校验记住的 id；不存在则 `onFilterSelect(All)` 并覆写存储。之所以放 ViewModel 而不是继续用界面里的 `LaunchedEffect`：界面那段判断以「分类列表非空」作为「已加载」信号，若用户只留一个分类并把它删了，列表会一直为空、校验永远不成立，重启后会卡在「这个分类还没有笔记」。ViewModel 的一次性查询能拿到确切的空列表。
3. **运行中删除**（界面还在）：沿用 `NotesScreen` 现有 `LaunchedEffect(categories)` 判断，命中即 `onFilterSelect(All)`，于是存储同步被覆写成 `all`。

不做 Snackbar 提示（用户明确要求静默）；顶栏副标题本就显示当前筛选（`全部笔记` / 分类名），空态文案也区分「这个分类还没有笔记」，不会让人误以为笔记丢了。

## 技术方案

- `NoteFilterStore(context)`：`read(): String?` + `write(token: String)`（`apply()` 异步落盘）。不做 StateFlow：只有 `NotesViewModel` 一个写入方，且只在构造时读一次。
- `NotesViewModel` 构造参数新增 `filterStore`（放在 `sortStore` 之后），`_selectedFilter` 初值改为 `decodeCategoryFilter(filterStore.read())`；`onFilterSelect` 内写回 token。
- 构造时的一次性校验在 `init` 的 `viewModelScope.launch` 里，仅在恢复值是 `Single` 时才查库；查询失败（`runCatching`）保持原值，不因校验失败改变用户选择。
- `di/AppContainer` 增加 `noteFilterStore`；`AppNavHost` 传给 `NotesViewModel.factory`。
- 不碰 Room（无表、无列、无迁移、schema 版本不变）、不新增依赖与权限、不动设计语言（无新 UI）。
- 与排序偏好的关系：两者独立存储。设置页的「列表默认排序」改的是排序，不影响筛选记忆。

## 涉及文件

- 新：`data/settings/NoteFilterStore.kt`、`app/src/test/java/com/mynote/app/data/settings/NoteFilterStoreTest.kt`、`app/src/test/java/com/mynote/app/ui/notes/CategoryFilterCodecTest.kt`、本设计文档与配套计划。
- 改：`ui/notes/CategoryFilter.kt`（编解码）、`ui/notes/NotesViewModel.kt`（注入、恢复、写回、启动校验）、`di/AppContainer.kt`、`ui/navigation/AppNavHost.kt`、`app/src/test/java/com/mynote/app/ui/notes/NotesViewModelTest.kt`（新增用例 + 复位偏好）、`app/build.gradle.kts`（版本）、`README.md` / `AGENTS.md`（版本行、测试数）。

## 测试要点

1. 编解码：三种筛选往返一致；token 字面量固定（改格式等于让老用户丢记忆，用断言锁住）；null / 空串 / `unknown` / `category:` / `category:abc` / `category:-3` / `category:0` 全部回落 `All`。
2. `NoteFilterStore`：默认读到 null；写入后新实例能读到（跨实例持久化）。
3. `NotesViewModel`：预置 token 后构造即恢复对应筛选；`onFilterSelect` 后新实例能读到同一筛选；记住的分类已不存在时启动校验回落到 `All` 并覆写存储；记住的分类存在时启动校验不改动。
4. 回归：既有 `NotesViewModelTest` 全部用例（每个用例前复位筛选偏好，避免顺序依赖）。

## 版本

`1.14.0` → **1.14.1**（`versionCode` 11401）。

## 修订记录

- 2026-11-26：初版。确认「都记忆」（含未分类）、「静默回落」、「patch 递增」三点；启动校验放 ViewModel 而非界面，原因是界面用「列表非空」判加载完成，分类全删时会漏判。
