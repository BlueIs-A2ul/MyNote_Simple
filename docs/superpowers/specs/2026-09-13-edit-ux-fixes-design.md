# 编辑页体验修复（未保存提醒 / 分类预选 / 键盘遮挡）— 设计文档

- 日期：2026-09-13
- 状态：已实现（2026-09-13，216 个单测全绿 + assembleDebug 通过；真机 UI 手工验证待做）
- 关联：`docs/superpowers/specs/2026-09-10-edit-page-add-category-design.md`（分类选择）、`docs/superpowers/specs/2026-09-11-paper-ui-redesign-design.md`（编辑页布局）

## 1. 背景与问题

用户实测反馈三个编辑页体验问题，逐一核对代码后全部确认存在：

1. **未保存直接退出**：编辑页返回（顶栏返回键或系统返回手势/按键）直接 `popBackStack()`（`AppNavHost.kt:71`），无 `BackHandler` 拦截（grep 确认仅 AI 页、历史页有）。内容只由顶栏「保存」按钮写入，返回即丢。
2. **分类页新建笔记未预选分类**：`NotesScreen` 的 FAB 与空态「写第一条」统一调 `onNewNote`（`AppNavHost.kt:38`），路由 `edit/new` 不携带当前选中分类；`NoteEditScreen` 无初始分类参数，`selectedCategoryId` 恒初始为 `null`。用户在某分类下新建，分类为空，体验割裂。
3. **键盘遮挡正文**：`NoteEditScreen.kt:302` 已写 `.imePadding()`，但 `MainActivity` 未调用 `enableEdgeToEdge()`（非 edge-to-edge 状态下 `WindowInsets` 的 ime insets 不派发给内容），Manifest 也未声明 `android:windowSoftInputMode`。因此 `imePadding()` 实际拿不到键盘高度，长文写作时输入法直接盖住正文下半部分。

## 2. 目标与非目标

**目标**

- 编辑页存在未保存变更时，任何返回路径（顶栏返回、系统返回）先弹确认框：保存并退出 / 不保存 / 取消。
- 在某分类筛选下新建笔记（FAB 或空态按钮），编辑页自动预选该分类。
- 键盘弹出时编辑正文区域自动让位，光标始终可见；同根源的 AI 聊天输入框与首页搜索列表一并处理，不因 edge-to-edge 改造产生回归。

**非目标（本次不做）**

- 自动保存 / 草稿箱：仍保持「手动保存」的既有产品语义，只加防丢失提醒。
- AI 页网页模式（WebView）内的输入让位：网页自身滚动，本次不处理。
- 编辑页「保存并退出」之外的其他退出路径（如删除笔记对话框）复用确认：删除本身已有确认框，语义足够。

## 3. 交互设计

### 3.1 未保存变更提醒

- 「有未保存变更」判定（`NoteEditScreen` 内派生状态）：
  - 新建笔记：标题/正文非空，或分类/置顶相对进入编辑页时的初始状态有变化（分类页带入的预选分类本身不算变更，直接退出不打扰）；初始分类为 `initialCategoryId`（来自路由参数）。
  - 已有笔记（已加载）：标题/正文/分类/置顶与已保存值任一不同；笔记尚未加载完成时，只要标题或正文非空即视为有变更（保守防丢）。
- 返回拦截：`BackHandler` 始终启用——有变更时弹确认框，无变更时直接 `onBack()`；顶栏返回键走同一判断。
- 确认框（复用 `PaperAlertDialog`）：
  - 标题「尚未保存的更改」，正文「当前内容尚未保存，要保存后再退出吗？」
  - 取消：关闭对话框，留在编辑页。
  - 不保存：直接退出，丢弃未保存内容。
  - 保存并退出：走与顶栏「保存」完全相同的 `vm.save` 流程，成功后退出；触发历史条数警告时先展示 Snackbar 再退出（与现有保存按钮行为一致）。
