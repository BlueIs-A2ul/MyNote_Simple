# 编辑页体验修复（未保存提醒 / 分类预选 / 键盘遮挡）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复三个编辑页体验问题：未保存变更返回时确认提醒；分类页新建笔记预选当前分类；edge-to-edge + adjustResize 使 imePadding 生效，键盘不再遮挡正文。

**Architecture:** 纯函数脏检查 + 编辑页 `BackHandler`/返回按钮统一拦截；编辑路由增加可选 `categoryId` 参数；窗口级 edge-to-edge 改造（状态栏样式跟随应用内深色模式），零新增依赖。

**Tech Stack:** Kotlin + Compose(Material3) + Navigation Compose + activity-compose `enableEdgeToEdge`；Robolectric 单测。

**Spec:** `docs/superpowers/specs/2026-09-13-edit-ux-fixes-design.md`

**执行前提：** 当前工作目录即仓库 `master`（D:\desktop\myNote）；命令统一 `.\gradlew`（Windows PowerShell）。不创建分支（历史为 master 线性推进）。

---

### Task 1: 未保存变更确认（脏检查 + 返回拦截）

**Files:**
- Modify: `app/src/main/java/com/mynote/app/ui/notes/NoteEditScreen.kt`

- [ ] **Step 1: 写脏检查纯函数失败测试**

创建 `app/src/test/java/com/mynote/app/ui/notes/HasUnsavedChangesTest.kt`（Robolectric + `@Config(sdk=[34])`），覆盖设计文档 §6 全部用例：

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HasUnsavedChangesTest {

    private fun savedNote(
        title: String = "旧标题",
        content: String = "旧内容",
        categoryId: Long? = null,
        pinned: Boolean = false
    ) = NoteEntity(7L, title, content, 0L, 0L, categoryId, pinned, null)

    @Test fun newNoteAllEmptyIsNotDirty() =
        assertFalse(hasUnsavedChanges(true, null, null, "", "", null, false))

    @Test fun newNoteBlankTitleIsNotDirty() =
        assertFalse(hasUnsavedChanges(true, null, null, "   ", "", null, false))

    @Test fun newNoteContentIsDirty() =
        assertTrue(hasUnsavedChanges(true, null, null, "", "内容", null, false))

    @Test fun newNoteTitleIsDirty() =
        assertTrue(hasUnsavedChanges(true, null, null, "标题", "", null, false))

    @Test fun newNoteCategoryIsDirty() =
        assertTrue(hasUnsavedChanges(true, null, null, "", "", 3L, false))

    @Test fun newNotePreselectedCategoryOnlyIsNotDirty() =
        assertFalse(hasUnsavedChanges(true, null, 3L, "", "", 3L, false))

    @Test fun newNoteCategoryChangedFromInitialIsDirty() =
        assertTrue(hasUnsavedChanges(true, null, 3L, "", "", 4L, false))

    @Test fun newNoteCategoryClearedFromInitialIsDirty() =
        assertTrue(hasUnsavedChanges(true, null, 3L, "", "", null, false))

    @Test fun newNotePinnedIsDirty() =
        assertTrue(hasUnsavedChanges(true, null, null, "", "", null, true))

    @Test fun existingNoteNotLoadedAndEmptyIsNotDirty() =
        assertFalse(hasUnsavedChanges(false, null, null, "", "", null, false))

    @Test fun existingNoteNotLoadedWithInputIsDirty() =
        assertTrue(hasUnsavedChanges(false, null, null, "输入中", "", null, false))

    @Test fun existingNoteUnchangedIsNotDirty() =
        assertFalse(hasUnsavedChanges(false, savedNote(), null, "旧标题", "旧内容", null, false))

    @Test fun existingNoteTitleChangedIsDirty() =
        assertTrue(hasUnsavedChanges(false, savedNote(), null, "新标题", "旧内容", null, false))

    @Test fun existingNoteContentChangedIsDirty() =
        assertTrue(hasUnsavedChanges(false, savedNote(), null, "旧标题", "新内容", null, false))

    @Test fun existingNoteCategoryChangedIsDirty() =
        assertTrue(hasUnsavedChanges(false, savedNote(), null, "旧标题", "旧内容", 3L, false))

    @Test fun existingNoteCategoryClearedIsDirty() =
        assertTrue(hasUnsavedChanges(false, savedNote(categoryId = 3L), null, "旧标题", "旧内容", null, false))

    @Test fun existingNotePinnedChangedIsDirty() =
        assertTrue(hasUnsavedChanges(false, savedNote(), null, "旧标题", "旧内容", null, true))
}
```

先运行确认失败（`hasUnsavedChanges` 不存在）。

- [ ] **Step 2: 实现脏检查纯函数**

`NoteEditScreen.kt` 顶层（`NoteEditScreen` 之上）：

```kotlin
internal fun hasUnsavedChanges(
    isNew: Boolean,
    saved: NoteEntity?,
    initialCategoryId: Long?,
    title: String,
    content: String,
    categoryId: Long?,
    pinned: Boolean
): Boolean = when {
    isNew -> title.isNotBlank() || content.isNotBlank() ||
        categoryId != initialCategoryId || pinned
    saved == null -> title.isNotBlank() || content.isNotBlank()
    else -> title != saved.title || content != saved.content ||
        categoryId != saved.categoryId || pinned != saved.pinned
}
```

（新建笔记以 `initialCategoryId` 为分类基线：分类页带入的预选分类不算变更，避免空笔记返回时被无谓打扰。）

运行 `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.ui.notes.HasUnsavedChangesTest"` 确认全绿。

