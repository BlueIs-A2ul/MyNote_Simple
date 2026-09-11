# AI 网页端助手（DeepSeek 先行）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在编辑页接入 DeepSeek 网页版 AI 助手：app 内聊天 UI + 隐藏 WebView 自动发送/抓取流式回答 + 按笔记本地留档 + 回答落地（插入/替换/复制/存新笔记），服务适配层可扩展到豆包/Kimi。

**Architecture:** Room v3 新增 `ai_sessions`/`ai_messages` 两表；`AiWebDriver` 接口封装单服务差异（选择器/JS），`WebViewAiSession` 负责 WebView 注入与事件桥；`AiChatViewModel` 编排「保存消息 → 驱动网页 → 流式落库」；编辑页内容状态升级为 `TextFieldValue` 以支持光标插入与选区替换。

**Tech Stack:** Kotlin + Jetpack Compose (Material 3) + Room(KSP) + 协程 Flow；WebView + `@JavascriptInterface` + `org.json`（均为系统 API，零新增三方依赖）；Robolectric 单测。

**依据设计：** `docs/superpowers/specs/2026-09-11-ai-web-assistant-design.md`（已确认）。

---

## 文件结构总览

```
新增：
app/src/main/java/com/mynote/app/data/db/AiSessionEntity.kt
app/src/main/java/com/mynote/app/data/db/AiSessionDao.kt
app/src/main/java/com/mynote/app/data/db/AiMessageEntity.kt
app/src/main/java/com/mynote/app/data/db/AiMessageDao.kt
app/src/main/java/com/mynote/app/data/ai/AiWebDriver.kt
app/src/main/java/com/mynote/app/data/ai/AiWebSession.kt
app/src/main/java/com/mynote/app/data/ai/AiDriverRegistry.kt
app/src/main/java/com/mynote/app/data/ai/DeepSeekDriver.kt
app/src/main/java/com/mynote/app/data/ai/AiPromptBuilder.kt
app/src/main/java/com/mynote/app/data/ai/AiChatRepository.kt
app/src/main/java/com/mynote/app/data/ai/AiWebEventParser.kt
app/src/main/java/com/mynote/app/data/ai/WebViewAiSession.kt
app/src/main/java/com/mynote/app/data/settings/AiSettingsStore.kt
app/src/main/java/com/mynote/app/ui/notes/AiResultApplier.kt
app/src/main/java/com/mynote/app/ui/ai/AiChatViewModel.kt
app/src/main/java/com/mynote/app/ui/ai/AiChatScreen.kt

修改：
app/src/main/java/com/mynote/app/data/db/AppDatabase.kt           # v3 + MIGRATION_2_3
app/src/main/java/com/mynote/app/di/AppContainer.kt              # DAO/仓库/注册表/会话工厂/设置/应用协程作用域
app/src/main/java/com/mynote/app/ui/notes/NoteEditScreen.kt      # TextFieldValue 化 + AI 入口 + 结果落回
app/src/main/java/com/mynote/app/ui/navigation/AppNavHost.kt     # ai_chat/{noteId} 路由 + 传参/回传
app/src/main/AndroidManifest.xml                                 # INTERNET 权限

测试新增：
app/src/test/java/com/mynote/app/data/db/AiSessionDaoTest.kt
app/src/test/java/com/mynote/app/data/db/AiMessageDaoTest.kt
app/src/test/java/com/mynote/app/data/ai/DeepSeekDriverTest.kt
app/src/test/java/com/mynote/app/data/ai/AiDriverRegistryTest.kt
app/src/test/java/com/mynote/app/data/ai/AiPromptBuilderTest.kt
app/src/test/java/com/mynote/app/data/ai/AiChatRepositoryTest.kt
app/src/test/java/com/mynote/app/data/ai/AiWebEventParserTest.kt
app/src/test/java/com/mynote/app/ui/notes/AiResultApplierTest.kt
app/src/test/java/com/mynote/app/ui/ai/AiChatViewModelTest.kt

测试修改：
app/src/test/java/com/mynote/app/data/db/AppDatabaseMigrationTest.kt   # 增加 v2→v3 用例
```

统一命令（Windows PowerShell，工作目录 `D:\desktop\myNote`）：

```powershell
.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.xxx.YyyTest" --console=plain
.\gradlew :app:testDebugUnitTest --console=plain
```

---

### Task 1: 数据库 v3（实体 / DAO / 迁移）

**Files:**
- Create: `app/src/main/java/com/mynote/app/data/db/AiSessionEntity.kt`
- Create: `app/src/main/java/com/mynote/app/data/db/AiSessionDao.kt`
- Create: `app/src/main/java/com/mynote/app/data/db/AiMessageEntity.kt`
- Create: `app/src/main/java/com/mynote/app/data/db/AiMessageDao.kt`
- Modify: `app/src/main/java/com/mynote/app/data/db/AppDatabase.kt`
- Modify: `app/src/main/java/com/mynote/app/di/AppContainer.kt`（仅迁移列表 + DAO 暴露，其余 Task 4 再加）
- Test: `app/src/test/java/com/mynote/app/data/db/AiSessionDaoTest.kt`
- Test: `app/src/test/java/com/mynote/app/data/db/AiMessageDaoTest.kt`
- Test: `app/src/test/java/com/mynote/app/data/db/AppDatabaseMigrationTest.kt`

- [ ] **Step 1: 写失败测试 `AiSessionDaoTest`**

```kotlin
package com.mynote.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiSessionDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun session(noteId: Long, title: String = "会话", updatedAt: Long = 1) =
        AiSessionEntity(
            noteId = noteId, serviceId = "deepseek", title = title,
            remoteChatId = null, createdAt = 1, updatedAt = updatedAt
        )

    @Test
    fun insertAndObserveOrderedByUpdatedAtDesc() = runTest {
        val noteId = db.noteDao().insert(NoteEntity(0, "t", "c", 1, 1, null, false, null))
        db.aiSessionDao().insert(session(noteId, "old", 100))
        db.aiSessionDao().insert(session(noteId, "new", 200))
        val list = db.aiSessionDao().observeByNote(noteId).first()
        assertEquals(listOf("new", "old"), list.map { it.title })
    }

    @Test
    fun observeByNoteOnlyReturnsThatNote() = runTest {
        val a = db.noteDao().insert(NoteEntity(0, "a", "", 1, 1, null, false, null))
        val b = db.noteDao().insert(NoteEntity(0, "b", "", 1, 1, null, false, null))
        db.aiSessionDao().insert(session(a, "a1"))
        db.aiSessionDao().insert(session(b, "b1"))
        assertEquals(listOf("a1"), db.aiSessionDao().observeByNote(a).first().map { it.title })
    }

    @Test
    fun updateRemoteChatIdAndTouch() = runTest {
        val noteId = db.noteDao().insert(NoteEntity(0, "t", "c", 1, 1, null, false, null))
        val id = db.aiSessionDao().insert(session(noteId))
        db.aiSessionDao().updateRemoteChatId(id, "abc-123")
        db.aiSessionDao().touch(id, 999)
        val row = db.aiSessionDao().getById(id)!!
        assertEquals("abc-123", row.remoteChatId)
        assertEquals(999, row.updatedAt)
    }

    @Test
    fun deletingNoteCascadesSessions() = runTest {
        val noteId = db.noteDao().insert(NoteEntity(0, "t", "c", 1, 1, null, false, null))
        db.aiSessionDao().insert(session(noteId))
        db.noteDao().delete(db.noteDao().getById(noteId)!!)
        assertEquals(0, db.aiSessionDao().observeByNote(noteId).first().size)
    }
}
```

- [ ] **Step 2: 跑测试确认失败（编译不过）**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.data.db.AiSessionDaoTest" --console=plain`
Expected: FAIL，`AiSessionEntity` / `AiSessionDao` 未定义。

- [ ] **Step 3: 写失败测试 `AiMessageDaoTest`**

```kotlin
package com.mynote.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiMessageDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun teardown() {
        db.close()
    }

    private suspend fun newSession(): Long {
        val noteId = db.noteDao().insert(NoteEntity(0, "t", "c", 1, 1, null, false, null))
        return db.aiSessionDao().insert(
            AiSessionEntity(
                noteId = noteId, serviceId = "deepseek", title = "会话",
                remoteChatId = null, createdAt = 1, updatedAt = 1
            )
        )
    }

    private fun message(sessionId: Long, role: String, content: String, createdAt: Long, status: String = "done") =
        AiMessageEntity(sessionId = sessionId, role = role, content = content, status = status, createdAt = createdAt)

    @Test
    fun insertAndObserveOrderedByCreatedAtAsc() = runTest {
        val sessionId = newSession()
        db.aiMessageDao().insert(message(sessionId, AiMessageEntity.ROLE_USER, "q1", 100))
        db.aiMessageDao().insert(message(sessionId, AiMessageEntity.ROLE_ASSISTANT, "a1", 200))
        val list = db.aiMessageDao().observeBySession(sessionId).first()
        assertEquals(listOf("q1", "a1"), list.map { it.content })
    }

    @Test
    fun updateContentAndStatusForInterrupted() = runTest {
        val sessionId = newSession()
        val id = db.aiMessageDao().insert(
            message(sessionId, AiMessageEntity.ROLE_ASSISTANT, "半截", 100, AiMessageEntity.STATUS_FAILED)
        )
        db.aiMessageDao().updateContentAndStatus(id, "半截完整一点", AiMessageEntity.STATUS_INTERRUPTED)
        val row = db.aiMessageDao().getBySession(sessionId).single()
        assertEquals("半截完整一点", row.content)
        assertEquals(AiMessageEntity.STATUS_INTERRUPTED, row.status)
    }

    @Test
    fun deletingSessionCascadesMessages() = runTest {
        val sessionId = newSession()
        db.aiMessageDao().insert(message(sessionId, AiMessageEntity.ROLE_USER, "q", 1))
        db.aiSessionDao().deleteById(sessionId)
        assertEquals(0, db.aiMessageDao().getBySession(sessionId).size)
    }
}
```

- [ ] **Step 4: 实现实体与 DAO**

`AiSessionEntity.kt`：

```kotlin
package com.mynote.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ai_sessions",
    foreignKeys = [ForeignKey(
        entity = NoteEntity::class,
        parentColumns = ["id"],
        childColumns = ["noteId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("noteId")]
)
data class AiSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val serviceId: String,
    val title: String,
    val remoteChatId: String?,
    val createdAt: Long,
    val updatedAt: Long
)
```

`AiMessageEntity.kt`：

```kotlin
package com.mynote.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ai_messages",
    foreignKeys = [ForeignKey(
        entity = AiSessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class AiMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val role: String,
    val content: String,
    val status: String,
    val createdAt: Long
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val STATUS_DONE = "done"
        const val STATUS_INTERRUPTED = "interrupted"
        const val STATUS_FAILED = "failed"
    }
}
```

`AiSessionDao.kt`：

```kotlin
package com.mynote.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AiSessionDao {

    @Query("SELECT * FROM ai_sessions WHERE noteId = :noteId ORDER BY updatedAt DESC, id DESC")
    fun observeByNote(noteId: Long): Flow<List<AiSessionEntity>>

    @Query("SELECT * FROM ai_sessions WHERE id = :id")
    suspend fun getById(id: Long): AiSessionEntity?

    @Insert
    suspend fun insert(session: AiSessionEntity): Long

    @Query("UPDATE ai_sessions SET remoteChatId = :remoteChatId WHERE id = :id")
    suspend fun updateRemoteChatId(id: Long, remoteChatId: String)

    @Query("UPDATE ai_sessions SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, updatedAt: Long)

    @Query("DELETE FROM ai_sessions WHERE id = :id")
    suspend fun deleteById(id: Long)
}
```

`AiMessageDao.kt`：

