# 三个严重缺陷修复 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复三个严重缺陷：分类重命名撞名崩溃；保存/删除连点导致重复数据与双重退出；清空内容后旋转屏幕被旧内容覆盖。

**Architecture:** 重命名在 Repository 层查重（返回 Boolean）+ UI Snackbar 提示；防重入在 ViewModel 层加在途标志（数据层兜底）+ UI 禁用按钮 + 导航 `launchSingleTop`；编辑页回填改用独立 `initialized` 标志。

**Tech Stack:** Kotlin + Compose(Material3) + Room + Robolectric 单测；零新增依赖。

**Spec:** `docs/superpowers/specs/2026-09-13-critical-fixes-design.md`

**执行前提：** 当前工作目录即仓库 `master`（D:\desktop\myNote）；命令统一 `.\gradlew`（Windows PowerShell）。不创建分支。

---

### Task 1: 分类重命名查重（崩溃修复）

**Files:**
- Modify: `app/src/main/java/com/mynote/app/data/repository/NoteRepository.kt`
- Modify: `app/src/main/java/com/mynote/app/ui/categories/CategoriesViewModel.kt`
- Modify: `app/src/main/java/com/mynote/app/ui/categories/CategoriesScreen.kt`
- Test: `app/src/test/java/com/mynote/app/data/repository/NoteRepositoryTest.kt`
- Test: `app/src/test/java/com/mynote/app/ui/categories/CategoriesViewModelTest.kt`（新建）

- [ ] **Step 1: 写失败测试**

`NoteRepositoryTest` 新增三个用例（`NoteRepository.renameCategory` 目前无返回值，先按新签名写会编译失败，属预期）：

```kotlin
@Test
fun renameCategoryToUnusedNameSucceeds() = runTest {
    val catId = repo.addCategory("工作", 0)
    assertTrue(repo.renameCategory(db.categoryDao().getById(catId)!!, "生活"))
    assertEquals("生活", db.categoryDao().getById(catId)?.name)
}

@Test
fun renameCategoryToExistingNameFailsAndKeepsOriginal() = runTest {
    val a = repo.addCategory("工作", 0)
    val b = repo.addCategory("生活", 1)
    val before = db.categoryDao().getAll().size
    // 注意：撞名重命名会抛 SQLiteConstraintException 导致崩溃——新实现应返回 false 而非抛异常
    val result = try {
        repo.renameCategory(db.categoryDao().getById(a)!!, "生活")
    } catch (e: SQLiteConstraintException) {
        fail("重命名撞名不应抛出约束异常")
    }
    assertFalse(result)
    assertEquals("工作", db.categoryDao().getById(a)?.name)
    assertEquals(before, db.categoryDao().getAll().size)
}

@Test
fun renameCategoryToItsOwnNameSucceeds() = runTest {
    val catId = repo.addCategory("工作", 0)
    assertTrue(repo.renameCategory(db.categoryDao().getById(catId)!!, "工作"))
    assertEquals("工作", db.categoryDao().getById(catId)?.name)
}
```

新建 `CategoriesViewModelTest`：

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CategoriesViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: AppDatabase
    private lateinit var repo: NoteRepository
    private lateinit var vm: CategoriesViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = NoteRepository(db.noteDao(), db.categoryDao(), db.noteRevisionDao(), ImageStore(context), db)
        vm = CategoriesViewModel(repo)
    }

    @After
    fun teardown() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun renameBlankNameDoesNotInvokeCallback() = runTest(dispatcher) {
        val catId = repo.addCategory("工作", 0)
        var called = false
        vm.rename(db.categoryDao().getById(catId)!!, "   ") { called = true }
        assertFalse(called)
    }

    @Test
    fun renameToExistingNameReportsFailure() = runTest(dispatcher) {
        val a = repo.addCategory("工作", 0)
        repo.addCategory("生活", 1)
        val result = CompletableDeferred<Boolean>()
        vm.rename(db.categoryDao().getById(a)!!, "生活") { result.complete(it) }
        assertFalse(result.await())
    }

    @Test
    fun renameToUnusedNameReportsSuccess() = runTest(dispatcher) {
        val catId = repo.addCategory("工作", 0)
        val result = CompletableDeferred<Boolean>()
        vm.rename(db.categoryDao().getById(catId)!!, "生活") { result.complete(it) }
        assertTrue(result.await())
    }
}
```

- [ ] **Step 2: 实现 Repository 与 ViewModel**

`NoteRepository.renameCategory` 改为返回 `Boolean`（查重逻辑见设计文档 3.1）；`CategoriesViewModel.rename` 改为 `rename(category, newName, onDone: (Boolean) -> Unit)`。

- [ ] **Step 3: UI 提示**

`CategoriesScreen`：`Scaffold` 加 `snackbarHost`；`CategoryRow` 签名改为 `onRename: (String, (Boolean) -> Unit) -> Unit`；保存点击逻辑：空白名退出编辑态；成功退出编辑态；失败 Snackbar「该分类已存在」并保持编辑态。

- [ ] **Step 4: 运行** `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.data.repository.NoteRepositoryTest" --tests "com.mynote.app.ui.categories.CategoriesViewModelTest"` 全绿。