- [ ] **Step 3: 返回拦截与确认框**

`NoteEditScreen.kt`：

1. import `androidx.activity.compose.BackHandler`。
2. 新增 `var showUnsavedDialog by remember { mutableStateOf(false) }`。
3. `val isNewNote = noteId == null || noteId == 0L`；`val dirty = hasUnsavedChanges(isNewNote, note, title, content.text, selectedCategoryId, pinned)`。
4. 抽取 `saveAndExit()`（lambda）：

```kotlin
val saveAndExit: () -> Unit = {
    vm.save(title, content.text, selectedCategoryId, pinned, note?.color) { warning ->
        if (warning != null) {
            scope.launch {
                snackbarHostState.showSnackbar(warning)
                onBack()
            }
        } else {
            onBack()
        }
    }
}
```

顶栏「保存」按钮 onClick 改为 `saveAndExit`。
5. `BackHandler(enabled = true) { if (dirty) showUnsavedDialog = true else onBack() }`；顶栏 `onBack = { if (dirty) showUnsavedDialog = true else onBack() }`。
6. 对话框：

```kotlin
if (showUnsavedDialog) {
    PaperAlertDialog(
        onDismissRequest = { showUnsavedDialog = false },
        title = "尚未保存的更改",
        text = { Text("当前内容尚未保存，要保存后再退出吗？") },
        confirmButton = {
            Row {
                TextButton(onClick = onBack) { Text("不保存") }
                TextButton(onClick = { showUnsavedDialog = false; saveAndExit() }) {
                    Text("保存并退出")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { showUnsavedDialog = false }) { Text("取消") }
        }
    )
}
```

注意「不保存」按钮直接 `onBack`（丢弃内容），无需先关对话框（页面即离开）；「保存并退出」先关框再 `saveAndExit`（保存是异步的，避免框遮挡 Snackbar）。

- [ ] **Step 4: 运行编辑页相关单测**（`NoteEditViewModelTest` + `HasUnsavedChangesTest`）确认无回归。

### Task 2: 分类页新建笔记预选分类

**Files:**
- Modify: `app/src/main/java/com/mynote/app/ui/navigation/AppNavHost.kt`
- Modify: `app/src/main/java/com/mynote/app/ui/notes/NotesScreen.kt`
- Modify: `app/src/main/java/com/mynote/app/ui/notes/NoteEditScreen.kt`

- [ ] **Step 1: 路由增加可选参数**

`AppNavHost.kt`：

```kotlin
composable(
    route = "edit/{noteId}?categoryId={categoryId}",
    arguments = listOf(
        navArgument("categoryId") {
            type = NavType.LongType
            defaultValue = -1L
        }
    )
) { backStack ->
    // ...
    val initialCategoryId = backStack.arguments
        ?.getLong("categoryId")
        ?.takeIf { it >= 0L }
    NoteEditScreen(
        noteId = id,
        initialCategoryId = initialCategoryId,
        // ...其余参数不变
    )
}
```

`onNewNote` 改为 `onNewNote = { catId -> navController.navigate("edit/new?categoryId=${catId ?: -1L}") }`。补充 import `androidx.navigation.NavType`、`androidx.navigation.navArgument`。

- [ ] **Step 2: NotesScreen 传递当前分类**