```kotlin
package com.mynote.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AiMessageDao {

    @Query("SELECT * FROM ai_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    fun observeBySession(sessionId: Long): Flow<List<AiMessageEntity>>

    @Query("SELECT * FROM ai_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    suspend fun getBySession(sessionId: Long): List<AiMessageEntity>

    @Insert
    suspend fun insert(message: AiMessageEntity): Long

    @Query("UPDATE ai_messages SET content = :content, status = :status WHERE id = :id")
    suspend fun updateContentAndStatus(id: Long, content: String, status: String)
}
```

- [ ] **Step 5: 改 `AppDatabase` 到 v3 + 迁移**

`AppDatabase.kt` 整体替换为：

```kotlin
package com.mynote.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        NoteEntity::class, CategoryEntity::class, NoteRevisionEntity::class,
        AiSessionEntity::class, AiMessageEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun categoryDao(): CategoryDao
    abstract fun noteRevisionDao(): NoteRevisionDao
    abstract fun aiSessionDao(): AiSessionDao
    abstract fun aiMessageDao(): AiMessageDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `note_revisions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`noteId` INTEGER NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`content` TEXT NOT NULL, " +
                        "`categoryId` INTEGER, " +
                        "`pinned` INTEGER NOT NULL, " +
                        "`color` INTEGER, " +
                        "`savedAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_revisions_noteId` ON `note_revisions` (`noteId`)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ai_sessions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`noteId` INTEGER NOT NULL, " +
                        "`serviceId` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`remoteChatId` TEXT, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_sessions_noteId` ON `ai_sessions` (`noteId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ai_messages` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sessionId` INTEGER NOT NULL, " +
                        "`role` TEXT NOT NULL, " +
                        "`content` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`sessionId`) REFERENCES `ai_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_messages_sessionId` ON `ai_messages` (`sessionId`)")
            }
        }
    }
}
```

- [ ] **Step 6: `AppContainer` 挂迁移与 DAO 暴露**

`AppContainer.kt` 中 `database` 的 `.addMigrations(AppDatabase.MIGRATION_1_2)` 改为：

```kotlin
        Room.databaseBuilder(context, AppDatabase::class.java, "mynote.db")
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .build()
```

- [ ] **Step 7: 迁移测试扩展（v2 → v3）**

在 `AppDatabaseMigrationTest.kt` 末尾（`}` 前）追加：

```kotlin
    /** 按 Room v2 的精确 schema 手工建库（含 notes/categories/note_revisions），再走 v2→v3。 */
    private fun createV2Database() {
        val v2 = context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null)
        v2.execSQL(
            "CREATE TABLE IF NOT EXISTS `notes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, `content` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, `categoryId` INTEGER, `pinned` INTEGER NOT NULL, `color` INTEGER)"
        )
        v2.execSQL(
            "CREATE TABLE IF NOT EXISTS `categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `color` INTEGER NOT NULL)"
        )
        v2.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_categories_name` ON `categories` (`name`)")
        v2.execSQL(
            "CREATE TABLE IF NOT EXISTS `note_revisions` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `noteId` INTEGER NOT NULL, " +
                "`title` TEXT NOT NULL, `content` TEXT NOT NULL, `categoryId` INTEGER, " +
                "`pinned` INTEGER NOT NULL, `color` INTEGER, `savedAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        v2.execSQL("CREATE INDEX IF NOT EXISTS `index_note_revisions_noteId` ON `note_revisions` (`noteId`)")
        v2.execSQL(
            "INSERT INTO notes (title, content, createdAt, updatedAt, categoryId, pinned, color) " +
                "VALUES ('老标题', '老内容', 111, 222, NULL, 0, NULL)"
        )
        v2.execSQL(
            "INSERT INTO note_revisions (noteId, title, content, categoryId, pinned, color, savedAt) " +
                "VALUES (1, '老标题', '老内容', NULL, 0, NULL, 222)"
        )
        v2.version = 2
        v2.close()
    }

    @Test
    fun migrate2To3KeepsNotesAndCreatesAiTables() = runTest {
        createV2Database()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals("老标题", db.noteDao().getById(1)?.title)
            assertEquals(1, db.noteRevisionDao().countByNote(1))

            val sessionId = db.aiSessionDao().insert(
                AiSessionEntity(
                    noteId = 1, serviceId = "deepseek", title = "新会话",
                    remoteChatId = null, createdAt = 1, updatedAt = 1
                )
            )
            val messageId = db.aiMessageDao().insert(
                AiMessageEntity(
                    sessionId = sessionId, role = AiMessageEntity.ROLE_USER,
                    content = "问题", status = AiMessageEntity.STATUS_DONE, createdAt = 1
                )
            )
            assertTrue(sessionId > 0 && messageId > 0)
            assertEquals(1, db.aiMessageDao().getBySession(sessionId).size)

            db.noteDao().delete(db.noteDao().getById(1)!!)
            assertEquals(0, db.aiSessionDao().observeByNote(1).first().size)
        } finally {
            db.close()
        }
    }
```

文件顶部补 import：

```kotlin
import kotlinx.coroutines.flow.first
```

- [ ] **Step 8: 跑本任务全部测试**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.data.db.*" --console=plain`
Expected: PASS（AiSessionDaoTest / AiMessageDaoTest / AppDatabaseMigrationTest 及既有 DAO 测试全绿）。

- [ ] **Step 9: 提交**

```powershell
git add app/src/main/java/com/mynote/app/data/db app/src/main/java/com/mynote/app/di/AppContainer.kt app/src/test/java/com/mynote/app/data/db
git commit -m "feat: AI 会话与消息表（Room v3 + 迁移）"
```

---

### Task 2: 服务驱动层（接口 + DeepSeek 实现 + 注册表）

**Files:**
- Create: `app/src/main/java/com/mynote/app/data/ai/AiWebDriver.kt`
- Create: `app/src/main/java/com/mynote/app/data/ai/AiWebSession.kt`
- Create: `app/src/main/java/com/mynote/app/data/ai/AiDriverRegistry.kt`
- Create: `app/src/main/java/com/mynote/app/data/ai/DeepSeekDriver.kt`
- Test: `app/src/test/java/com/mynote/app/data/ai/DeepSeekDriverTest.kt`
- Test: `app/src/test/java/com/mynote/app/data/ai/AiDriverRegistryTest.kt`

- [ ] **Step 1: 写失败测试 `DeepSeekDriverTest`**

```kotlin
package com.mynote.app.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeepSeekDriverTest {

    private val driver = DeepSeekDriver()

    @Test
    fun parseChatIdFromChatUrl() {
        assertEquals(
            "abc-123",
            driver.parseChatId("https://chat.deepseek.com/a/chat/s/abc-123")
        )
    }

    @Test
    fun parseChatIdReturnsNullForHome() {
        assertNull(driver.parseChatId("https://chat.deepseek.com/"))
    }

    @Test
    fun chatUrlBuildsDeepLink() {
        assertEquals("https://chat.deepseek.com/a/chat/s/x9", driver.chatUrl("x9"))
    }

    @Test
    fun sendMessageJsEscapesQuotesAndNewlines() {
        val js = driver.sendMessageJs("他\"说\"\n第二行")
        assertTrue(js.contains("window.__mynoteText = "))
        assertTrue(js.contains("\\\"说\\\""))
        assertTrue(js.contains("\\n"))
    }

    @Test
    fun scriptsSpeakBridgeProtocol() {
        assertTrue(driver.loginCheckJs().contains("loginState"))
        assertTrue(driver.observeReplyJs().contains("replyChunk"))
        assertTrue(driver.observeReplyJs().contains("replyDone"))
        assertTrue(driver.newChatJs().contains("新对话"))
        assertTrue(driver.stopObservingJs().contains("__mynoteObserver"))
    }
}
```

- [ ] **Step 2: 跑测试确认失败（编译不过）**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.data.ai.DeepSeekDriverTest" --console=plain`
Expected: FAIL，`DeepSeekDriver` / `AiWebDriver` 未定义。

- [ ] **Step 3: 写接口 `AiWebDriver`**

```kotlin
package com.mynote.app.data.ai

/**
 * 单个网页 AI 服务的适配器：URL 规则、DOM 选择器与注入脚本都集中在这里。
 * 新增服务 = 新增一个实现 + 在 AiDriverRegistry 注册一行。
 * 所有脚本为纯字符串（可单测），运行环境里 window.__mynote.emit 由 WebViewAiSession 注入。
 */
interface AiWebDriver {
    val id: String
    val displayName: String
    val homeUrl: String

    fun chatUrl(remoteChatId: String): String
    fun parseChatId(url: String): String?

    /** 检测登录态，结果经 emit('loginState', {loggedIn}) 上报。 */
    fun loginCheckJs(): String

    /** 点击页面“新对话”；找不到时自行超时退出。 */
    fun newChatJs(): String

    /** 填入输入框并点发送；找不到输入框时 emit('replyError', {reason})。 */
    fun sendMessageJs(text: String): String

    /** 启动回答观察：emit replyChunk / replyDone / replyError。 */
    fun observeReplyJs(): String

    /** 断开回答观察。 */
    fun stopObservingJs(): String

    /** 点击页面“停止生成”（若存在）。 */
    fun stopGeneratingJs(): String
}
```

- [ ] **Step 4: 写事件与会话接口 `AiWebSession`**

```kotlin
package com.mynote.app.data.ai

import android.webkit.WebView
import kotlinx.coroutines.flow.SharedFlow

sealed interface AiWebEvent {
    data object PageReady : AiWebEvent
    data class LoginState(val loggedIn: Boolean) : AiWebEvent
    data class ChatId(val id: String) : AiWebEvent
    data class ReplyChunk(val text: String) : AiWebEvent
    data class ReplyDone(val text: String) : AiWebEvent
    data class ReplyError(val reason: String) : AiWebEvent
    data class PageError(val description: String) : AiWebEvent
}

/** WebView 层抽象：真实实现见 WebViewAiSession；测试用 Fake 替换。 */
interface AiWebSession {
    val events: SharedFlow<AiWebEvent>
    val driver: AiWebDriver

    fun attach(webView: WebView)
    fun openNewChat()
    fun openChat(remoteChatId: String)
    fun send(text: String)
    fun stop()
    fun release()
}
```

- [ ] **Step 5: 写 `AiDriverRegistry`**

```kotlin
package com.mynote.app.data.ai

class AiDriverRegistry(private val drivers: List<AiWebDriver>) {

    init {
        require(drivers.isNotEmpty()) { "至少注册一个 AI 服务驱动" }
    }

    val all: List<AiWebDriver> get() = drivers

    val default: AiWebDriver get() = drivers.first()

    fun find(id: String?): AiWebDriver? = drivers.firstOrNull { it.id == id }
}
```

- [ ] **Step 6: 写 `DeepSeekDriver`**

