# P1 批次 D 设计：图片/启动/回收站性能 + Room v5 索引与投影（1.5.0）

> 日期：2026-09-14｜范围：backlog 条目 39、40、41、42、45（全部 P1、非 AI 相关）｜版本：feature → 1.5.0（含 Room schema 迁移）

## 条目 39 · 图片导入移出主线程

- **现状**：`NoteEditScreen` 的 `insertImage` → `ImageStore.importAndCompress`（采样解码 + WebP 压缩 + 缩略图）全程主线程，大图数百毫秒卡顿/ANR。
- **方案**：`importAndCompress` 与 `generateThumbnail` 主体包 `withContext(Dispatchers.IO)`（方法签名不变，调用点无感）。

## 条目 40 · 预览改缩略图 + Coil 缓存上限

- **现状**：预览 `AsyncImage(physicalFile(...))` 解码 1600px 原图（≈7-10MB/张）；`ImageStore` 生成的 300px 缩略图无人读取；全仓无 Coil 配置。
- **方案**：
  - 编辑页预览与列表场景优先 `thumbFile(name)`，缺失回退 `physicalFile(name)`；全屏预览（NoteImagePreviewDialog）仍用原图。
  - `MyNoteApp` 实现 `ImageLoaderFactory`：显式内存缓存上限 ~20MB（`memoryCachePolicy` 保留默认），显式磁盘缓存目录策略不动。
- **测试**：ImageStore 无新增逻辑（thumb 已存在）；Coil 配置为启动装配，不加单测（沿用既有约定）。

## 条目 41 · 启动清理改 SQL 过滤

- **现状**：`MainActivity` 启动 → `purgeExpiredDeletedNotes` 用 `getAll().filter{}` 全表读正文后再筛，旋转/重建重复执行。
- **方案**：`NoteDao` 新增 `getDeletedBefore(cutoff)`（`deletedAt IS NOT NULL AND deletedAt < :cutoff`）；`purgeExpiredDeletedNotes` 直接按该查询批量删（+ 一次 GC），并放入 `withTransaction`。
- **测试**：`NoteDaoTest` 补 `getDeletedBeforeReturnsOnlyExpiredDeleted`；`NoteRepositoryTest` 既有过期清理用例保持。

## 条目 42 · 清空回收站批量删除 + GC 一次

- **现状**：`TrashViewModel.purgeAll` 逐条 `purgeNote`，每条内一次全表 GC → O(N²)，无进度可删一半。
- **方案**：`NoteRepository.purgeNotes(list)`：单事务物理删除 + 只做一次 `collectImageGarbage`；`TrashViewModel.purgeAll` 改用它并暴露 `purging: StateFlow<Boolean>`；TrashScreen 清理中禁用「清空/恢复/删除」并显示「清理中…」。
- **测试**：`NoteRepositoryTest` 补批量彻底删除 + 一次 GC（孤儿文件被回收、历史引用保留）；`TrashViewModelTest` 补 purging 状态。

## 条目 45 · notes 索引 + 投影查询（Room v5）

- **现状**：`NoteEntity` 零索引，分类/回收站/未分类/搜索全表扫描；列表 `SELECT *` 携带全量正文，每次写表整表重发射。
- **方案**：
  - `NoteEntity` 增加 `indices = [Index("deletedAt"), Index("categoryId"), Index("pinned", "updatedAt")]`；`AppDatabase` 升 v5，`MIGRATION_4_5` 建三个索引；迁移测试补 `migrate4To5`。
  - **投影查询**：新增 `NoteListItem(id, title, categoryId, pinned, createdAt, updatedAt, summary)` POJO，`summary = substr(content, 1, 400)`；全部列表类查询（全部 ×3 排序、分类 ×3、未分类 ×3、搜索 ×3、回收站）改为投影返回，非列表查询（getById/getByIds/getAll/insert/update…）不动。
  - `NotesViewModel.notes` 类型改为 `List<NoteListItem>?`；`NoteRow` 入参改为 `NoteListItem`；搜索高亮与摘要纯文本基于 `summary`（命中在 400 字之后时无行内高亮线索——罕见，可接受，记入验收）。
  - 回收站查询（observeDeleted）一并投影，TrashScreen 摘要同样基于 summary。
- **测试**：DAO 各排序变体按投影断言（title 等字段不变）；迁移测试；既有 VM/组件测试随类型适配（title/id/pinned/updatedAt 字段不变，断言大多无需改）。

## 收尾

全量单测通过 → `versionName 1.5.0` / `versionCode 1_05_00`（feature 批递增 minor 遵循 AGENTS.md）→ README 同步（版本/测试数/产物名）→ backlog 条目 39-42、45 置已完成（v1.5.0）→ 本地提交（不 push）。