- 对话框弹出期间按系统返回：由 Dialog 自身消费（关掉对话框），编辑页的 `BackHandler` 不二次拦截，行为与其它确认框一致。

### 3.2 分类预选

- `NotesScreen.onNewNote` 由 `() -> Unit` 改为 `(Long?) -> Unit`，FAB 与空态按钮都传当前 `selectedCategoryId`。
- 编辑页路由由 `edit/{noteId}` 扩为 `edit/{noteId}?categoryId={categoryId}`，`categoryId` 为可选长整型参数，缺省 `-1`（无预选）。
- `NoteEditScreen` 新增 `initialCategoryId: Long?` 参数，`selectedCategoryId` 初始值取它；已有笔记仍由 `LaunchedEffect(note)` 用库中值回填（现有逻辑不变）。
- 分类页新建后保存，笔记即落入当前分类；「全部」页新建仍为未分类，行为不变。

### 3.3 键盘遮挡

- `MainActivity` 在 `setContent` 内按应用主题调用 `enableEdgeToEdge()`（状态栏/导航栏透明，深色检测跟随应用内 DarkMode 设置而非仅系统配置），使 Compose 能收到系统栏与 IME insets。
- Manifest 的 `MainActivity` 声明 `android:windowSoftInputMode="adjustResize"`：edge-to-edge 下键盘高度以 insets 形式派发，`imePadding()` 生效，`BasicTextField` 自动保持光标可见。
- 既有 `.imePadding()` 保留；`NotesScreen` 内容列与 `AiChatScreen` 聊天层补 `.imePadding()`，避免 edge-to-edge 后键盘盖住搜索列表 / 聊天输入框。

## 4. 技术设计

### 4.1 脏检查纯函数（`NoteEditScreen.kt`）

顶层 internal 函数，便于单测：

```kotlin
internal fun hasUnsavedChanges(
    isNew: Boolean,
    saved: NoteEntity?,
    initialCategoryId: Long?,
    title: String,
    content: String,
    categoryId: Long?,
    pinned: Boolean
): Boolean
```

判定逻辑：

- `isNew`：`title.isNotBlank() || content.isNotBlank() || categoryId != initialCategoryId || pinned`（分类页带入的预选分类 = 初始值，不算变更）。
- `saved == null`（已有笔记但未加载完）：`title.isNotBlank() || content.isNotBlank()`。
- 其余：与 `saved.title/content/categoryId/pinned` 逐项比较。

### 4.2 编辑页（`NoteEditScreen.kt`）

- 新参数 `initialCategoryId: Long?`；`selectedCategoryId` 改为 `rememberSaveable(noteId) { mutableStateOf(initialCategoryId) }`。
- 新增 `showUnsavedDialog` 状态；`val dirty = hasUnsavedChanges(...)`。
- 新增 `BackHandler(enabled = true) { if (dirty) showUnsavedDialog = true else onBack() }`。
- 顶栏 `onBack` 包装为同一判断。
- 抽出 `saveAndExit()`（`vm.save` 成功后退出，警告先 Snackbar），顶栏「保存」与对话框「保存并退出」共用。
- 确认框：`dismissButton` 槽放「取消」，`confirmButton` 槽放 Row（「不保存」+「保存并退出」）。

### 4.3 导航（`AppNavHost.kt` / `NotesScreen.kt`）

- `composable("edit/{noteId}?categoryId={categoryId}", arguments = listOf(navArgument("categoryId") { type = NavType.LongType; defaultValue = -1L }))`。
- `onNewNote = { catId -> navController.navigate("edit/new?categoryId=${catId ?: -1L}") }`；`NoteEditScreen` 传入 `backStack.arguments?.getLong("categoryId")?.takeIf { it >= 0 }`。
- `onOpenNote` 路径不变（不携带分类，缺省 -1 → null）。
- `NotesScreen`：FAB 与空态按钮传 `selectedCategoryId`。