`NotesScreen.kt`：签名 `onNewNote: (Long?) -> Unit`；FAB `onClick = { onNewNote(selectedCategoryId) }`；空态 `onAction = if (searching) null else { onNewNote(selectedCategoryId) }`。

- [ ] **Step 3: NoteEditScreen 接收初始分类**

签名新增 `initialCategoryId: Long?`；`selectedCategoryId` 改为：

```kotlin
var selectedCategoryId by rememberSaveable(noteId) { mutableStateOf(initialCategoryId) }
```

已有笔记回填逻辑（`LaunchedEffect(note)`）不变。

- [ ] **Step 4: 编译验证**（此任务无独立单测；逻辑走 UI 层，真机验证见 Task 4）。

### Task 3: 键盘遮挡（edge-to-edge + adjustResize）

**Files:**
- Modify: `app/src/main/java/com/mynote/app/MainActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/mynote/app/ui/ai/AiChatScreen.kt`
- Modify: `app/src/main/java/com/mynote/app/ui/notes/NotesScreen.kt`

- [ ] **Step 1: MainActivity 启用 edge-to-edge**

`MainActivity.kt`：

```kotlin
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as MyNoteApp).container
        setContent {
            val settings by container.themeSettingsStore.settings.collectAsState()
            val darkTheme = when (settings.darkMode) {
                DarkMode.SYSTEM -> isSystemInDarkTheme()
                DarkMode.LIGHT -> false
                DarkMode.DARK -> true
            }
            LaunchedEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        Color.TRANSPARENT, Color.TRANSPARENT
                    ) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(
                        Color.argb(0xE6, 0xFF, 0xFF, 0xFF),
                        Color.argb(0x80, 0x1B, 0x1B, 0x1B)
                    ) { darkTheme }
                )
            }
            MyNoteTheme(
                darkTheme = darkTheme,
                dynamicColor = settings.dynamicColor,
                preset = ThemePresets.resolve(settings.themeColorIndex)
            ) {
                AppNavHost(container)
            }
        }
    }
}
```

（导航栏浅/深色遮罩取值与 activity 库默认 `DefaultLightScrim`/`DefaultDarkScrim` 相同，保证三键导航对比度；状态栏用透明遮罩。深色检测 lambda 跟随应用内 DarkMode 设置。）

- [ ] **Step 2: Manifest 声明 adjustResize**

`AndroidManifest.xml` 的 `MainActivity` 加 `android:windowSoftInputMode="adjustResize"`。

- [ ] **Step 3: 补 imePadding**

- `AiChatScreen.kt`：`ChatLayer` 根 `Surface` 的 modifier 加 `.imePadding()`。
- `NotesScreen.kt`：内容 `Column(Modifier.padding(padding).fillMaxSize())` 加 `.imePadding()`。

- [ ] **Step 4: assembleDebug 编译验证。**

### Task 4: 全量验证与收尾

- [ ] 运行 `.\gradlew :app:testDebugUnitTest`（全部单测，约 200 个）确认全绿。
- [ ] 运行 `.\gradlew :app:assembleDebug` 产出 APK。
- [ ] 真机手工验证清单（交付用户时附上）：
  1. 新建笔记输入内容 → 顶栏返回 → 出现「尚未保存的更改」；「取消」留在编辑页；「不保存」退出且列表无新笔记；「保存并退出」退出且列表出现新笔记。
  2. 新建笔记全空 → 返回静默退出（无对话框）。
  3. 已有笔记修改标题/正文/分类/置顶任一 → 返回弹框；未修改 → 静默返回。
  4. 某分类页（无笔记）→ FAB 与「写第一条」→ 编辑页分类 chip 显示该分类 → 保存 → 笔记出现在该分类下。
  5. 「全部」页新建 → 分类为「未分类」。
  6. 长文编辑到底部 → 键盘弹出 → 底部工具栏与光标在键盘上方可见，可继续输入。
  7. 首页搜索输入、AI 聊天输入：键盘弹出时输入框与内容不被遮挡。
  8. 深色/浅色/跟随系统三档下状态栏图标对比度正常。
- [ ] 按需递增版本号（仅发布 release 时，惯例 patch +1），本次不主动 push。

## 修订记录

- 2026-09-13：初稿（跟随设计文档 `2026-09-13-edit-ux-fixes-design.md`）。
- 2026-09-13：修订 Task 1——新建笔记脏检查以 `initialCategoryId` 为分类基线（分类页预选分类不算变更），新增两个对应测试用例。