```kotlin
package com.mynote.app.data.ai

import org.json.JSONObject

class DeepSeekDriver : AiWebDriver {

    override val id: String = "deepseek"
    override val displayName: String = "DeepSeek"
    override val homeUrl: String = "https://chat.deepseek.com/"

    override fun chatUrl(remoteChatId: String): String =
        "https://chat.deepseek.com/a/chat/s/$remoteChatId"

    override fun parseChatId(url: String): String? =
        CHAT_ID_REGEX.find(url)?.groupValues?.get(1)

    override fun loginCheckJs(): String = LOGIN_CHECK_JS

    override fun newChatJs(): String = NEW_CHAT_JS

    override fun sendMessageJs(text: String): String =
        "window.__mynoteText = " + JSONObject.quote(text) + ";\n" + SEND_MESSAGE_JS

    override fun observeReplyJs(): String = OBSERVE_REPLY_JS

    override fun stopObservingJs(): String = STOP_OBSERVING_JS

    override fun stopGeneratingJs(): String = STOP_GENERATING_JS

    private companion object {
        val CHAT_ID_REGEX = Regex("""/a/chat/s/([\w-]+)""")

        val LOGIN_CHECK_JS = """
            (function () {
              var list = document.querySelectorAll('textarea');
              var ok = false;
              for (var i = 0; i < list.length; i++) {
                var el = list[i];
                if (el.offsetParent !== null && !el.disabled) { ok = true; break; }
              }
              window.__mynote.emit('loginState', { loggedIn: ok });
            })();
        """.trimIndent()

        val NEW_CHAT_JS = """
            (function () {
              var tries = 0;
              var timer = setInterval(function () {
                var nodes = document.querySelectorAll('button, [role="button"], div, span');
                for (var i = 0; i < nodes.length; i++) {
                  var el = nodes[i];
                  var t = (el.textContent || '').trim();
                  if ((t === '开启新对话' || t === '新对话') && el.offsetParent !== null) {
                    el.click();
                    clearInterval(timer);
                    return;
                  }
                }
                if (++tries > 20) clearInterval(timer);
              }, 300);
            })();
        """.trimIndent()

        val SEND_MESSAGE_JS = """
            (function () {
              var payload = window.__mynoteText;
              function findInput() {
                var list = document.querySelectorAll('textarea');
                for (var i = 0; i < list.length; i++) {
                  var el = list[i];
                  if (el.offsetParent !== null && !el.disabled) return el;
                }
                return null;
              }
              function findSend(input) {
                var container = input.parentElement;
                for (var i = 0; i < 8 && container; i++) {
                  var btns = container.querySelectorAll('button, [role="button"]');
                  var enabled = [];
                  for (var j = 0; j < btns.length; j++) {
                    var b = btns[j];
                    if (b.offsetParent !== null && b.getAttribute('aria-disabled') !== 'true' && !b.disabled) enabled.push(b);
                  }
                  if (enabled.length) {
                    for (var k = 0; k < enabled.length; k++) {
                      var label = ((enabled[k].getAttribute('aria-label') || '') + (enabled[k].textContent || '')).toLowerCase();
                      if (label.indexOf('send') >= 0 || label.indexOf('发送') >= 0) return enabled[k];
                    }
                    return enabled[enabled.length - 1];
                  }
                  container = container.parentElement;
                }
                return null;
              }
              var input = findInput();
              if (!input) {
                window.__mynote.emit('replyError', { reason: '未找到输入框，请显示网页手动发送' });
                return;
              }
              input.focus();
              var setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set;
              setter.call(input, payload);
              input.dispatchEvent(new Event('input', { bubbles: true }));
              input.dispatchEvent(new Event('change', { bubbles: true }));
              setTimeout(function () {
                var btn = findSend(input);
                if (btn) { btn.click(); return; }
                input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
              }, 500);
            })();
        """.trimIndent()

        val OBSERVE_REPLY_JS = """
            (function () {
              if (window.__mynoteObserver) { window.__mynoteObserver.disconnect(); window.__mynoteObserver = null; }
              if (window.__mynoteTimer) { clearInterval(window.__mynoteTimer); window.__mynoteTimer = null; }
              function findStop() {
                var nodes = document.querySelectorAll('[role="button"], button');
                for (var i = 0; i < nodes.length; i++) {
                  var t = (nodes[i].textContent || '').trim();
                  var a = nodes[i].getAttribute('aria-label') || '';
                  if (t.indexOf('停止') >= 0 || a.indexOf('停止') >= 0) return nodes[i];
                }
                return null;
              }
              function targets() {
                return document.querySelectorAll('[class*="markdown"]');
              }
              var beforeCount = targets().length;
              var lastText = '';
              var lastChange = Date.now();
              var started = false;
              var finished = false;
              function finish(ok, reason) {
                if (finished) return;
                finished = true;
                if (window.__mynoteObserver) { window.__mynoteObserver.disconnect(); window.__mynoteObserver = null; }
                if (window.__mynoteTimer) { clearInterval(window.__mynoteTimer); window.__mynoteTimer = null; }
                if (ok) { window.__mynote.emit('replyDone', { text: lastText }); }
                else { window.__mynote.emit('replyError', { reason: reason || '回答超时' }); }
              }
              function tick() {
                var list = targets();
                if (list.length > beforeCount) {
                  var el = list[list.length - 1];
                  var text = (el.innerText || '').replace(/\s+$/, '');
                  if (text !== lastText) {
                    lastText = text;
                    lastChange = Date.now();
                    started = true;
                    window.__mynote.emit('replyChunk', { text: text });
                  }
                }
                var stop = findStop();
                if (started && !stop && Date.now() - lastChange > 1200) { finish(true); return; }
                if (started && Date.now() - lastChange > 60000) { finish(false, '回答超时'); return; }
                if (!started && Date.now() - lastChange > 60000) { finish(false, '未收到回答，请显示网页检查'); }
              }
              window.__mynoteObserver = new MutationObserver(tick);
              window.__mynoteObserver.observe(document.body, { childList: true, subtree: true, characterData: true });
              window.__mynoteTimer = setInterval(tick, 500);
            })();
        """.trimIndent()

        val STOP_OBSERVING_JS = """
            (function () {
              if (window.__mynoteObserver) { window.__mynoteObserver.disconnect(); window.__mynoteObserver = null; }
              if (window.__mynoteTimer) { clearInterval(window.__mynoteTimer); window.__mynoteTimer = null; }
            })();
        """.trimIndent()

        val STOP_GENERATING_JS = """
            (function () {
              var nodes = document.querySelectorAll('[role="button"], button');
              for (var i = 0; i < nodes.length; i++) {
                var t = (nodes[i].textContent || '').trim();
                var a = nodes[i].getAttribute('aria-label') || '';
                if (t.indexOf('停止') >= 0 || a.indexOf('停止') >= 0) { nodes[i].click(); return; }
              }
            })();
        """.trimIndent()
    }
}
```

- [ ] **Step 7: 写 `AiDriverRegistryTest`**

```kotlin
package com.mynote.app.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiDriverRegistryTest {

    private class FakeDriver(override val id: String) : AiWebDriver {
        override val displayName = id
        override val homeUrl = "https://example.com/"
        override fun chatUrl(remoteChatId: String) = homeUrl + remoteChatId
        override fun parseChatId(url: String): String? = null
        override fun loginCheckJs() = ""
        override fun newChatJs() = ""
        override fun sendMessageJs(text: String) = ""
        override fun observeReplyJs() = ""
        override fun stopObservingJs() = ""
        override fun stopGeneratingJs() = ""
    }

    @Test
    fun defaultIsFirstAndFindMatchesId() {
        val a = FakeDriver("a")
        val b = FakeDriver("b")
        val registry = AiDriverRegistry(listOf(a, b))
        assertEquals(a, registry.default)
        assertEquals(b, registry.find("b"))
        assertNull(registry.find("missing"))
        assertEquals(listOf(a, b), registry.all)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyRegistryRejected() {
        AiDriverRegistry(emptyList())
    }
}
```

- [ ] **Step 8: 跑本任务测试**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.data.ai.*" --console=plain`
Expected: PASS。

- [ ] **Step 9: 提交**

```powershell
git add app/src/main/java/com/mynote/app/data/ai app/src/test/java/com/mynote/app/data/ai
git commit -m "feat: AI 服务驱动层（DeepSeek 适配 + 注册表）"
```

---

### Task 3: 发送内容构造 `AiPromptBuilder`

**Files:**
- Create: `app/src/main/java/com/mynote/app/data/ai/AiPromptBuilder.kt`
- Test: `app/src/test/java/com/mynote/app/data/ai/AiPromptBuilderTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.mynote.app.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiPromptBuilderTest {

    @Test
    fun newContextIncludesTitleBodyAndRequirement() {
        val text = AiPromptBuilder.build(
            noteTitle = "旅行清单",
            noteContent = "护照和充电器",
            userInput = "帮我补充几项",
            includeNoteContext = true
        )
        assertTrue(text.startsWith("【笔记正文】"))
        assertTrue(text.contains("# 旅行清单"))
        assertTrue(text.contains("护照和充电器"))
        assertTrue(text.contains("【要求】\n帮我补充几项"))
    }

    @Test
    fun existingContextSendsInputOnly() {
        val text = AiPromptBuilder.build("t", "c", "再短一点", includeNoteContext = false)
        assertEquals("再短一点", text)
    }

    @Test
    fun imageMarkupIsStripped() {
        val text = AiPromptBuilder.build(
            noteTitle = "",
            noteContent = "前文![](img/a.webp)后文",
            userInput = "润色",
            includeNoteContext = true
        )
        assertFalse(text.contains("img/a.webp"))
        assertTrue(text.contains("前文后文"))
    }

    @Test
    fun blankNoteContentFallsBackToInputOnly() {
        val text = AiPromptBuilder.build("", "   ", "随便聊聊", includeNoteContext = true)
        assertEquals("随便聊聊", text)
    }

    @Test
    fun inputIsTrimmed() {
        assertEquals("问题", AiPromptBuilder.build("", "", "  问题  ", includeNoteContext = false))
    }
}
```

- [ ] **Step 2: 跑测试确认失败（编译不过）**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.data.ai.AiPromptBuilderTest" --console=plain`
Expected: FAIL，`AiPromptBuilder` 未定义。

- [ ] **Step 3: 实现 `AiPromptBuilder`**

