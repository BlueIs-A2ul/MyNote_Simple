# 编辑页新建分类 — 设计文档

- 日期：2026-09-10
- 状态：已实现（2026-09-10，26 个单测全绿 + assembleDebug 通过；真机 UI 手工验证待做）
- 关联：`docs/superpowers/specs/2026-08-13-mynote-design.md`（§2 分类、§5 数据模型）

## 1. 背景与问题

分类功能已全链路实现（分类管理页、编辑页选择、列表筛选），但用户反馈「找不到分类功能」：

- 编辑页的分类 chip 行仅遍历已有分类，**分类为空时不显示任何分类相关入口**（`NoteEditScreen.kt:214-220`），新用户完全看不到。
- 创建分类的唯一入口在主界面右上角无文字图标（分类管理页），可发现性差。

本次先解决编辑页：让用户可在编辑笔记时直接新建分类。

## 2. 目标与非目标

**目标**

- 编辑页可新建分类，创建成功后自动选中该分类。

**非目标（本次不做）**

- 编辑页清除分类（改回未分类）——后续迭代。
- 列表页「未分类」筛选入口——后续迭代。
- 分类颜色自选、分类管理入口可发现性改造——后续迭代。

## 3. 交互设计

- 编辑页顶部 chip 行末尾常驻「+ 新建分类」chip（位于「预览」与各分类 chip 之后）。分类为空时该 chip 依然显示，顺带解决新用户看不到分类入口的问题。
- 点击弹出 `AlertDialog`：标题「新建分类」，名称输入框，确定 / 取消。风格与分类管理页的新建对话框一致（`CategoriesScreen.kt:88-103`）。
- 名称空白时「确定」按钮置灰不可点。
- 确定后：创建分类 → 关闭对话框 → 新分类自动成为当前笔记的选中分类。
- 输入的名称已存在时：自动选中已存在的分类（与 `NoteRepository.addCategory` 同名幂等行为一致），不报错。

## 4. 技术设计

### 4.1 ViewModel（`NoteEditViewModel`）

新增方法：

```kotlin
fun addCategory(name: String, onCreated: (Long) -> Unit)
```

- 名称 `isBlank()` 时直接返回（防御性校验，UI 已置灰确定按钮）。
- 通过 `repository.addCategory(name.trim(), NoteColors.random().toArgb())` 创建，颜色沿用分类管理页的随机取色（`CategoriesViewModel.kt:25`）。
- 成功后回调返回的分类 id；分类列表经现有 `repository.observeCategories()`（`NoteEditScreen.kt:80`）自动刷新，无需手动更新。

### 4.2 UI（`NoteEditScreen.kt`）

- chip 行末尾新增 `FilterChip`，label 为「+ 新建分类」，点击置 `showAddCategoryDialog = true`。
- 新增 `AlertDialog`（名称输入 + 确定/取消）；确定回调中 `selectedCategoryId = id` 并关闭对话框。
- 不新增导航路由，不修改数据层、Repository、DAO。

### 4.3 数据流

```
用户点「+ 新建分类」→ 弹窗输入名称 → 确定
  → NoteEditViewModel.addCategory → NoteRepository.addCategory（同名幂等）
  → 回调 id → selectedCategoryId = id → 保存笔记时随 saveNote 写入 categoryId
```

## 5. 边界与错误处理

| 场景 | 行为 |
|---|---|
| 名称为空/全空白 | 确定按钮置灰；ViewModel 侧也做 blank 校验 |
| 名称已存在 | 自动选中已有分类，不创建重复记录 |
| 名称前后空格 | 创建前 `trim()` |
| 创建过程异常 | 沿用现有模式（协程内未捕获，交由上层/系统），本次不新增错误 UI |

## 6. 测试策略

- **新增** `NoteEditViewModelTest`（Robolectric + 内存 Room + `Dispatchers.setMain`）：
  - 新建分类成功并回调 id，数据库中可查到该分类。
  - 空名不创建、不回调。
  - 同名幂等：回调已存在分类 id，不产生重复记录。
  - 名称前后空格被 trim。
- 复用已有 `NoteRepositoryTest.addCategoryIsIdempotentForSameName`（`NoteRepositoryTest.kt:80`）。
- UI 层无 Compose 测试基础设施，采用编译 + 手工验证。
- 验证命令：`.\gradlew :app:testDebugUnitTest`、`.\gradlew :app:assembleDebug`。

## 7. 涉及文件

| 文件 | 变更 |
|---|---|
| `app/src/main/java/com/mynote/app/ui/notes/NoteEditScreen.kt` | 新增 chip、对话框、ViewModel 方法 |
| `app/src/test/java/com/mynote/app/ui/notes/NoteEditViewModelTest.kt` | 新增测试 |
