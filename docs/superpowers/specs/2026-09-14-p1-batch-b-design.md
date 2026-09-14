# P1 批次 B 设计：编辑页焦点/预览接线/插图反馈/缺图提示（1.4.3）

> 日期：2026-09-14｜范围：backlog 条目 25、26、27、28（全部 P1、非 AI 助手功能本身）｜版本：patch → 1.4.3

## 条目 25 · 新建自动聚焦 + 标题 IME 跳正文 + AI 结果后焦点

- **现状**：`NoteEditScreen.kt:253-266` 新建笔记 `note` 恒为 null，`LaunchedEffect` 提前返回 → 新笔记页无焦点无键盘，须手动点输入框；标题 `BasicTextField`（`:411-430`）无 `ImeAction.Next`，标题写完只能点正文；AI 结果回填（`:268-279`）未恢复正文焦点（插图路径有，`:290`）。
- **方案**：
  - 新增 `titleFocusRequester`；新建笔记且标题/正文均为空时首帧请求焦点（进程重建恢复出内容则不抢焦点）。
  - 标题输入框加 `KeyboardOptions(imeAction = ImeAction.Next)` + `KeyboardActions(onNext = { contentFocusRequester.requestFocus() })`。
  - AI 结果 `apply` 后补 `contentFocusRequester.requestFocus()`，与插图路径一致。
- **测试**：纯 UI 行为，按既有约定不加 Robolectric 组合测试。

## 条目 26 · 全屏图片预览接线（补齐 #9 欠账）

- **现状**：`NoteImagePreviewDialog.kt` 已实现但全仓零调用（死代码）；预览 `AsyncImage`（`NoteEditScreen.kt:452-459`）无 clickable。
- **方案**：预览图片 `clickable` 时把 `imageStore.physicalFile(name)` 存入本地 `previewFile` 状态，非空即渲染 `NoteImagePreviewDialog(file, onDismiss)`。
- **测试**：纯 UI，不加组合测试。

## 条目 27 · 插图失败提示 + 半成品清理 + 图片 GC 时机

- **现状**：`NoteEditScreen.kt:123-130` 插图无失败分支（`NoteEditViewModel.insertImage` 只有成功回调）；`ImageStore.importAndCompress` 失败不删半成品；`NoteRepository.saveNote` 只在历史裁剪时跑 `collectImageGarbage`，用户删掉图片标记后文件永久残留。
- **方案**：
  - `NoteEditViewModel.insertImage` 增加 `onFailed` 回调（或返回结果），失败时编辑页 snackbar「图片插入失败，请换一张」。
  - `ImageStore.importAndCompress` 的失败路径删除半成品目标文件。
  - `NoteRepository.saveNote`：更新前记录 `extractImageNames(existing.content)`，更新后与 `extractImageNames(content)` 比较，有图片名消失即 `collectImageGarbage()`（现有引用集含历史快照，不会误删）；与 `trimmed` 触发合并去重。
- **测试**：`NoteRepositoryTest` 补「删除图片标记后文件被回收」；`ImageStoreTest` 补「失败清理半成品」。

## 条目 28 · 导出/分享缺图显式提示

- **现状**：`NoteImageRenderer.kt:236/345` 对缺失/损坏图片静默跳过，导出 PNG 成品少图无提示；分享走 `plainText` 图片标记无痕剥掉，无说明。
- **方案**：
  - 渲染侧：统计不可读图片数（文件缺失或解码失败），导出对话框 Ready 态显示「有 N 张图片无法读取，导出结果不含它们」（按需 `missingImages` 明细）。
  - 分享侧（`NoteEditScreen`）：分享文案追加「（含 N 张图片，未包含在文本中）」，N = `NoteContentParser.extractImageNames(content).size`。
- **测试**：渲染/导出域测试补「缺图计数」用例。

## 收尾

全量单测通过 → 1.4.3 / 1_04_03 → README 同步 → backlog 条目 25-28 置已完成（v1.4.3）→ 本地提交（不 push）。