```kotlin
package com.mynote.app.data.ai

import com.mynote.app.ui.notes.NoteContentParser

object AiPromptBuilder {

    /**
     * 网页上下文未建立时（新会话 / 上下文丢失）附带笔记正文；
     * 已有上下文时只发用户输入（设计 §7）。
     */
    fun build(
        noteTitle: String,
        noteContent: String,
        userInput: String,
        includeNoteContext: Boolean
    ): String {
        val input = userInput.trim()
        if (!includeNoteContext) return input
        val plain = NoteContentParser.plainText(noteContent).trim()
        if (plain.isEmpty()) return input
        val titleLine = noteTitle.trim().takeIf { it.isNotEmpty() }?.let { "# $it\n" } ?: ""
        return "【笔记正文】\n$titleLine$plain\n\n【要求】\n$input"
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.data.ai.AiPromptBuilderTest" --console=plain`
Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add app/src/main/java/com/mynote/app/data/ai/AiPromptBuilder.kt app/src/test/java/com/mynote/app/data/ai/AiPromptBuilderTest.kt
git commit -m "feat: AI 发送内容构造（新会话附带笔记正文）"
```

---

### Task 4: 会话仓库 `AiChatRepository` 与设置 `AiSettingsStore`

**Files:**
- Create: `app/src/main/java/com/mynote/app/data/ai/AiChatRepository.kt`
- Create: `app/src/main/java/com/mynote/app/data/settings/AiSettingsStore.kt`
- Test: `app/src/test/java/com/mynote/app/data/ai/AiChatRepositoryTest.kt`
- Test: `app/src/test/java/com/mynote/app/data/settings/AiSettingsStoreTest.kt`

- [ ] **Step 1: 写失败测试 `AiChatRepositoryTest`**

```kotlin
package com.mynote.app.data.ai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mynote.app.data.db.AiMessageEntity
import com.mynote.app.data.db.AppDatabase
import com.mynote.app.data.db.NoteEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiChatRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: AiChatRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = AiChatRepository(db.aiSessionDao(), db.aiMessageDao())
    }

    @After
    fun teardown() {
        db.close()
    }

    private suspend fun note(): Long =
        db.noteDao().insert(NoteEntity(0, "t", "c", 1, 1, null, false, null))

    @Test
    fun createSessionAppendMessagesAndObserve() = runTest {
        val noteId = note()
        val sessionId = repo.createSession(noteId, "deepseek", "帮我总结", 100)
        repo.appendMessage(sessionId, AiMessageEntity.ROLE_USER, "帮我总结", AiMessageEntity.STATUS_DONE, 100)
        repo.appendMessage(sessionId, AiMessageEntity.ROLE_ASSISTANT, "好的", AiMessageEntity.STATUS_DONE, 200)

        val session = repo.observeSessions(noteId).first().single()
        assertEquals("deepseek", session.serviceId)
        assertEquals(null, session.remoteChatId)
        assertEquals(
            listOf("帮我总结", "好的"),
            repo.observeMessages(sessionId).first().map { it.content }
        )
    }

    @Test
    fun remoteChatIdAndTouchUpdateSession() = runTest {
        val noteId = note()
        val sessionId = repo.createSession(noteId, "deepseek", "t", 100)
        repo.updateRemoteChatId(sessionId, "chat-1")
        repo.touch(sessionId, 300)
        val session = repo.getSession(sessionId)!!
        assertEquals("chat-1", session.remoteChatId)
        assertEquals(300, session.updatedAt)
    }

    @Test
    fun touchReordersSessions() = runTest {
        val noteId = note()
        val first = repo.createSession(noteId, "deepseek", "first", 100)
        repo.createSession(noteId, "deepseek", "second", 200)
        repo.touch(first, 300)
        assertEquals(
            listOf("first", "second"),
            repo.observeSessions(noteId).first().map { it.title }
        )
    }

    @Test
    fun deleteSessionCascadesMessages() = runTest {
        val noteId = note()
        val sessionId = repo.createSession(noteId, "deepseek", "t", 100)
        repo.appendMessage(sessionId, AiMessageEntity.ROLE_USER, "q", AiMessageEntity.STATUS_DONE, 100)
        repo.deleteSession(sessionId)
        assertEquals(0, repo.observeSessions(noteId).first().size)
        assertEquals(0, repo.getMessages(sessionId).size)
    }
}
```

- [ ] **Step 2: 写失败测试 `AiSettingsStoreTest`**

```kotlin
package com.mynote.app.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiSettingsStoreTest {

    private lateinit var store: AiSettingsStore

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE).edit().clear().commit()
        store = AiSettingsStore(context)
    }

    @Test
    fun privacyAcceptanceIsPerService() {
        assertFalse(store.isPrivacyAccepted("deepseek"))
        store.acceptPrivacy("deepseek")
        assertTrue(store.isPrivacyAccepted("deepseek"))
        assertFalse(store.isPrivacyAccepted("doubao"))
    }

    @Test
    fun selectedServiceRoundTrips() {
        assertEquals(null, store.selectedServiceId())
        store.setSelectedServiceId("deepseek")
        assertEquals("deepseek", store.selectedServiceId())
    }
}
```

- [ ] **Step 3: 跑测试确认失败（编译不过）**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.data.ai.AiChatRepositoryTest" --tests "com.mynote.app.data.settings.AiSettingsStoreTest" --console=plain`
Expected: FAIL，两个类未定义。

- [ ] **Step 4: 实现 `AiChatRepository`**

```kotlin
package com.mynote.app.data.ai

import com.mynote.app.data.db.AiMessageDao
import com.mynote.app.data.db.AiMessageEntity
import com.mynote.app.data.db.AiSessionDao
import com.mynote.app.data.db.AiSessionEntity
import kotlinx.coroutines.flow.Flow

class AiChatRepository(
    private val sessionDao: AiSessionDao,
    private val messageDao: AiMessageDao
) {

    fun observeSessions(noteId: Long): Flow<List<AiSessionEntity>> = sessionDao.observeByNote(noteId)

    fun observeMessages(sessionId: Long): Flow<List<AiMessageEntity>> = messageDao.observeBySession(sessionId)

    suspend fun getSession(id: Long): AiSessionEntity? = sessionDao.getById(id)

    suspend fun createSession(noteId: Long, serviceId: String, title: String, now: Long): Long =
        sessionDao.insert(AiSessionEntity(0, noteId, serviceId, title, null, now, now))

    suspend fun updateRemoteChatId(sessionId: Long, remoteChatId: String) =
        sessionDao.updateRemoteChatId(sessionId, remoteChatId)

    suspend fun touch(sessionId: Long, now: Long) = sessionDao.touch(sessionId, now)

    suspend fun appendMessage(
        sessionId: Long,
        role: String,
        content: String,
        status: String,
        now: Long
    ): Long = messageDao.insert(AiMessageEntity(0, sessionId, role, content, status, now))

    suspend fun deleteSession(sessionId: Long) = sessionDao.deleteById(sessionId)

    suspend fun getMessages(sessionId: Long): List<AiMessageEntity> = messageDao.getBySession(sessionId)
}
```

- [ ] **Step 5: 实现 `AiSettingsStore`**

```kotlin
package com.mynote.app.data.settings

import android.content.Context

class AiSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)

    fun selectedServiceId(): String? = prefs.getString(KEY_SELECTED_SERVICE, null)

    fun setSelectedServiceId(id: String) {
        prefs.edit().putString(KEY_SELECTED_SERVICE, id).apply()
    }

    fun isPrivacyAccepted(serviceId: String): Boolean =
        prefs.getBoolean(privacyKey(serviceId), false)

    fun acceptPrivacy(serviceId: String) {
        prefs.edit().putBoolean(privacyKey(serviceId), true).apply()
    }

    private fun privacyKey(serviceId: String) = "privacy_accepted_$serviceId"

    private companion object {
        const val KEY_SELECTED_SERVICE = "selected_service_id"
    }
}
```

- [ ] **Step 6: 跑测试确认通过**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.data.ai.AiChatRepositoryTest" --tests "com.mynote.app.data.settings.AiSettingsStoreTest" --console=plain`
Expected: PASS。

- [ ] **Step 7: 提交**

```powershell
git add app/src/main/java/com/mynote/app/data/ai/AiChatRepository.kt app/src/main/java/com/mynote/app/data/settings/AiSettingsStore.kt app/src/test/java/com/mynote/app/data/ai/AiChatRepositoryTest.kt app/src/test/java/com/mynote/app/data/settings/AiSettingsStoreTest.kt
git commit -m "feat: AI 会话仓库与设置存储"
```

---

### Task 5: `AiResultApplier` + 编辑页内容状态升级为 `TextFieldValue`

说明：本任务只做「纯逻辑 + 编辑器底层改造」，不加 AI 按钮（避免依赖尚未实现的路由）；改完现有 113 个测试必须全绿。

**Files:**
- Create: `app/src/main/java/com/mynote/app/ui/notes/AiResultApplier.kt`
- Modify: `app/src/main/java/com/mynote/app/ui/notes/NoteEditScreen.kt`
- Test: `app/src/test/java/com/mynote/app/ui/notes/AiResultApplierTest.kt`

- [ ] **Step 1: 写失败测试 `AiResultApplierTest`**

```kotlin
package com.mynote.app.ui.notes

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiResultApplierTest {

    @Test
    fun insertAtCursorKeepsSurroundingText() {
        val current = TextFieldValue("ab", TextRange(1))
        val result = AiResultApplier.apply(current, AiResultApplier.Type.INSERT, "XY")
        assertEquals("aXYb", result.text)
        assertEquals(TextRange(3), result.selection)
    }

    @Test
    fun insertAtEnd() {
        val current = TextFieldValue("ab", TextRange(2))
        val result = AiResultApplier.apply(current, AiResultApplier.Type.INSERT, "!")
        assertEquals("ab!", result.text)
        assertEquals(TextRange(3), result.selection)
    }

    @Test
    fun replaceSelection() {
        val current = TextFieldValue("hello world", TextRange(6, 11))
        val result = AiResultApplier.apply(current, AiResultApplier.Type.REPLACE, "地球")
        assertEquals("hello 地球", result.text)
        assertEquals(TextRange(8), result.selection)
    }

    @Test
    fun replaceWithCollapsedSelectionIsNoOp() {
        val current = TextFieldValue("hello", TextRange(2))
        val result = AiResultApplier.apply(current, AiResultApplier.Type.REPLACE, "X")
        assertEquals("hello", result.text)
    }

    @Test
    fun selectionOutOfRangeIsClamped() {
        val current = TextFieldValue("ab", TextRange(5))
        val result = AiResultApplier.apply(current, AiResultApplier.Type.INSERT, "X")
        assertEquals("abX", result.text)
    }
}
```

- [ ] **Step 2: 跑测试确认失败（编译不过）**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.ui.notes.AiResultApplierTest" --console=plain`
Expected: FAIL，`AiResultApplier` 未定义。

- [ ] **Step 3: 实现 `AiResultApplier`**

```kotlin
package com.mynote.app.ui.notes

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** AI 回答落入编辑器的纯逻辑：插入到光标 / 替换选区。 */
object AiResultApplier {

    enum class Type { INSERT, REPLACE }

    fun apply(current: TextFieldValue, type: Type, text: String): TextFieldValue {
        val length = current.text.length
        return when (type) {
            Type.INSERT -> {
                val at = current.selection.start.coerceIn(0, length)
                val newText = current.text.substring(0, at) + text + current.text.substring(at)
                TextFieldValue(newText, TextRange(at + text.length))
            }
            Type.REPLACE -> {
                if (current.selection.collapsed) return current
                val start = current.selection.min.coerceIn(0, length)
                val end = current.selection.max.coerceIn(0, length)
                val newText = current.text.substring(0, start) + text + current.text.substring(end)
                TextFieldValue(newText, TextRange(start + text.length))
            }
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.ui.notes.AiResultApplierTest" --console=plain`
Expected: PASS。

- [ ] **Step 5: 编辑页 `content` 从 `String` 改为 `TextFieldValue`**

在 `NoteEditScreen.kt` 依次做以下替换：

1) import 区增加：

```kotlin
import androidx.compose.ui.text.input.TextFieldValue
```

2) 状态声明（`var content by rememberSaveable(noteId) { mutableStateOf("") }`）改为：

```kotlin
    var content by rememberSaveable(noteId, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
```

3) `LaunchedEffect(note)` 块改为：

```kotlin
    LaunchedEffect(note) {
        if (note != null && title.isEmpty() && content.text.isEmpty()) {
            title = note!!.title
            content = TextFieldValue(note!!.content)
            selectedCategoryId = note!!.categoryId
            pinned = note!!.pinned
        }
    }
```

4) 插图回调改为（顺带修正为"插入到光标处"）：

```kotlin
        uri?.let { vm.insertImage(it) { markup -> content = AiResultApplier.apply(content, AiResultApplier.Type.INSERT, markup) } }
```

5) 保存调用改为：

```kotlin
                        vm.save(title, content.text, selectedCategoryId, pinned, note?.color) { warning ->
```

6) 导出按钮显隐条件与预览、导出对话框、内容判断改为：

```kotlin
                    if (noteId != null || title.isNotBlank() || content.text.isNotBlank()) {
```

```kotlin
                    items(NoteContentParser.parse(content.text)) { block ->
```

```kotlin
                val hasContent = title.isNotBlank() || content.text.isNotBlank()
```

```kotlin
        NoteExportDialog(
            title = title,
            content = content.text,
```

7) `OutlinedTextField` 的正文输入框保持不变（`TextFieldValue` 是该重载的合法类型）：

```kotlin
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("开始记录…") }
                )
```

- [ ] **Step 6: 全量测试 + 编译**