### 4.4 窗口与输入法（`MainActivity.kt` / `AndroidManifest.xml`）

- `MainActivity`：`LaunchedEffect(darkTheme)` 内调用 `enableEdgeToEdge(statusBarStyle = SystemBarStyle.auto(透明, 透明) { darkTheme }, navigationBarStyle = SystemBarStyle.auto(浅色遮罩, 深色遮罩) { darkTheme })`，`darkTheme` 取自 `ThemeSettingsStore`（SYSTEM/LIGHT/DARK 三态），设置变化时自动重应用。
- Manifest：`MainActivity` 加 `android:windowSoftInputMode="adjustResize"`。
- `AiChatScreen.ChatLayer` 的根 `Column`/`Surface` 加 `.imePadding()`；`NotesScreen` 内容 `Column` 加 `.imePadding()`。
- 依赖：`enableEdgeToEdge` / `SystemBarStyle` 来自既有 `activity-compose 1.9.2`，零新增第三方依赖。

### 4.5 数据流

```
编辑页按返回 → hasUnsavedChanges ? 
  否 → onBack()
  是 → 确认框
      取消 → 留在编辑页
      不保存 → onBack()
      保存并退出 → vm.save → (历史警告 Snackbar) → onBack()

分类页 FAB/空态 → onNewNote(selectedCategoryId)
  → edit/new?categoryId=N → selectedCategoryId 初始 = N → 保存时写入 categoryId

键盘弹出 → (edge-to-edge + adjustResize) ime insets → imePadding → 编辑区收缩、光标可见
```

## 5. 边界与错误处理

| 场景 | 行为 |
|---|---|
| 新建笔记全空直接返回 | `hasUnsavedChanges=false`，静默退出（不打扰） |
| 新建笔记仅带分类页预选分类（未输入任何内容）直接返回 | 预选分类 = 初始分类，视为无变更，静默退出 |
| 新建笔记切换/清除了预选分类 | 相对初始分类有变化，返回时提示 |
| 只输入空白标题/正文 | 视为无内容，静默退出 |
| 已有笔记数据尚未加载完就返回 | 标题/正文非空按有变更处理；全空静默退出 |
| 分类页新建但所选分类随后被删 | 编辑页分类 sheet 遍历现有分类，缺失时显示「分类」占位；保存时写 null 之外的值前 Repository 已有既有行为，本次不改 |
| 历史条数达到 40 条时保存并退出 | 先 Snackbar 提示，消失后退出（与现有保存按钮一致） |
| 对话框打开时按返回 | 仅关闭对话框 |
| 应用内 DARK 而系统 LIGHT（或反之） | `enableEdgeToEdge` 深色检测跟随应用主题，状态栏图标对比度正确 |
| Android 15 强制 edge-to-edge | 本改造即为其规范形态，无额外处理 |

## 6. 测试策略

- **新增** `app/src/test/java/com/mynote/app/ui/notes/HasUnsavedChangesTest.kt`（Robolectric + `@Config(sdk=[34])`，与项目惯例一致）：
  - 新建：全空 false；空白标题 false；正文非空 true；标题非空 true；选分类 true；仅预选分类（初始=当前）false；切换/清除预选分类 true；置顶 true。
  - 已有笔记未加载（saved=null）：全空 false；输入后 true。
  - 已有笔记已加载：完全一致 false；标题/正文/分类（含 null↔非 null）/置顶任一不同 true。
- 导航参数、IME 让位属 UI 系统行为，无 Compose UI 测试设施，靠 assembleDebug + 真机手工验证清单（见计划文档）。

## 7. 影响面

- `AndroidManifest.xml`、`MainActivity.kt` 的窗口改造全局生效：全部页面已使用 M3 `Scaffold` + 自处理状态栏的 `PaperTopBar`（`statusBarsPadding`），无已知回归点。
- 版本号：本次为 bug 修复，不发布 release 则不递增（发布时按惯例 patch +1）。