### Task 2: 保存/删除防重入 + 导航防叠层

**Files:**
- Modify: `app/src/main/java/com/mynote/app/ui/notes/NoteEditScreen.kt`
- Modify: `app/src/main/java/com/mynote/app/ui/navigation/AppNavHost.kt`
- Test: `app/src/test/java/com/mynote/app/ui/notes/NoteEditViewModelTest.kt`

- [ ] **Step 1: 写失败测试**

`NoteEditViewModelTest` 新增：

```kotlin
@Test
fun doubleSaveCreatesOnlyOneNoteAndReportsOnce() = runTest(dispatcher) {
    var doneCount = 0
    val first = CompletableDeferred<Unit>()
    vm.save("t", "c", null, false, null) { doneCount++; first.complete(Unit) }
    vm.save("t", "c", null, false, null) { doneCount++ }
    first.await()
    assertEquals(1, db.noteDao().getAll().size)
    assertEquals(1, doneCount)
}
```

删除连点用例：先 `repo.saveNote(null,...)` 建笔记，`vm = NoteEditViewModel(repo, ..., noteId=id)`，`vm.note.first { it != null }` 等加载后连续两次 `vm.delete {}`，断言回调 1 次且库中 0 条。异步完成统一用 `CompletableDeferred`（真实 Dispatchers 上的 Room 事务不能依赖 `advanceUntilIdle`）。

- [ ] **Step 2: ViewModel 在途标志**

`NoteEditViewModel`：`saving` / `deleting` 布尔字段 + try/finally（见设计文档 3.2）。

- [ ] **Step 3: UI 禁用 + 导航 singleTop**

`NoteEditScreen`：`isSaving` 状态；`saveAndExit` 入口判 `isSaving`；保存与「保存并退出」按钮 `enabled = !isSaving`。
`AppNavHost`：`onOpenNote`、`onNewNote` 的 `navigate` 加 `launchSingleTop = true`。

- [ ] **Step 4: 运行** `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.ui.notes.NoteEditViewModelTest"` 全绿。

### Task 3: 编辑页 initialized 标志（旋转复活修复）

**Files:**
- Modify: `app/src/main/java/com/mynote/app/ui/notes/NoteEditScreen.kt`

- [ ] **Step 1: 实现**

新增 `var initialized by rememberSaveable(noteId) { mutableStateOf(false) }`，回填逻辑改为 `if (!initialized) { initialized = true; if (title.isEmpty() && content.text.isEmpty()) { ...回填... } }`（见设计文档 3.3）。

- [ ] **Step 2: 编译验证**（无独立单测，属 UI 状态逻辑；真机手工验证见 Task 4）。

### Task 4: 全量验证与收尾

- [ ] 运行 `.\gradlew :app:testDebugUnitTest` 全绿（预计 216 + 新增 ≈ 22x）。
- [ ] 运行 `.\gradlew :app:assembleDebug` 产出 APK。
- [ ] 真机手工验证清单：
  1. 分类管理页把分类 A 重命名为已有分类 B 名 → 不崩溃，Snackbar「该分类已存在」，A 保持原名。
  2. 新建笔记快速双击「保存」→ 列表只有一条笔记，且应用不退到桌面。
  3. 首页快速双击 FAB / 双击笔记行 → 只打开一层编辑页，返回一次即回列表。
  4. 打开已有笔记删光标题与正文 → 旋转屏幕 → 内容保持为空（不复活）。
  5. 编辑页删除确认框连点「删除」→ 只返回列表，不退到桌面。
- [ ] 按需递增版本号（仅发布 release 时），本次不主动 push。

## 修订记录

- 2026-09-13：初稿（跟随设计文档 `2026-09-13-critical-fixes-design.md`）。