Run: `.\gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL，113 个测试全绿。

- [ ] **Step 7: 提交**

```powershell
git add app/src/main/java/com/mynote/app/ui/notes/NoteEditScreen.kt app/src/main/java/com/mynote/app/ui/notes/AiResultApplier.kt app/src/test/java/com/mynote/app/ui/notes/AiResultApplierTest.kt
git commit -m "refactor: 编辑页正文改为 TextFieldValue 并新增 AI 结果落点逻辑"
```

---

### Task 6: `AiWebEventParser` + `WebViewAiSession`

**Files:**
- Create: `app/src/main/java/com/mynote/app/data/ai/AiWebEventParser.kt`
- Create: `app/src/main/java/com/mynote/app/data/ai/WebViewAiSession.kt`
- Test: `app/src/test/java/com/mynote/app/data/ai/AiWebEventParserTest.kt`

- [ ] **Step 1: 写失败测试 `AiWebEventParserTest`**

```kotlin
package com.mynote.app.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiWebEventParserTest {

    @Test
    fun parsesLoginState() {
        val event = AiWebEventParser.parse("""{"type":"loginState","payload":{"loggedIn":true}}""")
        assertEquals(AiWebEvent.LoginState(true), event)
    }

    @Test
    fun parsesReplyChunkAndDone() {
        assertEquals(
            AiWebEvent.ReplyChunk("你好"),
            AiWebEventParser.parse("""{"type":"replyChunk","payload":{"text":"你好"}}""")
        )
        assertEquals(
            AiWebEvent.ReplyDone("完整"),
            AiWebEventParser.parse("""{"type":"replyDone","payload":{"text":"完整"}}""")
        )
    }

    @Test
    fun sendFailedBecomesReplyError() {
        val event = AiWebEventParser.parse("""{"type":"sendFailed","payload":{"reason":"没找到"}}""")
        assertTrue(event is AiWebEvent.ReplyError)
        assertEquals("没找到", (event as AiWebEvent.ReplyError).reason)
    }

    @Test
    fun unknownTypeAndMalformedJsonReturnNull() {
        assertNull(AiWebEventParser.parse("""{"type":"whatever","payload":{}}"""))
        assertNull(AiWebEventParser.parse("not json"))
    }
}
```

- [ ] **Step 2: 跑测试确认失败（编译不过）**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.data.ai.AiWebEventParserTest" --console=plain`
Expected: FAIL。

- [ ] **Step 3: 实现 `AiWebEventParser`**

```kotlin
package com.mynote.app.data.ai

import org.json.JSONObject

/** 解析 JS 桥发来的 JSON 事件；未知类型 / 坏 JSON 返回 null（忽略即可）。 */
object AiWebEventParser {

    fun parse(json: String): AiWebEvent? = try {
        val root = JSONObject(json)
        val payload = root.optJSONObject("payload") ?: JSONObject()
        when (root.getString("type")) {
            "loginState" -> AiWebEvent.LoginState(payload.optBoolean("loggedIn", false))
            "replyChunk" -> AiWebEvent.ReplyChunk(payload.optString("text"))
            "replyDone" -> AiWebEvent.ReplyDone(payload.optString("text"))
            "replyError", "sendFailed" -> AiWebEvent.ReplyError(payload.optString("reason", "未知错误"))
            "chatId" -> payload.optString("id").takeIf { it.isNotEmpty() }?.let { AiWebEvent.ChatId(it) }
            "pageReady" -> AiWebEvent.PageReady
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}
```

- [ ] **Step 4: 实现 `WebViewAiSession`**

```kotlin
package com.mynote.app.data.ai

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

internal class MyNoteJsBridge(private val onJson: (String) -> Unit) {
    @JavascriptInterface
    fun emit(json: String) {
        onJson(json)
    }
}

class WebViewAiSession(initialDriver: AiWebDriver) : AiWebSession {

    private val _events = MutableSharedFlow<AiWebEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val events: SharedFlow<AiWebEvent> = _events

    override var driver: AiWebDriver = initialDriver
        private set

    private var webView: WebView? = null
    private var desiredUrl: String? = null
    private var pendingSend: String? = null
    private var pendingNewChat = false
    private var pageReady = false
    private var loggedIn: Boolean? = null

    private val bridge = MyNoteJsBridge { json ->
        val event = AiWebEventParser.parse(json)
        if (event != null) {
            val view = webView
            if (view != null) view.post { handleEvent(event) } else handleEvent(event)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun attach(webView: WebView) {
        this.webView = webView
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.addJavascriptInterface(bridge, BRIDGE_NAME)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                pageReady = true
                _events.tryEmit(AiWebEvent.PageReady)
                driver.parseChatId(url ?: view.url.orEmpty())?.let { _events.tryEmit(AiWebEvent.ChatId(it)) }
                view.evaluateJavascript(BOOTSTRAP_JS, null)
                if (pendingNewChat) {
                    pendingNewChat = false
                    view.evaluateJavascript(driver.newChatJs(), null)
                }
                view.evaluateJavascript(driver.loginCheckJs(), null)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    _events.tryEmit(AiWebEvent.PageError(error.description?.toString() ?: "网页加载失败"))
                }
            }
        }
        val start = desiredUrl ?: driver.homeUrl
        desiredUrl = start
        webView.loadUrl(start)
    }

    override fun openNewChat() {
        pendingSend = null
        pendingNewChat = true
        val url = driver.homeUrl
        desiredUrl = url
        webView?.loadUrl(url)
    }

    override fun openChat(remoteChatId: String) {
        pendingSend = null
        pendingNewChat = false
        val url = driver.chatUrl(remoteChatId)
        desiredUrl = url
        webView?.loadUrl(url)
    }

    override fun send(text: String) {
        pendingSend = text
        val view = webView ?: return
        if (pageReady && loggedIn == true) {
            flushPendingSend()
        } else {
            view.evaluateJavascript(driver.loginCheckJs(), null)
        }
    }

    override fun stop() {
        val view = webView ?: return
        view.evaluateJavascript(driver.stopObservingJs(), null)
        view.evaluateJavascript(driver.stopGeneratingJs(), null)
    }

    override fun release() {
        webView?.removeJavascriptInterface(BRIDGE_NAME)
        webView = null
        pageReady = false
        loggedIn = null
    }

    private fun handleEvent(event: AiWebEvent) {
        when (event) {
            is AiWebEvent.LoginState -> {
                loggedIn = event.loggedIn
                if (event.loggedIn) flushPendingSend()
            }
            AiWebEvent.PageReady -> pageReady = true
            else -> Unit
        }
        _events.tryEmit(event)
    }

    private fun flushPendingSend() {
        val view = webView ?: return
        val text = pendingSend ?: return
        pendingSend = null
        view.evaluateJavascript(driver.sendMessageJs(text), null)
        view.postDelayed({
            webView?.evaluateJavascript(driver.observeReplyJs(), null)
        }, 300)
    }

    private companion object {
        const val BRIDGE_NAME = "MyNoteJsBridge"
        val BOOTSTRAP_JS = """
            window.__mynote = window.__mynote || {};
            window.__mynote.emit = function (type, payload) {
              try { MyNoteJsBridge.emit(JSON.stringify({ type: type, payload: payload || {} })); } catch (e) {}
            };
        """.trimIndent()
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.data.ai.AiWebEventParserTest" --console=plain`
Expected: PASS（`WebViewAiSession` 编译通过即可，运行行为走真机清单）。

- [ ] **Step 6: 提交**

```powershell
git add app/src/main/java/com/mynote/app/data/ai/AiWebEventParser.kt app/src/main/java/com/mynote/app/data/ai/WebViewAiSession.kt app/src/test/java/com/mynote/app/data/ai/AiWebEventParserTest.kt
git commit -m "feat: WebView AI 会话桥接与事件解析"
```

---

### Task 7: `AiChatViewModel`（会话编排 + 流式状态机）

**Files:**
- Create: `app/src/main/java/com/mynote/app/ui/ai/AiChatViewModel.kt`
- Test: `app/src/test/java/com/mynote/app/ui/ai/AiChatViewModelTest.kt`

- [ ] **Step 1: 写失败测试 `AiChatViewModelTest`**

```kotlin
package com.mynote.app.ui.ai

import android.content.Context
import android.webkit.WebView
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.viewModelScope
import com.mynote.app.data.ai.AiChatRepository
import com.mynote.app.data.ai.AiDriverRegistry
import com.mynote.app.data.ai.AiWebDriver
import com.mynote.app.data.ai.AiWebEvent
import com.mynote.app.data.ai.AiWebSession
import com.mynote.app.data.db.AiMessageEntity
import com.mynote.app.data.db.AppDatabase
import com.mynote.app.data.image.ImageStore
import com.mynote.app.data.repository.NoteRepository
import com.mynote.app.data.settings.AiSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiChatViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var aiRepo: AiChatRepository
    private lateinit var noteRepo: NoteRepository
    private lateinit var settings: AiSettingsStore
    private lateinit var fake: FakeAiWebSession
    private val vms = mutableListOf<AiChatViewModel>()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        aiRepo = AiChatRepository(db.aiSessionDao(), db.aiMessageDao())
        noteRepo = NoteRepository(db.noteDao(), db.categoryDao(), db.noteRevisionDao(), ImageStore(context), db)
        settings = AiSettingsStore(context)
        settings.acceptPrivacy("deepseek")
        fake = FakeAiWebSession(FakeDriver)
    }

    @After
    fun teardown() {
        vms.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
        db.close()
    }

    private fun createVm(
        noteId: Long,
        title: String = "标题",
        content: String = "正文",
        store: AiSettingsStore = settings
    ): AiChatViewModel {
        val vm = AiChatViewModel(
            noteId = noteId,
            noteTitle = title,
            noteContent = content,
            aiRepository = aiRepo,
            noteRepository = noteRepo,
            settingsStore = store,
            externalScope = CoroutineScope(dispatcher),
            registry = AiDriverRegistry(listOf(FakeDriver)),
            webSessionFactory = { fake }
        )
        vms += vm
        return vm
    }

    @Test
    fun firstSendCreatesSessionAndIncludesNoteContext() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.send("帮我总结")
        vm.state.first { it.sending }

        val session = aiRepo.observeSessions(noteId).first { it.isNotEmpty() }.single()
        assertEquals("帮我总结", session.title)
        assertEquals("deepseek", session.serviceId)
        assertTrue(fake.sent.single().contains("【笔记正文】"))
        assertTrue(fake.sent.single().contains("正文"))
        assertTrue(fake.sent.single().contains("帮我总结"))

        val messages = aiRepo.observeMessages(session.id).first { it.isNotEmpty() }
        assertEquals(1, messages.size)
        assertEquals(AiMessageEntity.ROLE_USER, messages[0].role)
        assertEquals("帮我总结", messages[0].content)
    }

    @Test
    fun laterSendOmitsNoteContext() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.send("第一问")
        vm.state.first { it.sending }

        fake.emit(AiWebEvent.ChatId("chat-1"))
        vm.state.first { it.sessions.firstOrNull()?.remoteChatId == "chat-1" }
        fake.emit(AiWebEvent.ReplyDone("答"))
        vm.state.first { !it.sending }

        vm.send("第二问")
        vm.state.first { it.sending && fake.sent.size == 2 }
        assertTrue(fake.sent[0].contains("【笔记正文】"))
        assertEquals("第二问", fake.sent[1])
    }

    @Test
    fun replyDoneStoresAssistantMessage() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.send("问")
        vm.state.first { it.sending }
        val sessionId = aiRepo.observeSessions(noteId).first().single().id

        fake.emit(AiWebEvent.ReplyChunk("答"))
        vm.state.first { it.streamingText == "答" }
        fake.emit(AiWebEvent.ReplyDone("答"))
        vm.state.first { !it.sending }

        val messages = aiRepo.observeMessages(sessionId).first { it.size == 2 }
        assertEquals(AiMessageEntity.ROLE_ASSISTANT, messages[1].role)
        assertEquals("答", messages[1].content)
        assertEquals(AiMessageEntity.STATUS_DONE, messages[1].status)
    }

    @Test
    fun replyErrorWithPartialStoresInterrupted() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.send("问")
        vm.state.first { it.sending }
        val sessionId = aiRepo.observeSessions(noteId).first().single().id

        fake.emit(AiWebEvent.ReplyChunk("半截"))
        vm.state.first { it.streamingText == "半截" }
        fake.emit(AiWebEvent.ReplyError("回答超时"))
        vm.state.first { !it.sending && it.banner != null }

        val messages = aiRepo.observeMessages(sessionId).first { it.size == 2 }
        assertEquals("半截", messages[1].content)
        assertEquals(AiMessageEntity.STATUS_INTERRUPTED, messages[1].status)
        assertTrue(vm.state.value.webVisible)
    }

    @Test
    fun replyErrorWithoutPartialStoresFailed() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.send("问")
        vm.state.first { it.sending }
        val sessionId = aiRepo.observeSessions(noteId).first().single().id

        fake.emit(AiWebEvent.ReplyError("未找到输入框"))
        vm.state.first { !it.sending && it.banner != null }

        val messages = aiRepo.observeMessages(sessionId).first { it.size == 2 }
        assertEquals(AiMessageEntity.STATUS_FAILED, messages[1].status)
        assertEquals("", messages[1].content)
    }

    @Test
    fun notLoggedInShowsBannerAndWeb() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        fake.emit(AiWebEvent.LoginState(false))
        vm.state.first { it.banner != null }
        assertTrue(vm.state.value.banner!!.contains("登录"))
        assertTrue(vm.state.value.webVisible)
    }

    @Test
    fun newChatClearsCurrentSession() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.send("问")
        vm.state.first { it.sending }
        assertTrue(vm.state.value.currentSessionId != null)

        vm.newChat()
        assertNull(vm.state.value.currentSessionId)
        assertTrue(vm.state.value.messages.isEmpty())
    }

    @Test
    fun deleteSessionRemovesIt() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.send("问")
        vm.state.first { it.sending }
        val sessionId = aiRepo.observeSessions(noteId).first().single().id
        fake.emit(AiWebEvent.ReplyDone("答"))
        vm.state.first { !it.sending }

        vm.deleteSession(sessionId)
        vm.state.first { it.sessions.isEmpty() }
        assertEquals(0, aiRepo.getMessages(sessionId).size)
        assertNull(vm.state.value.currentSessionId)
    }

    @Test
    fun saveAsNoteCreatesNoteWithFirstLineTitle() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val vm = createVm(noteId)
        vm.saveAsNote("第一行\n第二行")
        vm.state.first { it.snackbar != null }

        val notes = noteRepo.observeNotes().first()
        val created = notes.first { it.content == "第一行\n第二行" }
        assertEquals("第一行", created.title)
    }

    @Test
    fun privacyMustBeAcceptedBeforeSend() = runTest(dispatcher) {
        val noteId = noteRepo.saveNote(null, "标题", "正文", null, false, null)
        val fresh = AiSettingsStore(context)
        val vm = createVm(noteId, store = fresh)

        assertFalse(vm.state.value.privacyAccepted)
        vm.send("问")
        assertNull(vm.state.value.currentSessionId)
        assertTrue(fake.sent.isEmpty())

        vm.acceptPrivacy()
        assertTrue(vm.state.value.privacyAccepted)
        assertTrue(fresh.isPrivacyAccepted("deepseek"))
    }

    private object FakeDriver : AiWebDriver {
        override val id = "deepseek"
        override val displayName = "DeepSeek"
        override val homeUrl = "https://example.com/"
        override fun chatUrl(remoteChatId: String) = homeUrl + remoteChatId
        override fun parseChatId(url: String): String? = null
        override fun loginCheckJs() = ""
        override fun newChatJs() = ""
        override fun sendMessageJs(text: String) = ""
        override fun observeReplyJs() = ""
        override fun stopObservingJs() = ""
        override fun stopGeneratingJs() = ""
    }

    private class FakeAiWebSession(override val driver: AiWebDriver) : AiWebSession {
        private val _events = MutableSharedFlow<AiWebEvent>(extraBufferCapacity = 16)
        override val events: SharedFlow<AiWebEvent> = _events

        val sent = mutableListOf<String>()
        var newChatCount = 0

        override fun attach(webView: WebView) = Unit
        override fun openNewChat() { newChatCount++ }
        override fun openChat(remoteChatId: String) = Unit
        override fun send(text: String) { sent += text }
        override fun stop() = Unit
        override fun release() = Unit

        fun emit(event: AiWebEvent) { _events.tryEmit(event) }
    }
}
```

- [ ] **Step 2: 跑测试确认失败（编译不过）**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.ui.ai.AiChatViewModelTest" --console=plain`
Expected: FAIL，`AiChatViewModel` 未定义。

- [ ] **Step 3: 实现 `AiChatViewModel`**

```kotlin
package com.mynote.app.ui.ai

import android.webkit.WebView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mynote.app.data.ai.AiChatRepository
import com.mynote.app.data.ai.AiDriverRegistry
import com.mynote.app.data.ai.AiPromptBuilder
import com.mynote.app.data.ai.AiWebDriver
import com.mynote.app.data.ai.AiWebEvent
import com.mynote.app.data.ai.AiWebSession
import com.mynote.app.data.db.AiMessageEntity
import com.mynote.app.data.db.AiSessionEntity
import com.mynote.app.data.repository.NoteRepository
import com.mynote.app.data.settings.AiSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AiChatViewModel(
    private val noteId: Long,
    private val noteTitle: String,
    private val noteContent: String,
    private val aiRepository: AiChatRepository,
    private val noteRepository: NoteRepository,
    private val settingsStore: AiSettingsStore,
    private val externalScope: CoroutineScope,
    registry: AiDriverRegistry,
    webSessionFactory: (AiWebDriver) -> AiWebSession
) : ViewModel() {

    data class UiState(
        val sessions: List<AiSessionEntity> = emptyList(),
        val currentSessionId: Long? = null,
        val messages: List<AiMessageEntity> = emptyList(),
        val streamingText: String = "",
        val sending: Boolean = false,
        val webVisible: Boolean = false,
        val loggedIn: Boolean? = null,
        val privacyAccepted: Boolean = false,
        val banner: String? = null,
        val snackbar: String? = null
    )

    private val driver: AiWebDriver =
        settingsStore.selectedServiceId()?.let { registry.find(it) } ?: registry.default

    private val webSession: AiWebSession = webSessionFactory(driver)

    private val _state = MutableStateFlow(
        UiState(privacyAccepted = settingsStore.isPrivacyAccepted(driver.id))
    )
    val state: StateFlow<UiState> = _state

    private var messagesJob: Job? = null
    private var initialSelectionDone = false
    private var userStartedNewChat = false

    init {
        viewModelScope.launch {
            aiRepository.observeSessions(noteId).collectLatest { sessions ->
                _state.update { it.copy(sessions = sessions) }
                if (!initialSelectionDone && !userStartedNewChat) {
                    initialSelectionDone = true
                    if (_state.value.currentSessionId == null) {
                        if (sessions.isNotEmpty()) selectSession(sessions.first().id)
                        else webSession.openNewChat()
                    }
                }
            }
        }
        viewModelScope.launch {
            webSession.events.collect { handleWebEvent(it) }
        }
    }

    fun onWebViewAttached(webView: WebView) {
        webSession.attach(webView)
    }

    fun releaseWebView() {
        webSession.release()
    }

    fun selectSession(sessionId: Long) {
        val session = _state.value.sessions.firstOrNull { it.id == sessionId } ?: return
        userStartedNewChat = false
        _state.update { it.copy(currentSessionId = sessionId, streamingText = "", banner = null) }
        observeMessages(sessionId)
        if (session.remoteChatId != null) webSession.openChat(session.remoteChatId)
        else webSession.openNewChat()
    }

    fun newChat() {
        userStartedNewChat = true
        _state.update { it.copy(currentSessionId = null, streamingText = "", banner = null) }
        observeMessages(null)
        webSession.openNewChat()
    }

    fun send(rawInput: String) {
        val input = rawInput.trim()
        val snapshot = _state.value
        if (input.isEmpty() || snapshot.sending || !snapshot.privacyAccepted) return
        val sessionId = snapshot.currentSessionId
        if (sessionId != null) {
            val session = snapshot.sessions.firstOrNull { it.id == sessionId }
            viewModelScope.launch { launchSendNow(sessionId, session?.remoteChatId, input) }
        } else {
            viewModelScope.launch {
                val now = System.currentTimeMillis()
                val id = aiRepository.createSession(noteId, driver.id, titleFor(input), now)
                _state.update { it.copy(currentSessionId = id) }
                observeMessages(id)
                launchSendNow(id, null, input)
            }
        }
    }

    fun stop() {
        if (!_state.value.sending) return
        webSession.stop()
        finalizeAssistant(AiMessageEntity.STATUS_INTERRUPTED)
    }

    fun deleteSession(sessionId: Long) {
        if (_state.value.sending && sessionId == _state.value.currentSessionId) return
        viewModelScope.launch {
            aiRepository.deleteSession(sessionId)
            if (_state.value.currentSessionId == sessionId) {
                _state.update { it.copy(currentSessionId = null, streamingText = "") }
                observeMessages(null)
            }
        }
    }

    fun acceptPrivacy() {
        settingsStore.acceptPrivacy(driver.id)
        _state.update { it.copy(privacyAccepted = true) }
    }

    fun toggleWebVisible() {
        _state.update { it.copy(webVisible = !it.webVisible) }
    }

    fun saveAsNote(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            noteRepository.saveNote(null, titleFor(text), text, null, false, null)
            _state.update { it.copy(snackbar = "已存为新笔记") }
        }
    }

    fun consumeSnackbar() {
        _state.update { it.copy(snackbar = null) }
    }

    override fun onCleared() {
        super.onCleared()
        val snapshot = _state.value
        val sessionId = snapshot.currentSessionId
        if (snapshot.sending && sessionId != null) {
            val text = snapshot.streamingText
            externalScope.launch {
                val now = System.currentTimeMillis()
                aiRepository.appendMessage(
                    sessionId, AiMessageEntity.ROLE_ASSISTANT, text,
                    AiMessageEntity.STATUS_INTERRUPTED, now
                )
                aiRepository.touch(sessionId, now)
            }
        }
        webSession.release()
    }

    private suspend fun launchSendNow(sessionId: Long, remoteChatId: String?, input: String) {
        val payload = AiPromptBuilder.build(
            noteTitle = noteTitle,
            noteContent = noteContent,
            userInput = input,
            includeNoteContext = remoteChatId == null
        )
        val now = System.currentTimeMillis()
        aiRepository.appendMessage(sessionId, AiMessageEntity.ROLE_USER, input, AiMessageEntity.STATUS_DONE, now)
        _state.update { it.copy(sending = true, streamingText = "", banner = null) }
        webSession.send(payload)
    }

    private fun observeMessages(sessionId: Long?) {
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            if (sessionId == null) {
                _state.update { it.copy(messages = emptyList()) }
            } else {
                aiRepository.observeMessages(sessionId).collectLatest { messages ->
                    _state.update { it.copy(messages = messages) }
                }
            }
        }
    }

    private fun handleWebEvent(event: AiWebEvent) {
        when (event) {
            is AiWebEvent.LoginState -> _state.update {
                it.copy(
                    loggedIn = event.loggedIn,
                    banner = if (event.loggedIn) null else "请先登录 ${driver.displayName}，登录后重新发送",
                    webVisible = it.webVisible || !event.loggedIn
                )
            }
            is AiWebEvent.ChatId -> {
                val sessionId = _state.value.currentSessionId ?: return
                viewModelScope.launch { aiRepository.updateRemoteChatId(sessionId, event.id) }
            }
            is AiWebEvent.ReplyChunk -> _state.update { it.copy(streamingText = event.text) }
            is AiWebEvent.ReplyDone -> finalizeAssistant(AiMessageEntity.STATUS_DONE, event.text)
            is AiWebEvent.ReplyError -> {
                if (!_state.value.sending) return
                val partial = _state.value.streamingText
                finalizeAssistant(
                    if (partial.isBlank()) AiMessageEntity.STATUS_FAILED
                    else AiMessageEntity.STATUS_INTERRUPTED
                )
                _state.update {
                    it.copy(banner = "${event.reason}（可显示网页手动操作）", webVisible = true)
                }
            }
            is AiWebEvent.PageError -> {
                if (_state.value.sending) finalizeAssistant(AiMessageEntity.STATUS_FAILED)
                _state.update { it.copy(banner = event.description) }
            }
            AiWebEvent.PageReady -> _state.update { it.copy(banner = null) }
        }
    }

    private fun finalizeAssistant(status: String, textOverride: String? = null) {
        val snapshot = _state.value
        if (!snapshot.sending) return
        val sessionId = snapshot.currentSessionId ?: return
        val text = textOverride ?: snapshot.streamingText
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            aiRepository.appendMessage(sessionId, AiMessageEntity.ROLE_ASSISTANT, text, status, now)
            aiRepository.touch(sessionId, now)
        }
        _state.update { it.copy(sending = false, streamingText = "") }
    }

    private fun titleFor(input: String): String {
        val firstLine = input.lineSequence().firstOrNull()?.trim().orEmpty()
        if (firstLine.isEmpty()) return "新对话"
        return if (firstLine.length > 20) firstLine.take(20) + "…" else firstLine
    }

    companion object {
        fun factory(
            noteId: Long,
            noteTitle: String,
            noteContent: String,
            aiRepository: AiChatRepository,
            noteRepository: NoteRepository,
            settingsStore: AiSettingsStore,
            externalScope: CoroutineScope,
            registry: AiDriverRegistry,
            webSessionFactory: (AiWebDriver) -> AiWebSession
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AiChatViewModel(
                    noteId, noteTitle, noteContent, aiRepository, noteRepository,
                    settingsStore, externalScope, registry, webSessionFactory
                )
            }
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.ui.ai.AiChatViewModelTest" --console=plain`
Expected: PASS（11 个用例）。

- [ ] **Step 5: 提交**

```powershell
git add app/src/main/java/com/mynote/app/ui/ai/AiChatViewModel.kt app/src/test/java/com/mynote/app/ui/ai/AiChatViewModelTest.kt
git commit -m "feat: AI 聊天 ViewModel（会话编排与流式状态机）"
```

---

### Task 8: AI 聊天页 + 导航接线 + INTERNET 权限 + 编辑页入口

**Files:**
- Create: `app/src/main/java/com/mynote/app/ui/ai/AiChatScreen.kt`
- Modify: `app/src/main/java/com/mynote/app/ui/navigation/AppNavHost.kt`
- Modify: `app/src/main/java/com/mynote/app/ui/notes/NoteEditScreen.kt`
- Modify: `app/src/main/java/com/mynote/app/di/AppContainer.kt`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: 新增 `AiChatScreen.kt`（完整文件）**

```kotlin
package com.mynote.app.ui.ai

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mynote.app.data.ai.AiChatRepository
import com.mynote.app.data.ai.AiDriverRegistry
import com.mynote.app.data.ai.AiWebDriver
import com.mynote.app.data.ai.AiWebSession
import com.mynote.app.data.db.AiMessageEntity
import com.mynote.app.data.db.AiSessionEntity
import com.mynote.app.data.repository.NoteRepository
import com.mynote.app.data.settings.AiSettingsStore
import com.mynote.app.util.TimeFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatScreen(
    noteId: Long,
    noteTitle: String,
    noteContent: String,
    hasSelection: Boolean,
    aiRepository: AiChatRepository,
    noteRepository: NoteRepository,
    registry: AiDriverRegistry,
    settingsStore: AiSettingsStore,
    externalScope: CoroutineScope,
    webSessionFactory: (AiWebDriver) -> AiWebSession,
    onApplyResult: (type: String, text: String) -> Unit,
    onBack: () -> Unit
) {
    val vm: AiChatViewModel = viewModel(
        key = "ai_chat_$noteId",
        factory = AiChatViewModel.factory(
            noteId, noteTitle, noteContent, aiRepository, noteRepository,
            settingsStore, externalScope, registry, webSessionFactory
        )
    )
    val state by vm.state.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    DisposableEffect(Unit) {
        onDispose {
            vm.releaseWebView()
            webView?.destroy()
            webView = null
        }
    }

    state.snackbar?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message)
            vm.consumeSnackbar()
        }
    }

    BackHandler(enabled = state.webVisible) { vm.toggleWebVisible() }

    if (!state.privacyAccepted) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("发送到 DeepSeek 网页") },
            text = {
                Text("AI 助手会把你输入的内容与笔记正文发送到 DeepSeek 网页处理，回答由网页实时返回。请遵守服务条款，避免发送敏感信息。")
            },
            confirmButton = {
                TextButton(onClick = { vm.acceptPrivacy() }) { Text("同意并继续") }
            }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !state.webVisible,
        drawerContent = {
            ModalDrawerSheet {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("AI 会话", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        vm.newChat()
                        scope.launch { drawerState.close() }
                    }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text("新对话")
                    }
                }
                HorizontalDivider()
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.sessions, key = { it.id }) { session ->
                        SessionItem(
                            session = session,
                            selected = session.id == state.currentSessionId,
                            onClick = {
                                vm.selectSession(session.id)
                                scope.launch { drawerState.close() }
                            },
                            onDelete = { vm.deleteSession(session.id) }
                        )
                    }
                }
            }
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            state.sessions.firstOrNull { it.id == state.currentSessionId }?.title
                                ?: "AI 助手",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "会话列表")
                        }
                        IconButton(onClick = { vm.toggleWebVisible() }) {
                            Icon(
                                Icons.Default.Public,
                                contentDescription = if (state.webVisible) "返回聊天" else "显示网页"
                            )
                        }
                    }
                )
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                AndroidView(
                    factory = { context ->
                        WebView(context).also { view ->
                            webView = view
                            vm.onWebViewAttached(view)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                if (!state.webVisible) {
                    ChatLayer(
                        state = state,
                        hasSelection = hasSelection,
                        input = input,
                        onInputChange = { input = it },
                        onSend = {
                            vm.send(input)
                            input = ""
                        },
                        onStop = { vm.stop() },
                        onInsert = { text -> onApplyResult("insert", text) },
                        onReplace = { text -> onApplyResult("replace", text) },
                        onCopy = { text ->
                            clipboard.setText(AnnotatedString(text))
                            scope.launch { snackbarHostState.showSnackbar("已复制") }
                        },
                        onSaveAsNote = { text -> vm.saveAsNote(text) }
                    )
                } else {
                    SmallFloatingActionButton(
                        onClick = { vm.toggleWebVisible() },
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                    ) {
                        Icon(Icons.Default.Chat, contentDescription = "返回聊天")
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionItem(
    session: AiSessionEntity,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    ListItem(
        headlineContent = { Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(TimeFormat.dateTime(session.updatedAt)) },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除会话")
            }
        },
        colors = androidx.compose.material3.ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun ChatLayer(
    state: AiChatViewModel.UiState,
    hasSelection: Boolean,
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onInsert: (String) -> Unit,
    onReplace: (String) -> Unit,
    onCopy: (String) -> Unit,
    onSaveAsNote: (String) -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            state.banner?.let { banner ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        banner,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            if (state.messages.isEmpty() && !state.sending && state.streamingText.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "有问题随时问我，回答会留档在这篇笔记里",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        MessageBubble(
                            message = message,
                            hasSelection = hasSelection,
                            onInsert = onInsert,
                            onReplace = onReplace,
                            onCopy = onCopy,
                            onSaveAsNote = onSaveAsNote
                        )
                    }
                    if (state.sending || state.streamingText.isNotEmpty()) {
                        item(key = "streaming") {
                            StreamingBubble(text = state.streamingText, sending = state.sending)
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("问点什么…") },
                    maxLines = 5
                )
                FilledIconButton(
                    onClick = { if (state.sending) onStop() else onSend() },
                    enabled = state.sending || input.isNotBlank()
                ) {
                    Icon(
                        if (state.sending) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (state.sending) "停止" else "发送"
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: AiMessageEntity,
    hasSelection: Boolean,
    onInsert: (String) -> Unit,
    onReplace: (String) -> Unit,
    onCopy: (String) -> Unit,
    onSaveAsNote: (String) -> Unit
) {
    val isUser = message.role == AiMessageEntity.ROLE_USER
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    message.content.ifEmpty { "（没有内容）" },
                    style = MaterialTheme.typography.bodyMedium
                )
                if (message.status != AiMessageEntity.STATUS_DONE) {
                    Text(
                        if (message.status == AiMessageEntity.STATUS_INTERRUPTED) "（未完成）" else "（失败）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (!isUser && message.status == AiMessageEntity.STATUS_DONE) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onInsert(message.content) }) {
                    Text("插入正文", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = { onReplace(message.content) }, enabled = hasSelection) {
                    Text("替换选中", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = { onCopy(message.content) }) {
                    Text("复制", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = { onSaveAsNote(message.content) }) {
                    Text("存为新笔记", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun StreamingBubble(text: String, sending: Boolean) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.widthIn(max = 320.dp)
    ) {
        Text(
            text.ifEmpty { if (sending) "正在等待回答…" else "" },
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
```

- [ ] **Step 2: `AppContainer` 增加 AI 组件**

imports 增加：

```kotlin
import com.mynote.app.data.ai.AiChatRepository
import com.mynote.app.data.ai.AiDriverRegistry
import com.mynote.app.data.ai.AiWebDriver
import com.mynote.app.data.ai.AiWebSession
import com.mynote.app.data.ai.DeepSeekDriver
import com.mynote.app.data.ai.WebViewAiSession
import com.mynote.app.data.settings.AiSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
```

类内追加：

```kotlin
    /** 应用级协程作用域：ViewModel 清理后仍需完成的收尾写入（流式中断存档）用它。 */
    val applicationScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    val aiChatRepository: AiChatRepository by lazy {
        AiChatRepository(database.aiSessionDao(), database.aiMessageDao())
    }

    val aiSettingsStore: AiSettingsStore by lazy { AiSettingsStore(context) }

    val aiDriverRegistry: AiDriverRegistry by lazy {
        AiDriverRegistry(listOf(DeepSeekDriver()))
    }

    val aiWebSessionFactory: (AiWebDriver) -> AiWebSession = { WebViewAiSession(it) }
```

- [ ] **Step 3: `AndroidManifest.xml` 增加 INTERNET 权限**

在 `<manifest ...>` 后、`<application>` 前插入：

```xml
    <uses-permission android:name="android.permission.INTERNET" />
```

- [ ] **Step 4: `AppNavHost.kt` 整体替换**

```kotlin
package com.mynote.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mynote.app.di.AppContainer
import com.mynote.app.ui.ai.AiChatScreen
import com.mynote.app.ui.categories.CategoriesScreen
import com.mynote.app.ui.history.NoteHistoryScreen
import com.mynote.app.ui.notes.NoteEditScreen
import com.mynote.app.ui.notes.NotesScreen
import com.mynote.app.ui.notes.NotesViewModel
import com.mynote.app.ui.settings.SettingsScreen

object AiNavKeys {
    const val SEL_START = "ai_sel_start"
    const val SEL_END = "ai_sel_end"
    const val NOTE_TITLE = "ai_note_title"
    const val NOTE_CONTENT = "ai_note_content"
    const val RESULT_TYPE = "ai_result_type"
    const val RESULT_TEXT = "ai_result_text"
}

@Composable
fun AppNavHost(container: AppContainer) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "notes") {
        composable("notes") {
            val vm: NotesViewModel = viewModel(factory = NotesViewModel.factory(container.noteRepository))
            NotesScreen(
                viewModel = vm,
                backupManager = container.backupManager,
                onOpenNote = { id -> navController.navigate("edit/$id") },
                onNewNote = { navController.navigate("edit/new") },
                onManageCategories = { navController.navigate("categories") },
                onOpenSettings = { navController.navigate("settings") }
            )
        }
        composable("edit/{noteId}") { backStack ->
            val idArg = backStack.arguments?.getString("noteId")
            val id = idArg?.takeIf { it != "new" }?.toLongOrNull()
            val resultType by backStack.savedStateHandle
                .getStateFlow<String?>(AiNavKeys.RESULT_TYPE, null).collectAsState()
            val resultText by backStack.savedStateHandle
                .getStateFlow<String?>(AiNavKeys.RESULT_TEXT, null).collectAsState()
            NoteEditScreen(
                noteId = id,
                repository = container.noteRepository,
                imageStore = container.imageStore,
                backupManager = container.backupManager,
                imageRenderer = container.noteImageRenderer,
                exportManager = container.imageExportManager,
                aiResultType = resultType,
                aiResultText = resultText,
                onAiResultConsumed = {
                    backStack.savedStateHandle.remove<String>(AiNavKeys.RESULT_TYPE)
                    backStack.savedStateHandle.remove<String>(AiNavKeys.RESULT_TEXT)
                },
                onOpenAi = { selStart, selEnd, noteTitle, noteContent ->
                    backStack.savedStateHandle[AiNavKeys.SEL_START] = selStart
                    backStack.savedStateHandle[AiNavKeys.SEL_END] = selEnd
                    backStack.savedStateHandle[AiNavKeys.NOTE_TITLE] = noteTitle
                    backStack.savedStateHandle[AiNavKeys.NOTE_CONTENT] = noteContent
                    navController.navigate("ai_chat/$id")
                },
                onOpenHistory = { id?.let { navController.navigate("note_history/$it") } },
                onBack = { navController.popBackStack() }
            )
        }
        composable("ai_chat/{noteId}") { backStack ->
            val noteId = backStack.arguments?.getString("noteId")?.toLongOrNull()
                ?: return@composable
            val prev = navController.previousBackStackEntry?.savedStateHandle
            AiChatScreen(
                noteId = noteId,
                noteTitle = prev?.get<String>(AiNavKeys.NOTE_TITLE).orEmpty(),
                noteContent = prev?.get<String>(AiNavKeys.NOTE_CONTENT).orEmpty(),
                hasSelection = (prev?.get<Int>(AiNavKeys.SEL_END) ?: 0) >
                    (prev?.get<Int>(AiNavKeys.SEL_START) ?: 0),
                aiRepository = container.aiChatRepository,
                noteRepository = container.noteRepository,
                registry = container.aiDriverRegistry,
                settingsStore = container.aiSettingsStore,
                externalScope = container.applicationScope,
                webSessionFactory = container.aiWebSessionFactory,
                onApplyResult = { type, text ->
                    prev?.set(AiNavKeys.RESULT_TYPE, type)
                    prev?.set(AiNavKeys.RESULT_TEXT, text)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable("note_history/{noteId}") { backStack ->
            val noteId = backStack.arguments?.getString("noteId")?.toLongOrNull() ?: return@composable
            NoteHistoryScreen(
                noteId = noteId,
                repository = container.noteRepository,
                onRestored = { navController.popBackStack("notes", inclusive = false) },
                onBack = { navController.popBackStack() }
            )
        }
        composable("categories") {
            CategoriesScreen(
                repository = container.noteRepository,
                onBack = { navController.popBackStack() }
            )
        }
        composable("settings") {
            SettingsScreen(
                store = container.themeSettingsStore,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
```

- [ ] **Step 5: `NoteEditScreen` 加 AI 入口与结果落回**

1) import 增加：

```kotlin
import androidx.compose.material.icons.outlined.AutoAwesome
```

2) `NoteEditScreen` 参数在 `exportManager` 之后增加：

```kotlin
    aiResultType: String?,
    aiResultText: String?,
    onAiResultConsumed: () -> Unit,
    onOpenAi: (selStart: Int, selEnd: Int, noteTitle: String, noteContent: String) -> Unit,
```

3) 在 `LaunchedEffect(note)` 之后新增：

```kotlin
    LaunchedEffect(aiResultType, aiResultText) {
        val type = aiResultType ?: return@LaunchedEffect
        val text = aiResultText ?: return@LaunchedEffect
        content = AiResultApplier.apply(
            content,
            if (type == "replace") AiResultApplier.Type.REPLACE else AiResultApplier.Type.INSERT,
            text
        )
        onAiResultConsumed()
    }
```

4) 顶栏 actions 中历史图标之后增加：

```kotlin
                    if (noteId != null && noteId != 0L) {
                        IconButton(onClick = {
                            val sel = content.selection
                            onOpenAi(sel.start, sel.end, title, content.text)
                        }) {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = "AI 助手")
                        }
                    }
```

- [ ] **Step 6: 构建 + 全量测试**

Run: `.\gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL，全部测试通过。

- [ ] **Step 7: 提交**

```powershell
git add app/src/main/java/com/mynote/app/ui/ai/AiChatScreen.kt app/src/main/java/com/mynote/app/ui/navigation/AppNavHost.kt app/src/main/java/com/mynote/app/ui/notes/NoteEditScreen.kt app/src/main/java/com/mynote/app/di/AppContainer.kt app/src/main/AndroidManifest.xml
git commit -m "feat: AI 聊天页、编辑页入口与导航接线"
```

---

### Task 9: 文档更新、整体验证与修订记录

**Files:**
- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: `docs/superpowers/plans/2026-09-11-ai-web-assistant.md`（末尾追加「修订记录」）

- [ ] **Step 1: 全量测试并记录实际数量**

Run: `.\gradlew :app:testDebugUnitTest --console=plain`
Expected: BUILD SUCCESSFUL；在输出中确认测试数量（113 + 本次新增约 49 个）。

- [ ] **Step 2: 更新 `README.md`**

1) 「功能」列表在「历史」之后追加：

```markdown
- AI 助手：编辑页唤起，复用 DeepSeek 网页版（隐藏 WebView 自动发送与抓取回答）；会话按笔记留档，回答可插入正文 / 替换选中 / 复制 / 存为新笔记；首次使用有隐私确认，出错可切到可见网页手动操作
```

2) 首段能力描述（"全程零敏感存储权限"）句尾补充：

```markdown
（AI 助手需联网，仅新增普通 `INTERNET` 权限，无存储类敏感权限）
```

3) 「快速开始」注释里的测试数量改成 Step 1 得到的实际值：

```bat
.\gradlew :app:testDebugUnitTest      rem <实际数量> 个单元测试
```

4) 「项目结构」中：

```markdown
│   ├── db/                   # NoteEntity / CategoryEntity / NoteRevisionEntity / AiSessionEntity / AiMessageEntity / DAO / AppDatabase（Room v3 + 迁移）
```

在 `settings/` 行后追加：

```markdown
│   ├── ai/                   # AI 网页驱动（AiWebDriver / DeepSeekDriver / WebViewAiSession / 会话仓库）
```

在 `ui/history/` 行后追加：

```markdown
│   ├── ai/                   # AI 聊天页 / ViewModel（会话抽屉 / 流式回答 / 回答落地）
```

5) 「文档」列表追加：

```markdown
- AI 网页端助手设计：[`docs/superpowers/specs/2026-09-11-ai-web-assistant-design.md`](docs/superpowers/specs/2026-09-11-ai-web-assistant-design.md)
- AI 网页端助手计划（含修订记录）：[`docs/superpowers/plans/2026-09-11-ai-web-assistant.md`](docs/superpowers/plans/2026-09-11-ai-web-assistant.md)
```

- [ ] **Step 3: 更新 `AGENTS.md`**

1) 命令表「全部单测（当前 113 个）」改为实际数量。

2) 「项目与约束」技术栈行末尾补充：

```markdown
AI 助手为应用首个联网功能（`INTERNET` 普通权限），其余仍保持零敏感权限与零新增三方依赖。
```

3) 「数据与代码惯例」末尾追加：

```markdown
- AI 助手：DeepSeek 网页适配集中在 `data/ai/DeepSeekDriver.kt`（URL / DOM 选择器 / 注入 JS），新增服务 = 一个 `AiWebDriver` 实现 + `AiDriverRegistry` 注册一行；会话与消息存 `ai_sessions` / `ai_messages`（Room v3），网页上下文靠 `remoteChatId` 恢复；编辑页通过 `savedStateHandle` 传递选区与正文快照、回传 AI 结果；WebView 只在 `ui/ai/AiChatScreen` 创建，离开即 `destroy()`；测试用 `FakeAiWebSession` 替换网页层。
```

- [ ] **Step 4: 三连验证**

```powershell
.\gradlew :app:testDebugUnitTest --console=plain
.\gradlew :app:assembleDebug --console=plain
.\gradlew :app:assembleRelease --console=plain
Get-Item app/build/outputs/apk/release/app-release.apk | Select-Object Length, LastWriteTime
```

Expected: 三条 BUILD SUCCESSFUL；release APK 生成（记录体积）。

- [ ] **Step 5: 在计划末尾追加「修订记录」**

在文件最后追加（把括号内容替换为实际执行时发现的事项、实际测试数、实际 APK 体积；若某项未发生就写"无"）：

```markdown
---

## 修订记录（执行期）

1. 实际单测数量：<数量>（原 113 + 新增）。
2. release APK 体积：<体积>。
3. 与设计的偏差（如有）：
   - 未实现 `AiWebSession.checkLogin()` 独立方法：登录检测由「页面加载完成自动检测 + 发送前检测」覆盖；
   - 服务切换 UI 未做：注册表当前仅 DeepSeek，`AiDriverRegistry.all.size > 1` 时再加入口；
   - 会话删除无二次确认（当前点击即删，本地记录可重新生成）；
   - `remoteChatId` 失效时未弹独立提示（网页回到首页，用户可继续发送，视为新会话）；
   - <其它实际偏差>。
4. 真机人工验证（设计 §12 清单）待用户执行，结果回填于此。
```

- [ ] **Step 6: 提交**

```powershell
git add README.md AGENTS.md docs/superpowers/plans/2026-09-11-ai-web-assistant.md
git commit -m "docs: 更新 AI 助手文档与测试数（含计划修订记录）"
```

---

## 执行顺序与依赖

- Task 1 → Task 4 → Task 7 → Task 8 是主链（DB → 仓库 → ViewModel → UI）。
- Task 2 → Task 6 → Task 7（驱动 → 桥接 → ViewModel）。
- Task 3 → Task 7（提示词构造）。
- Task 5 可在 Task 1 之后任意时间做（与 AI 主链无编译依赖）。
- 每个 Task 结束必须本任务测试全绿再提交；Task 8/9 结束跑全量测试 + debug/release。
- 真机验证前不要把 release APK 交给用户：先确认 `INTERNET` 权限生效、WebView 能打开 `chat.deepseek.com` 登录页。

## 已知取舍（执行时不要"顺手优化"）

- 选择器写死在 `DeepSeekDriver`，不做远程配置 / 自动修复；网页改版属预期风险，降级路径是可见网页手操。
- 不做验证码 / 风控绕过；登录与验证码一律用户手动完成。
- 不做图片 AI 对话；发送前 `NoteContentParser.plainText` 剥离图片标记。
- AI 会话不进备份 zip（避免导入后 `noteId` 关联失效）。
- 流式回答只在「保存点」落库：错误 / 停止 / 页面销毁时存半截，进程被杀时流式半截丢失属预期。





