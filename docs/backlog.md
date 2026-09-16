# MyNote 待完成清单（Backlog）

> 来源：2026-09-13 全量体验复查（列表页、编辑页、分类、历史、备份、AI、设置、DAO/仓库层）。
> 状态流转：`待开发 → 设计中 → 开发中 → 已完成`；单条完成即更新状态与日期。
>
> **执行约定（与 AGENTS.md 一致）**
> - 每个条目动代码前，先写 `docs/superpowers/specs/YYYY-MM-DD-*-design.md` 与 `docs/superpowers/plans/`（计划末尾记「修订记录」），再实现。
> - 硬约束：零新增三方依赖、零敏感权限。条目 14、15 需要突破约束，必须先在本文档「待决策」栏拍板。
> - 每完成一批（feature 批）递增 minor、修复批递增 patch，同步 README 版本行与产物名；全量单测通过才算完成。
> - 顺序：按编号依次推进；标注「可并行」的条目可按协同建议并行开发（见文末）。

## 总览

| # | 标题 | 优先级 | 成本 | 状态 | 可并行 |
|---|---|---|---|---|---|
| 1 | 导入备份前确认与策略说明 | P0 | S | 已完成（2026-09-13，v1.2.0） | 与 3/6/7/11 并行 |
| 2 | 回收站（软删除） | P0 | L | 已完成（2026-09-13，v1.3.0） | 独立里程碑，建议 P1 完成后再启动 |
| 3 | 编辑正文撤销/重做 | P1 | M | 已完成（2026-09-13，v1.2.0） | 逻辑部分（新文件）可并行 |
| 4 | 插入图片后恢复输入焦点 | P1 | S | 已完成（2026-09-13，v1.2.0） | 依赖 3 的 UI 集成节奏 |
| 5 | 列表相对时间自动刷新 | P1 | S | 已完成（2026-09-13，v1.2.0） | 与 1 同文件，串行 |
| 6 | 列表行显示分类名 | P1 | S | 已完成（2026-09-13，v1.2.0） | 与 3/7/11 并行 |
| 7 | txt 导出文件名清洗非法字符 | P1 | S | 已完成（2026-09-13，v1.2.0） | 纯函数+测试，可与任何条目并行 |
| 8 | 系统分享（ACTION_SEND） | P1 | S | 已完成（2026-09-13，v1.2.0） | 依赖编辑页 UI 集成节奏 |
| 9 | 预览模式点开大图 | P1 | S | 已完成（2026-09-13，v1.2.0）⚠ 审计见 26 | 组件可独立文件先行 |
| 10 | 编辑页字数统计 | P1 | S | 已完成（2026-09-13，v1.2.0） | 依赖编辑页 UI 集成节奏 |
| 11 | 列表排序选项 | P1 | M | 已完成（2026-09-13，v1.2.0） | DAO/VM 层，与 3/6/7 并行 |
| 12 | 多选批量操作 | P2 | M | 已完成（2026-09-13，v1.4.0） | 独立里程碑 |
| 13 | 搜索关键词高亮 | P2 | S | 已完成（2026-09-13，v1.2.0，随 #6 同批） | 与 6 同文件部分，顺序执行 |
| 14 | 定时/自动备份提醒 | P2 | M | 待决策 | 需放宽「零敏感权限」（通知） |
| 15 | 笔记锁定（密码/生物识别） | P2 | L | 待决策 | 需新增依赖，暂不推荐 |
| 16 | 主页「未分类」筛选入口 | P1 | S | 已完成（2026-09-14，v1.4.1） | 与 12 互补 |
| 17 | 导出备份防止「假成功」（zip/txt） | P0 | S | 已完成（2026-09-15，v1.6.1） | 批次A |
| 18 | txt 导出当前内容并补反馈 | P0 | S | 已完成（2026-09-15，v1.6.1） | 批次A |
| 19 | 历史恢复不静默丢未保存草稿 | P0 | S/M | 已完成（2026-09-15，v1.6.1） | 批次A |
| 20 | 多选跨 tab 残留与空选死按钮 | P0 | S | 已完成（2026-09-15，v1.6.1） | 批次A |
| 21 | 排序文案「最早创建」反向修正 | P1 | S | 已完成（2026-09-14，v1.4.2） | 批次A |
| 22 | 撤销补合并/防抖策略 | P1 | S | 已完成（2026-09-14，v1.4.2） | 批次A |
| 23 | 主页冷启动加载态 | P1 | S | 已完成（2026-09-14，v1.4.2） | 批次A |
| 24 | 编辑页未保存草稿兜底/自动保存 | P0 | M | 已完成（2026-09-15，v1.6.1，ON_STOP 静默保存） | 批次B |
| 25 | 新建自动聚焦 + 标题 IME 跳正文 + AI 后焦点 | P1 | S | 已完成（2026-09-14，v1.4.3） | 批次B |
| 26 | 全屏图片预览接线（#9 死代码补欠账） | P1 | S | 已完成（2026-09-14，v1.4.3） | 批次B |
| 27 | 插图失败提示 + 缩略图利用 + 图片 GC 时机 | P1 | S | 已完成（2026-09-14，v1.4.3） | 批次B |
| 28 | 导出/分享缺图显式提示 | P1 | S/M | 已完成（2026-09-14，v1.4.3） | 批次B |
| 29 | 搜索分类归属/深命中线索/排序口径 | P1 | S/M | 已完成（2026-09-14，v1.4.4） | 批次C |
| 30 | 相对时间分钟/小时级 | P1 | S | 已完成（2026-09-14，v1.4.4） | 批次C |
| 31 | 置顶视觉增强 + 回收站行可预览 | P1 | S/M | 已完成（2026-09-14，v1.4.4） | 批次C |
| 32 | 搜索防抖 + NoteRow 重组/解析缓存 | P1 | S | 已完成（2026-09-14，v1.4.4） | 批次C |
| 33 | 分类颜色可选可改 | P1 | M | 已完成（2026-09-14，v1.4.4） | 批次C |
| 34 | 设置页通用分区（排序/保留期/密度） | P1 | M | 已完成（2026-09-14，v1.4.4；列表密度未做） | 批次C |
| 35 | 动态取色与色板联动反馈 | P1 | S | 已完成（2026-09-14，v1.4.4） | 批次C |
| 36 | AI 失败重试按钮 | P1 | S | 已完成（2026-09-15，v1.7.0，随条目 52） | 批次C |
| 37 | AI 可见网页刷新/后退工具条 | P1 | S/M | ❌ 已作废（v1.6.0 移除网页驱动） | 批次C |
| 38 | AI 未登录误判文案 + 慢登录看门狗竞态 | P1 | S/M | ❌ 已作废（v1.6.0 移除网页驱动） | 批次C |
| 39 | 图片导入移出主线程 | P1 | S | 已完成（2026-09-14，v1.5.0） | 批次D |
| 40 | 预览改缩略图 + Coil 缓存上限 | P1 | S | 已完成（2026-09-14，v1.5.0）⚠ 缩略图机制已于 v1.6.1 移除，仅 Coil 缓存上限生效 | 批次D |
| 41 | 启动清理改 SQL 过滤 | P1 | S | 已完成（2026-09-14，v1.5.0） | 批次D |
| 42 | 清空回收站批量删除 + GC 一次 | P1 | S/M | 已完成（2026-09-14，v1.5.0） | 批次D |
| 43 | 导入事务 + 条目名校验 + 流式写盘 | P0 | M | 已完成（2026-09-15，v1.6.1） | 批次D |
| 44 | 导入结果真实汇报（新增/更新/跳过） | P0 | S/M | 已完成（2026-09-15，v1.6.1） | 批次D |
| 45 | notes 索引 + 投影查询（Room v5） | P1 | M | 已完成（2026-09-14，v1.5.0） | 批次D |
| 46 | 备份导入导出流式化 | P2 | M | 部分完成（导入随条目 43 于 v1.6.1 流式化；导出流式化待实现） | 批次D |
| 47 | 历史时间线摘要/对比命名/图片渲染 | P2 | S | 已建议（2026-09-14，待实现） | 可选 |
| 48 | 删除/恢复反馈与就地撤销 | P2 | M | 已建议（2026-09-14，待实现） | 可选 |
| 49 | 分类空名/删除确认反馈 | P2 | S | 已建议（2026-09-14，待实现） | 可选 |
| 50 | 深链 mynote://note/{id} | P2 | M | 已建议（2026-09-14，待实现） | 可选 |
| 51–64 | AI 助手优化批（看门狗/重试/预算/用量/草稿等 14 项） | P1 | L | 已完成（2026-09-15，v1.7.0） | — |
| 65–66 | AI 兼容性加固（动态模型列表 / 422 参数降级） | P1 | S/M | 已完成（2026-09-15，v1.7.1） | — |
| 67 | 新用户欢迎笔记 | P1 | S | 已完成（2026-09-15，v1.7.2） | — |
| 68 | 字体切换 | P2 | L | 评估后暂缓（用户拍板不实现，见条目 68） | — |
| 69 | AI 服务商可切换（硅基流动 / 自定义兼容） | P1 | L | 已完成（2026-09-15，v1.10.0） | — |
| 70 | AI 模型选择体验优化（连接文案 / 可搜索模型选择） | P1 | S/M | 已完成（2026-09-15，v1.10.1） | — |
| 71 | 图片 GC 宽限期（避免删掉刚落盘的新图） | P0 | S | 已建议（2026-09-15，待实现） | 批次E |
| 72 | 缩短回收站保留期前确认并报告影响条数 | P0 | S | 已建议（2026-09-15，待实现） | 批次E |
| 73 | 编辑页 draftId 持久化（进程死亡不重复插入） | P0 | S/M | 已建议（2026-09-15，待实现） | 批次E |
| 74 | 导入写图原子化（临时文件 + rename） | P0 | M | 已建议（2026-09-15，待实现） | 批次E |
| 75 | 导入分类冲突改为复用同名分类 | P0 | S | 已建议（2026-09-15，待实现） | 批次E |
| 76 | 批量/回收站操作异常处理 + IN 列表切块 | P0 | S/M | 已建议（2026-09-15，待实现） | 批次E |
| 77 | API Key 输入框不进 saved instance state | P1 | S | 已建议（2026-09-15，待实现） | 批次F |
| 78 | 导出/导入脱离 composition 作用域 | P1 | M | 已建议（2026-09-15，待实现） | 批次F |
| 79 | 图片 GC 与备份的内存口径（投影 / 流式） | P1 | M | 已建议（2026-09-15，待实现） | 批次F |
| 80 | 导入与 GC 边界加固合集 | P1 | S/M | 已建议（2026-09-15，待实现） | 批次F |
| 81 | AI 服务商身份与聊天页保持一致 | P1 | M | 已建议（2026-09-15，待实现） | 批次G |
| 82 | 看门狗超时与重发必须真正停流 | P1 | S | 已建议（2026-09-15，待实现） | 批次G |
| 83 | 空回答按失败处理 + finish_reason + 重试入口 | P1 | S | 已建议（2026-09-15，待实现） | 批次G |
| 84 | 历史裁剪保持 user/assistant 配对 | P1 | S | 已建议（2026-09-15，待实现） | 批次G |
| 85 | 流式 Markdown 渲染节流（避免 O(n²)） | P1 | M | 已建议（2026-09-15，待实现） | 批次G |
| 86 | 自定义地址明文 http 策略与错误文案 | P1 | S | 已建议（2026-09-15，待实现） | 批次G |
| 87 | Markdown 链接 scheme 白名单 | P1 | S | 已建议（2026-09-15，待实现） | 批次G |
| 88 | AI 体验小坑合集 | P2 | S/M | 已建议（2026-09-15，待实现） | 批次G |
| 89 | 搜索 LIKE 转义 + 高亮词 trim + 深命中线索 | P1 | S | 已建议（2026-09-15，待实现） | 批次H |
| 90 | 保留期文案与回收站口径统一 | P1 | S | 已建议（2026-09-15，待实现） | 批次H |
| 91 | 多选随搜索/数据收敛（不删看不见的笔记） | P1 | S | 已建议（2026-09-15，待实现） | 批次H |
| 92 | 首帧加载态统一 + debounce 首帧不延迟 | P2 | S | 已建议（2026-09-15，待实现） | 批次H |
| 93 | 历史页与导航层级修正 | P2 | S/M | 已建议（2026-09-15，待实现） | 批次H |
| 94 | 编辑页加载态与保存失败反馈 | P1 | S/M | 已建议（2026-09-15，待实现） | 批次I |
| 95 | 「不保存」抑制标志 + 保存期间只读输入 | P1 | S | 已建议（2026-09-15，待实现） | 批次I |
| 96 | 旋转保留对话框与待写文件状态 | P2 | M | 已建议（2026-09-15，待实现） | 批次I |
| 97 | 图片编辑体验（标记可视化 / 删图入口 / 孤儿图） | P2 | M | 已建议（2026-09-15，待实现） | 批次I |
| 98 | 导出缺图统计与渲染同口径 + txt 图片说明 | P1 | S/M | 已建议（2026-09-15，待实现） | 批次I |
| 99 | 无障碍一批（输入框标签 / 日历圆点 / 多选语义 / 触点） | P2 | M | 已建议（2026-09-15，待实现） | 批次J |
| 100 | 撤销栈封顶 + 字数统计缓存 + 平台加固合集 | P2 | S/M | 已建议（2026-09-15，待实现） | 批次J |

---

## P0 · 数据安全

### 1. 导入备份前确认与策略说明
- **现状/问题**：`NotesScreen.kt:102-112` 点击「导入备份」→ 选文件 → 立即合并入库（`BackupManager.importZip` 按「更新者优先」：备份较新覆盖本地、本地较新跳过、本地缺失则新增）。用户对合并策略毫不知情，备份较新时本地后续修改会被静默覆盖。
- **方案**：选完文件后先弹 `PaperAlertDialog` 说明合并策略与笔记数量，点「确认导入」再执行；失败路径沿用现有 Snackbar。
- **涉及文件**：`ui/notes/NotesScreen.kt`（主要是 UI，`BackupManager.kt` 可不改）。
- **验收**：导入必先确认；取消不产生任何写入；文案说明覆盖/跳过/新增三条规则。
- **测试**：导入前弹窗为纯 UI，可用 Robolectric 组合测试兜底回调逻辑；核心合并逻辑已有测试。
- **预估**：0.5 天。

### 2. 回收站（软删除）
- **现状/问题**：`NoteEditScreen.kt:550-562` 删除确认后物理删除，`note_revisions` 随 `ON DELETE CASCADE` 一并清空，完全不可逆；误删只能靠手动备份。
- **方案**：`NoteEntity` 加 `deletedAt: Long?` 软删除字段；删除改为写入时间戳，列表/搜索/导出/备份均过滤已删；回收站页面可恢复/彻底删除；超期（如 30 天）自动物理清理（`collectGarbage` 时机顺带处理即可）。
- **注意**：需 Room 升 v4 + 手写迁移 + 迁移测试（参照 `AppDatabaseMigrationTest`）；备份 zip 格式需带 `deletedAt`（向后兼容旧 zip）。
- **涉及文件**：`data/db/NoteEntity.kt`、`NoteDao.kt`、`AppDatabase.kt`、`NoteRepository.kt`、`BackupManager.kt`、`NotesScreen.kt`、`NoteEditScreen.kt`、新增回收站界面。
- **验收**：删除进回收站；回收站可恢复（含历史快照不丢）与彻底删除；30 天到期自动清理；旧备份可导入。
- **预估**：2–3 天（含迁移测试）。建议作为独立里程碑，在 P1 全部完成后启动，避免与编辑页/列表页的小改动冲突。

---

## P1 · 快速体验优化

### 3. 编辑正文撤销/重做
- **现状/问题**：`BasicTextField` 无系统编辑工具栏，长文误删后无法撤销；历史记录是保存粒度，救不了未保存内容。
- **方案**：新建纯逻辑类（如 `ui/notes/NoteUndoController.kt`）：`TextFieldValue` 快照栈 + 合并策略（连续输入合并、防抖），编辑页接撤销/重做按钮（底部栏或顶栏），配合单元测试先行开发；UI 集成最后一步。
- **涉及文件**：新增 `NoteUndoController.kt` + 测试；`NoteEditScreen.kt`（仅集成）。
- **验收**：连续输入/删除/插图/贴 AI 结果均可撤销与重做；栈上限（如 100 条）防内存膨胀；进程重建后栈清空但正文仍在（rememberSaveable 已兜底）。
- **预估**：1–1.5 天。

### 4. 插入图片后恢复输入焦点
- **现状/问题**：`NoteEditScreen.kt:264-268` 选完图、标记插到光标处（`AiResultApplier` 已定位光标）后，Photo Picker 返回导致焦点丢失、键盘收起，需手动点回正文。
- **方案**：正文 `BasicTextField` 挂 `FocusRequester`，插入回调后 `requestFocus()`（必要时 `keyboard?.show()`）。
- **涉及文件**：`NoteEditScreen.kt`。
- **验收**：插入图片后光标回到正文原位置、键盘弹出。
- **预估**：0.5 天以内。

### 5. 列表相对时间自动刷新
- **现状/问题**：`NotesScreen.kt:234` `now` 只在重组时取值，界面停留期间「刚刚/5 分钟前」不变化。
- **方案**：`produceState`/`LaunchedEffect` 每分钟 tick 一次刷新 `now`。
- **涉及文件**：`NotesScreen.kt`。
- **验收**：列表停留 1 分钟后相对时间自动更新。
- **预估**：0.5 天以内。

### 6. 列表行显示分类名
- **现状/问题**：`NoteRow.kt:50-56` 仅有 3dp 色条，色板 8 档颜色，分类多时无法区分。
- **方案**：`NoteRow` 增加可选分类名参数（在日期下方或摘要行尾部小字显示），列表页传 `categories.firstOrNull{...}?.name`；搜索与分类视图下可省略（当前 tab 已表明分类）。
- **涉及文件**：`ui/components/NoteRow.kt`、`NotesScreen.kt`。
- **验收**：全部视图显示分类名；仅「全部」tab 显示（其他 tab 不重复显示）；无分类不显示。
- **预估**：0.5 天。

### 7. txt 导出文件名清洗非法字符
- **现状/问题**：`NoteEditScreen.kt:574` 直接 `note.title + ".txt"`，标题含 `/`、`:` 等时 SAF 可能失败或命名异常。
- **方案**：抽取纯函数（如 `util/FileNameSanitizer.kt`）替换非法字符、去首尾空白、兜底 "note"；先写测试再接入。
- **涉及文件**：新增 util + 测试；`NoteEditScreen.kt` 一行接入。
- **验收**：任意标题导出成功且文件名合法；空标题兜底。
- **预估**：0.5 天以内（可与其他条目完全并行）。

### 8. 系统分享（ACTION_SEND）
- **现状/问题**：无分享入口，「笔记 → 微信/其他应用」只能靠导出 txt/图片绕路。
- **方案**：编辑页溢出菜单加「分享」，`ACTION_SEND` 纯文本分享标题+正文（零依赖、零权限）；深色/主题无影响。
- **涉及文件**：`NoteEditScreen.kt`（Intent + 菜单项）。
- **验收**：分享面板唤起，标题+正文完整；空笔记禁用或仅分享标题。
- **预估**：0.5 天。

### 9. 预览模式点开大图
- **现状/问题**：`NoteEditScreen.kt:398-405` 预览中 `AsyncImage` 无交互，长图/截图看不清。
- **方案**：图片点击弹全屏查看 Dialog（Coil 已有，零依赖）；组件可先独立成 `ui/notes/NoteImagePreviewDialog.kt` 开发，再接入预览列表。
- **涉及文件**：新增 Dialog 组件；`NoteEditScreen.kt` 接入。
- **验收**：点击放大、返回/点击空白关闭、深色模式正常。
- **预估**：0.5 天。

### 10. 编辑页字数统计
- **现状/问题**：无字数/字符数显示。
- **方案**：底部栏加「字数」（纯文本字符数，图片标记 `![](img/x)` 不计入，用 `NoteContentParser.plainText` 口径与列表一致）。
- **涉及文件**：`NoteEditScreen.kt`。
- **验收**：随输入实时更新；口径与列表摘要一致。
- **预估**：0.5 天以内。

### 11. 列表排序选项
- **现状/问题**：`NoteDao.kt:13` 固定「置顶 + 更新时间」，无其他排序。
- **方案**：DAO 增加「按标题 / 按创建时间」`ORDER BY` 变体（仍保持置顶优先），排序偏好持久化到 `ThemeSettingsStore` 或新增轻量设置存储；列表页溢出菜单或设置页切换。
- **涉及文件**：`NoteDao.kt`、`NotesViewModel.kt`、设置存储、`NotesScreen.kt`（切换入口）。
- **验收**：三种排序即时生效、重启保留；搜索/分类视图沿用。
- **预估**：1 天。

### 16. 主页「未分类」筛选入口
- **现状/问题**：主页 tab 的「全部」用 `null` 表示，「不筛选」状态被占死；未分类笔记（新建未选分类、或所属分类被删除后 `clearCategory` 置空）只能混在「全部」里，整体归类需逐条进编辑页改分类。
- **方案**：新增 `CategoryFilter`（全部 / 未分类 / 某个分类）替代「null 双关」；DAO 增加 `observeUncategorized()`（`categoryId IS NULL`）；主页 tab 常驻「未分类」入口，空态文案分场景，分类被删后自动回落「全部」。配合 #12 多选批量移动即可整体归类。
- **涉及文件**：新增 `ui/notes/CategoryFilter.kt`；`NoteDao.kt`、`NoteRepository.kt`、`NotesViewModel.kt`、`NotesScreen.kt`。
- **验收**：未分类 tab 只显示未分类且未删除的笔记；该 tab 下新建默认未分类；排序菜单不可用（与分类 tab 一致）；删除当前分类后回落到「全部」。
- **预估**：0.5 天。

---

## P2 · 中大功能 / 待决策

### 12. 多选批量操作
- **方案**：列表长按进入多选模式（顶栏变「已选 n 项 + 全选」），批量删除（确认）、批量改分类、批量置顶/取消。
- **涉及文件**：`NotesScreen.kt`、`NotesViewModel.kt`、`NoteRepository.kt`（批量事务）。
- **验收**：多选状态管理正确（含旋转重建）、批量操作事务化、空选禁用。
- **预估**：1.5–2 天。

### 13. 搜索关键词高亮
- **方案**：`NoteRow` 摘要（标题）内对当前 query 命中片段加主题色/背景高亮（`AnnotatedString`），搜索态下传入 query。
- **涉及文件**：`ui/components/NoteRow.kt`、`NotesScreen.kt`。
- **预估**：0.5–1 天。

### 14. 定时/自动备份提醒 ⏸ 待决策
- **方案**：`AlarmManager` 周期任务 + 通知提醒导出备份（可附带「上次备份时间」显示在设置页）。
- **决策点**：Android 13+ 通知需 `POST_NOTIFICATIONS` 权限，违背「零敏感权限」硬约束；是否放宽需用户拍板。备选：仅在应用内提示（价值低）。
- **预估**：1 天（决策后）。

### 15. 笔记锁定（密码/生物识别） ⏸ 待决策
- **方案**：单篇笔记加锁，进入需验证。
- **决策点**：需新增 androidx.biometric 等依赖，违背「零新增三方依赖」；且加密方案复杂度高。**暂不推荐**，除非有明确需求。

---

## 体验审计批次（2026-09-14，条目 17–50）

> 来源：6 域并行只读体验审计（数据可靠性 / 主页列表 / 编辑页与历史 / 分类设置导航 / AI 助手 / 数据层性能），均已读码实测、落到 file:line。除已完成批次外状态统一「已建议，待实现」，未动代码。
> 硬约束沿用：零新增三方依赖、零敏感权限。批次 A–C 为 patch（1.4.x），批次 D 含 Room v5 迁移（1.5.0），「可选」按需排期。

### 批次 A · 1.4.2 修复批（数据安全 + 小改，全部零迁移）✅ 已完成（2026-09-14，v1.4.2）

### 17. 导出备份防止「假成功」（zip/txt 静默失败）
- 现状：`BackupManager.kt:80,97` `openOutputStream` 为 null 时 zip 一行未写仍返回「已导出 N 条」；txt `:145-147` 同理且 `NoteEditScreen.kt:301` 丢弃返回值 → 换机前导出 0 字节用户却放心清数据。
- 建议：流为 null 抛错走既有「导出失败」分支；txt 接布尔并 snackbar；导出中禁用菜单入口。价值：高 × S

### 18. txt 导出当前内容并补反馈
- 现状：`NoteEditScreen.kt:296-303` 导出的是 Room 已保存实体（旧内容），未保存修改不导出且无任何提示；txt 不含标题，文件名也可能带旧标题。
- 建议：用编辑态现值 `note.copy(title, content)`，文件名走 `FileNameSanitizer`，按返回值 snackbar「已导出/导出失败」。价值：高 × S

### 19. 历史恢复不静默丢未保存草稿
- 现状：`AppNavHost.kt:121` 恢复成功直接 pop 回「notes」绕过编辑页 dirty 拦截（`NoteEditScreen.kt:309-313`），草稿无声消失；成功也无「已恢复」反馈（`NoteHistoryViewModel.kt:132-139` 仅失败置消息）。
- 建议：`onOpenHistory` 经 savedStateHandle 透传 dirty，确认框追加「未保存修改将一并丢弃」；恢复成功经 nav 结果通道回列表页弹 snackbar。价值：高 × S/M

### 20. 多选跨 tab 残留与空选死按钮
- 现状：多选态下分类 tab 仍可点（`NotesScreen.kt:324-337` 无 `selectionMode` 守卫），`onFilterSelect` 不清选择集（`NotesViewModel.kt:57-59`）；取消最后一个选中不自动退出，批量菜单无 enabled 守卫，空选「删除」弹「0 条」确认后无任何反应（`NotesViewModel.kt:99-100`）。
- 建议：切 tab/搜索时 `exitSelection()`；`selectAll()` 只作用于当前可见列表；空选禁用菜单项并自动退出多选。价值：高 × S

### 21. 排序文案「最早创建」反向修正 ✅
- 现状：`NotesScreen.kt:237` 文案「排序：最早创建」实际设置 `CREATED_DESC`（`NoteDao.kt:26-27` 最新在前，单测亦持此语义）。
- 建议：文案改「排序：最新创建」（SQL 与测试不动）。价值：高 × S

### 22. 撤销补合并/防抖策略 ✅
- 现状：`NoteEditScreen.kt:466-470` 每次击键 `record()`，`NoteUndoController.kt:19-25` 无条件压栈 → 30 字要按 30 次撤销；backlog #3 承诺的「连续输入合并、防抖」未实现。
- 建议：纯逻辑合并（<600ms 相邻单字符增删替换栈顶；插图/贴 AI 结果 `force` 新开一格），现有单测语义保留、补合并用例。价值：高 × S

### 23. 主页冷启动加载态 ✅
- 现状：`NotesViewModel.kt:47` 首值 emptyList，`NotesScreen.kt:340-353` 直接渲染「还没有笔记 + 写第一条」（可误点进新建页）；主页是唯一无加载态的页面。
- 建议：`notes` 首值改 null / 暴露 loaded，加载中留白或进度指示，首个真实结果后再判空。价值：中 × S

### 批次 B · 1.4.3 编辑/预览批 ✅ 已完成（2026-09-14，v1.4.3）

### 24. 编辑页未保存草稿兜底/自动保存
- 现状：唯一落盘入口 `saveAndExit`（`NoteEditScreen.kt:315-330`）且保存即退出；正文只存 `rememberSaveable`（`:231-234`），划掉任务卡/强杀/崩溃即全丢；空白新笔记「保存」始终可点会插一条空笔记。
- 建议：`LifecycleEventEffect(ON_STOP)` 静默保存（dirty 且非保存中，只更新笔记行不写 revision 以防刷爆 50 条上限），或草稿兜底；「保存」与「保存并退出」分离；空白笔记禁用保存；新建笔记仅正文非空才把 pinned/分类计入脏。价值：高 × M（需与「手动保存」产品语义拍板）

### 25. 新建自动聚焦 + 标题 IME 跳正文 + AI 后焦点
- 现状：新建笔记 `note` 恒为 null 导致 `LaunchedEffect` 提前返回（`NoteEditScreen.kt:253-266`）无焦点无键盘；标题无 `ImeAction.Next`；AI 结果回填后未 `requestFocus()`（对比插图路径 `:290`）。
- 建议：新建首帧 `titleFocusRequester.requestFocus()`；标题加 Next → 正文；AI apply 后补正文焦点。价值：中 × S

### 26. 全屏图片预览接线（#9 死代码补欠账） ✅
- 现状：`NoteImagePreviewDialog.kt` 已实现但全仓零调用点（grep + `git log -S` 双向证实），预览 `AsyncImage`（`NoteEditScreen.kt:452-459`）无 clickable；backlog #9 状态需修正。
- 建议：预览图挂 clickable → 弹 Dialog（约 3 行）；同时补 error/placeholder 占位。价值：中 × S

### 27. 插图失败提示 + 缩略图利用 + 图片 GC 时机 ✅
- 现状：`insertImage`（`NoteEditScreen.kt:123-130`）失败静默且 `ImageStore` 留半成品文件；300px 缩略图（`ImageStore.kt:58-76`）无人读取；`collectImageGarbage` 只在历史裁剪时触发（`NoteRepository.kt:82,88`），删标记后图片文件几乎永久残留。
- 建议：失败分支 snackbar + `importAndCompress` catch 里删半成品；保存时对比前后图片名集合，有消失即 GC。价值：中 × S

### 28. 导出/分享缺图显式提示 ✅
- 现状：渲染器对缺图/损坏图静默跳过（`NoteImageRenderer.kt:236,345`），导出 PNG/分享成品少图用户不知情。
- 建议：渲染前预检回传 `missingImages`，导出对话框 Ready 态提示「有 N 张图片无法读取」；分享文案注明含 N 张图片未包含。价值：中 × S/M

### 批次 C · 1.4.4 列表/AI 批 ✅ 已完成（2026-09-14，v1.4.4；条目 36-38 属 AI 域，未启动）

### 29. 搜索分类归属 / 深命中线索 / 排序口径拍板
- 现状：搜索为全库但隐藏全部 tab 与分类名（`NotesScreen.kt:367` 要求 `query.isBlank()`）；命中在正文深处时摘要无高亮线索；排序在分类/未分类/搜索视图静默失效（`NotesScreen.kt:230`）且菜单无解释——与 #11 验收「搜索/分类视图沿用」、#16 验收「非全部 tab 禁用」互相矛盾，需拍板。
- 建议：搜索态也显示分类名；命中不在前两行时摘要取命中位置 ±30 字窗口（纯函数可单测）；排序禁用时菜单加一行说明或让三视图共用 sortMode。价值：中高 × S/M

### 30. 相对时间分钟/小时级
- 现状：`TimeFormat.kt:16-28` 只有「今天/昨天/日期」四档，`NotesScreen.kt:116-121` 的 60s tick 刷不出任何变化。
- 建议：补 `<60s 刚刚 / <60min N 分钟前 / <24h N 小时前` 档位；补 `TimeFormat` 边界单测。价值：中 × S

### 31. 置顶视觉增强 + 回收站行可预览
- 现状：置顶标记 12dp 同色图钉无分区（`NoteRow.kt:82-90`）；回收站行纯 Row 不可点，彻底删除前无法预览内容（`TrashScreen.kt:168,118-137`）。
- 建议：图钉用 primary 色 + 置顶/普通分区标题；回收站行 clickable 只读预览（复用 `NoteContentParser` + `physicalFile`），确认框带标题/摘要。价值：中 × S/M

### 32. 搜索防抖 + NoteRow 重组/解析缓存
- 现状：`NotesViewModel.kt:53-55` 每键 flatMapLatest 重启 LIKE 全表扫描（`NoteDao.kt:39`）；`NotesScreen.kt:100,116-121` 每分钟 whole-list 重组，`NoteRow.kt:104` 每次重组全量 plainText + 高亮。
- 建议：query 流 `debounce(200)+distinctUntilChanged`；`remember(note.content, query)` 缓存解析；`now` 改 `compositionLocalOf` 只让日期读取。价值：中 × S

### 33. 分类颜色可选可改
- 现状：新建分类 `NoteColors.random()`（`CategoriesViewModel.kt:25`），对话框与行内编辑均无颜色选项；6 色板中苔绿 `0x7FA588` 与青灰 `0x7FA8A5` 相近，>6 分类必然撞色（`Color.kt:37-44`）。
- 建议：新建对话框选色（默认最少使用色），行内圆点点击换色。价值：高 × M

### 34. 设置页通用分区
- 现状：设置页只有外观（`SettingsScreen.kt:64-152`）；默认排序只在首页菜单且非「全部」tab 下置灰无解释；回收站保留期硬编码 30 天（`NoteRepository.kt:184-186`）；无列表密度/字号。
- 建议：新增「通用」分区：默认排序、保留期 7/30/90 天、列表密度紧凑/舒适（NoteRow padding 参数化）。价值：中 × M

### 35. 动态取色与色板联动反馈
- 现状：动态取色开启时 8 色圈仍显示选中环但实际色来自壁纸（`SettingsScreen.kt:114-141`），点色圈静默关动态取色无反馈。
- 建议：动态开启时色板降透明/去环 + 说明文字，点色圈弹 snackbar 反馈。价值：中 × S

### 36. AI 失败重试按钮 ✅ 已完成（2026-09-15，v1.7.0，随条目 52 落地）
- 现状（2026-09-14 记录）：banner「可重试」仅为文案，全代码无重试按钮（`AiChatViewModel.kt:232`）；发送即清空输入（`AiChatScreen.kt:224-226`），失败后要整段重打。
- 落地方式：见条目 52——失败/中断气泡下加「重试」，重发该会话最后一条 ROLE_USER 消息且不重复落库（`AiChatScreen.kt` 重试按钮 + `AiChatViewModel.launchSendNow`）。

### 37. AI 可见网页刷新/后退工具条 ❌ 已作废
- 2026-09-15 起 AI 助手改走 DeepSeek 官方 API，无 WebView 与可见网页，本条随网页版移除。

### 38. AI 未登录误判文案 + 慢登录看门狗竞态 ❌ 已作废
- 2026-09-15 起无网页登录态；API Key 无效（401）已映射为「去设置」引导。

### 批次 D · 1.5.0 性能批（含 Room v5 迁移）✅ 已完成（2026-09-14，v1.5.0；条目 43/44 属 P0，未在本批）

### 39. 图片导入移出主线程 ✅
- 现状：`NoteEditScreen.kt:123-130` → `ImageStore.kt:33-56` 同步解码+WebP 压缩+缩略图全程主线程，大图数百毫秒卡顿/ANR。
- 建议：`importAndCompress` 内 `withContext(Dispatchers.IO)`。价值：高 × S

### 40. 预览改缩略图 + Coil 缓存上限 ✅（缩略图部分已于 v1.6.1 撤销）
- 现状（2026-09-14 记录）：预览 `AsyncImage(physicalFile(...))` 解码 1600px 原图（≈7-10MB/张），300px 缩略图闲置；全仓无 ImageLoader 配置，Coil 默认 25% 堆缓存。
- 落地与回退：v1.5.0 曾按建议改用 300px 缩略图（缺失回退原图）；**v1.6.1 已把缩略图机制整体移除**——`ImageStore` 不再生成 `thumb_*`，只在 GC 时清理历史遗留文件，预览改为直接读原图。仍在生效的只有 `MyNoteApp` 的 Coil `ImageLoaderFactory` 显式内存缓存上限（堆约 10%）。
- 结论：本条的缩略图部分已作废，无需再实现。

### 41. 启动清理改 SQL 过滤 ✅
- 现状：`MainActivity.kt:30-32` → `NoteRepository.kt:140` `getAll().filter` 全表读正文，旋转/主题重建重复执行，回收站页再重复一遍。
- 建议：新增 `deletedAt IS NOT NULL AND deletedAt < :cutoff` 查询（配合 #45 索引），清理进事务。价值：高 × S

### 42. 清空回收站批量删除 + GC 一次 ✅
- 现状：`TrashViewModel.kt:43-49` 对每条 `purgeNote`，每条内一次全表 GC（`NoteRepository.kt:130-133`）→ O(N²)；无进度、可删一半。
- 建议：新增 `purgeNotes(list)` 单事务批量删 + 一次 GC；VM 暴露 purging 态，按钮禁用显示「清理中…」。价值：中高 × S/M

### 43. 导入事务 + 条目名校验 + 流式写盘
- 现状：`BackupManager.kt:101-140` 无 withTransaction（半导入只报失败）；zip 条目名 `img/../../x` 可越出目录（`ImageStore.kt:78-81`）；全部图片 `readBytes()` 攒内存 OOM。
- 建议：`withTransaction` 包裹；条目名白名单 `[A-Za-z0-9._-]+`；逐 entry `copyTo` 流式；野 categoryId 置 null；区分「非有效备份」与「读取失败」文案。价值：中高 × M

### 44. 导入结果真实汇报
- 现状：`BackupManager.kt:139` 无论新增/更新/跳过一律报「已导入 N 条」；分类无条件覆盖（`:129`）；带 deletedAt 的备份落回收站后「导入即消失」且无说明。
- 建议：返回结果对象（新增/更新/跳过/落回收站），按实数措辞；确认框补「分类名称与颜色以备份为准」。价值：高 × S/M

### 45. notes 索引 + 投影查询（结构性，Room v5） ✅
- 现状：`NoteEntity.kt` 零索引，列表/分类/回收站/未分类/搜索全表扫描 + `SELECT *` 带全量正文，每次写表整表重发。
- 建议：补 `Index(deletedAt)`、`Index(categoryId)`、`Index(pinned, updatedAt)`（Room v5 + MIGRATION_4_5 + 迁移测试）；列表改投影查询（`substr(content,1,300)` POJO）。价值：高 × M

### 46. 备份导入导出流式化
- 现状：导入把全部图片 readBytes 攒内存；导出 JSON 3~4 份拷贝（`BackupManager.kt:75-84,104-118`）。
- 建议：导入逐 entry 流式写盘；导出投影查询 + JSON 逐条写 zip entry。价值：中 × M

### 可选 · P2
- **47 历史时间线**：行摘要（plainText take 40）、页签改「与上一版对比」、全文渲染图片占位（`NoteHistoryScreen.kt:143-162,225,235`）。中 × S
- **48 删除/恢复反馈**：编辑页删除后列表弹「已移入回收站可恢复」snackbar + 就地「撤销」（复用 `restoreNote`）；回收站恢复带标题提示 + 「查看」入口。中 × M
- **49 分类反馈**：删除框带名称与笔记数；空名添加/重命名禁用确定（`CategoriesScreen.kt:197-209`、`CategoriesViewModel.kt:22-28`）。低 × S
- **50 深链**：`mynote://note/{id}` VIEW filter（`AndroidManifest.xml:20-24`、`AppNavHost.kt:34`）。低 × M（按需）

### AI 助手优化批（51–64，2026-09-15 立项）✅ 已完成（v1.7.0）

> 来源：API 替换网页版后的自审（见 `docs/superpowers/specs/2026-09-15-ai-assistant-optimization-design.md`）。51–56 为高价值，57–64 为体验/健壮性。

- **51 看门狗按流活跃度重置**：现在 120s 是从发送起的总时长，长回答会被误判失败；改为距上一个流事件超时，且思考模式 `reasoning_content` 帧要作为心跳（否则长思维链仍误判）。高 × S
- **52 失败重试 / 重新生成**：`failed` / `interrupted` 气泡缺重试按钮；新增「重试」重发该会话最后一条 user 消息（不重复落库）。高 × S
- **53 流式自动滚动跟随**：`LaunchedEffect` 只监听消息数量，流式变长不跟随；加 `streamingText/reasoningText` 监听并在用户上滑时暂停跟随。高 × S
- **54 上下文预算裁剪**：历史全量回传会无限膨胀；按字符预算裁掉最旧的中间消息（保留首条含正文 + 最新），笔记正文超长时截断。高 × S/M
- **55 会话删除确认**：当前删除无确认；加对话框避免误删整段对话。高 × S
- **56 抑制空失败消息**：无内容的 `failed` 不再落库（只留横幅），失败记录不再成串出现。高 × S
- **57 token 用量展示**：解析末块 `usage` 并显示「上次用量」，自付费场景成本可感。中 × S
- **58 余额查询 + 模型探测**：设置页「查询余额」（`GET /user/balance`）；「测试连接」顺带列出官方模型并与内置常量比对提示。中 × S/M
- **59 思考过程折叠展示**：深度思考开启时折叠显示 `reasoning_content`（最多 3 行，可展开）。中 × S/M
- **60 草稿按笔记/会话持久化**：离开 AI 页草稿不丢（SharedPreferences，键 = noteId+sessionId）。中 × S/M
- **61 代码块极简渲染**：回答中的 ``` 围栏代码块等宽显示（不做完整 Markdown），代码类回答可读。中 × M
- **62 每会话独立 HTTP 客户端**：取消隔离，`Streamer.cancel()` 不再误伤其他会话；容器按会话创建客户端。中 × S
- **63 `sendRequested` 异常复位**：建会话/落库异常时发送永久卡死；`try/catch` 复位并提示。中 × S
- **64 网络加固**：`Accept-Encoding: identity` 防潜在缓冲；429/503 预连接单次退避重试。中 × S

### AI 兼容性加固批（65–66，2026-09-15 立项）✅ 已完成（v1.7.1）

> 动机：官方文档/参数演进时减少跟版成本（模型改名、参数集调整无需等发版）。设计见 `docs/superpowers/specs/2026-09-15-api-compat-hardening-design.md`。

- **65 动态模型列表**：「测试连接」成功后把官方 `/models` 返回值持久化并在设置页作为模型选项（内置常量退化为默认值/兜底），官方改名的当天用户即可切换，无需发版。高 × S/M
- **66 422 参数降级自愈**：请求含 `thinking` 字段收到 422 时，自动去掉该字段重试一次；官方调整参数集时请求不再直接失败。中 × S

### 新用户欢迎笔记（67，2026-09-15 立项）✅ 已完成（v1.7.2）

> 用户需求：新用户首次进入主页时自动创建一篇介绍主要功能的笔记。设计见 `docs/superpowers/specs/2026-09-15-new-user-welcome-note-design.md`。

- **67 新用户欢迎笔记**：首次启动且主页没有任何可见笔记时，自动落一篇「欢迎使用 MyNote」介绍核心功能；一次性标记（删除后不再重建），老用户升级不受影响。中 × S

### 字体切换（68，2026-09-15 评估）⏸ 暂缓（用户决策：保持极致轻量）

> 结论：**暂不实现**。现状 `ui/theme/Type.kt` 写死系统字体（正文 SansSerif / 标题 Serif），无 `res/font/`、无设置项。

- 方案 A 系统字体切换（Sans/Serif/Monospace）：成本最低，但中文下视觉无差别，价值低。
- 方案 B 内置子集字体（如霞鹜文楷 OFL 子集，3500 常用字）：体验最好，APK +2~3MB（1.8MB → 4~5MB），与大标题/导出渲染需同步改造。
- 方案 C SAF 用户自选 `.ttf`：APK 零增长，但依赖用户自行下载字体文件，普通用户不会用。
- 重开条件：愿意放宽体积约束（方案 B），或仅做「用户自选字体」高级选项（方案 C）。若走方案 B 需在设置页/关于页补 OFL 许可证声明（子集化属修改，注意 RFN）。

### AI 服务商可切换（69，2026-09-15 立项）✅ 已完成（v1.10.0）

> 用户需求：支持接入硅基流动等其他 OpenAI 兼容服务商，并要求设计上可扩展（后续加服务商成本低）。设计见 `docs/superpowers/specs/2026-09-15-ai-provider-support-design.md`。

- **69 AI 服务商可切换**：内置 DeepSeek / 硅基流动 / 自定义（任意 OpenAI 兼容地址）三档；服务商配置集中在 `AiProvider` 枚举（地址、思考参数样式、余额样式、模型过滤、默认模型），新增服务商 = 加一条枚举；Key / 模型 / 思考开关按服务商命名空间隔离（DeepSeek 沿用历史 key 名，老用户无感升级）；设置页支持切换服务商、自定义地址与模型、余额按钮按能力显示；聊天页隐私确认按服务商独立。高 × M/L

### AI 模型选择体验优化（70，2026-09-15）✅ 已完成（v1.10.1）

> 背景：硅基流动官方对话模型达数十个，v1.10.0 的「连接成功列出全部模型」「横向 tab 选模型」「聊天页不显示模型」三处体验没跟上。设计见 `docs/superpowers/specs/2026-09-15-ai-model-ux-design.md`。

- **70 模型选择体验优化**：连接成功文案在模型数 > 5 时改为「共 N 个可用模型」（不再拼超长 snackbar）；设置页模型数 > 8 时改用可搜索弹窗（当前项对勾、点选即存）；聊天页顶栏副标题显示「服务商 · 模型」。低 × S

---

## 五域审计批次（2026-09-15，条目 71–100）

> 来源：五域并行只读审计（数据持久化 / 编辑页状态 / AI 助手 / 列表搜索导航 / 跨域生命周期）+ 主线逐条复核。已读码坐实、均落到 file:line；行号基于 `f616283`（v1.10.1 + 首批缺陷修复后）。
> 同批已修复的 5 项**不在本清单内**（预览模式崩溃、AI 流式分片赋值、AI 第二轮丢正文、保存键卡死、撤销合并丢整段起点），见修订记录。
> 优先级口径：P0 = 可能丢数据/删错文件；P1 = 用户可感知的功能或隐私问题；P2 = 体验打磨与加固。

### 批次 E · 数据安全与一致性（P0）

### 71. 图片 GC 宽限期（避免删掉「已落盘但引用未落库」的新图）★
- 现状：`ImageStore.collectGarbage` 是「不在引用集即删」的无保护全扫（`ImageStore.kt:67-75`），引用集只来自**已提交的 DB 状态**（`NoteRepository.kt:241-248`）；而图片文件先于任何 DB 行落盘（插图 `NoteEditScreen.kt:141-150`、导入 `BackupManager.kt:146-151`）。GC 触发点很多：`saveNote`（`:116`）、`purgeNote/purgeNotes`（`:190/:197`）、`purgeExpiredDeletedNotes`（`:209`，`MainActivity.kt:30-36` 每次 Activity 创建都跑，`TrashViewModel.kt:35-40` 打开回收站也跑）。
- 影响：插图后旋转/切深浅色/重建 Activity，恰逢启动清理 → 文件被删 → 随后保存写入 `![](img/x)` → **永久裂图**（历史快照里也是坏标记）。
- 建议：`collectGarbage` 跳过 `lastModified` 在宽限期内的新文件（如 10 分钟）；导入侧更彻底的做法是先落 `notes_images_pending/`，事务提交后 `renameTo`。价值：高 × S

### 72. 缩短回收站保留期前必须确认并报告影响条数 ★
- 现状：设置页点档位即生效、无确认（`SettingsScreen.kt:262-267` → `SettingsViewModel.kt:71` → `TrashRetentionStore.kt:21-25`）；真正清理在下次冷启动（`MainActivity.kt:30-36`）或打开回收站时（`TrashViewModel.kt:35-40`）按新 TTL **物理删除**（`NoteRepository.kt:201-211`，含历史与图片 GC）。
- 影响：30 天改 7 天后，回收站里 7 天以上的笔记被静默永久删除，无撤销入口；用户预期只是「以后删除的只留 7 天」。
- 建议：点选时弹确认框，文案含「回收站中 N 条超期笔记会立即永久删除」（N 由 `observeDeleted()` 按新 TTL 计数）；或改为变更后立即清理并 snackbar 报告条数。价值：高 × S

### 73. 编辑页 draftId 持久化（进程死亡后不再重复插入）
- 现状：`_draftId` 只在 VM 里（`NoteEditScreen.kt:119-120`），`save()` / `saveDraft()` 靠它避免二次插入（`:173`、`:207-215`），而屏幕侧 `title/content/initialized` 都是 `rememberSaveable`，唯独目标 id 没有。
- 影响：新建笔记退后台（ON_STOP 已静默插入）→ 进程被系统回收 → 回来 `noteId==null && draftId==null` → 点保存**再插一条**，列表出现两条近似重复笔记，旧草稿再也不会被更新。
- 建议：把目标 id 提到 `rememberSaveable(noteId)` 或导航 entry 的 `SavedStateHandle`，显式传给 `save/saveDraft`；VM 不再自己藏目标。价值：中高 × S/M

### 74. 导入写图原子化（临时文件 + rename）
- 现状：`importZip` 边解压边 `imageStore.writeFile(name, zip)`（`BackupManager.kt:138-157`），DB 写入直到 `:171` 才开事务；`ImageStore.writeFile` 无 try/catch、无半成品清理，且 `FileOutputStream` 会先截断同名文件（`ImageStore.kt:62-64`）。
- 影响：① 导入中旋转屏幕 → 协程被取消（`NotesScreen.kt:97,157-178` 用的是 `rememberCoroutineScope`）→ 事务回滚但**整包图片已落盘**，一条笔记都没导入且无提示；② 重导同一备份时磁盘满/包损坏 → 同名图片被截断，**本地已有笔记**开始显示坏图。
- 建议：`writeFile` 改「写临时文件 → 成功后 `renameTo`」，失败删临时文件；导入把图片落到暂存目录、事务提交后再提升。价值：中 × M

### 75. 导入分类冲突改为复用同名分类
- 现状：`BackupManager.kt:174-191` —— `getById(cat.id) != null` 时无条件 `update`，不检查该名字是否已被**另一个 id** 的本地分类占用，而 `categories.name` 是唯一索引（`CategoryEntity.kt:7`）；只有「id 不存在」分支才走 `getByName` 复用。
- 影响：A 机 `{1:工作, 2:生活}` 导入 B 机 `{1:生活}` 的备份 → 改名撞唯一索引 → `SQLiteConstraintException` → **整个导入事务回滚**（一条笔记都没进来），用户只看到「导入失败」；同 id 还会静默吞掉本地一个不相干分类的名字。注意：`BackupManagerTest.kt:297-317` 目前把这个冲突当成「预期整体回滚」写进了测试，修完需同步改。
- 建议：先算 `byId`/`byName`，冲突时优先复用 `byName.id` 并只重写颜色，仅两者皆无时 insert。价值：中 × S

### 76. 批量操作异常处理 + IN 列表切块
- 现状：`NotesViewModel.kt:111-120`（`runBatch`）与 `TrashViewModel.kt:42-68` 无 try/catch，异常沿 `viewModelScope`（无 `CoroutineExceptionHandler`）抛出即崩溃；`NoteDao.kt:118-119` 的 `IN (:ids)` 由 Room 展开为 N 个绑定参数，**API ≤ 30 的 SQLite 变量上限是 999**（minSdk 24）。
- 影响：1000+ 条笔记「全选 → 删除/移动/置顶」或 1000+ 条回收站「清空」→ `too many SQL variables` → 崩溃，且用户看不到任何反馈；另 `TrashViewModel.purgeAll` 用 `notes.value`（初值空列表）→ 极端时序下 `getByIds(emptyList())` 会生成非法的 `IN ()`（已核对 KSP 生成的 `NoteDao_Impl`）。
- 建议：`getNotesByIds` 内 `ids.chunked(900)`；`runBatch`/回收站操作包 try/catch，失败给一句文案并保留选中态；`purgeAll` 空列表直接返回。价值：中 × S/M

### 批次 F · 隐私与可靠性（P1）

### 77. API Key 输入框不进 saved instance state
- 现状：`SettingsScreen.kt:102` 用 `rememberSaveable` 保存用户刚粘贴的明文 Key，而同页文案声明「Key 仅保存在本机（Keystore 加密）」（`:434-435`）。
- 影响：尚未点保存就旋转/进程重建 → 明文 Key 进入 Activity 的 saved instance state Bundle（系统进程持有、可被序列化落盘），与承诺不符。
- 建议：改成 `remember`（旋转后清空输入框，已保存的 Key 不受影响）。价值：中 × S

### 78. 导出/导入脱离 composition 作用域
- 现状：`NotesScreen.kt:97`（`rememberCoroutineScope`）驱动导出 `:139-147` 与导入 `:159-177`；`NoteEditScreen.kt` 的 txt 导出同理；`exporting/importing` 是 `remember`。`runCatching` 还吞掉了 `CancellationException`。
- 影响：导出大备份时旋转屏幕 → 协程取消 → **留下半截 zip 且没有任何提示**，用户以为备份成功；导入同样是「静默中断」（DB 有事务保护，但完成提示丢失）。
- 建议：把这两件事放进存活期更长的作用域（VM 或 `container.applicationScope`），结果经 VM `StateFlow` 回传给 `LaunchedEffect` 弹 snackbar；失败/取消都要有提示。价值：中 × M

### 79. 图片 GC 与备份的内存口径
- 现状：`collectImageGarbage` 用 `noteDao.getAll()`（`SELECT * FROM notes`，`NoteDao.kt:124-125`）把所有笔记完整正文读进内存再正则扫描（`NoteRepository.kt:242-243`）；而 revision 侧早已用 SQL `LIKE` 预筛并注释「避免全量载入内存」（`NoteRevisionDao.kt:37-39`）。备份侧 `BackupManager.kt:102,105-112,145` 把全库拼成一个 JSON 字符串再做第二份 `ByteArray`，导入又 `zip.readBytes()` 整包载入，且无条目大小上限。
- 影响：与「内存占用小」硬约束冲突；大库低端机在启动清理/清空回收站/备份时有 OOM 与卡顿风险。
- 建议：新增与 revision 对称的 `SELECT content FROM notes WHERE content LIKE '%![](img/%'`（引用集语义等价），GC 改用它；备份改流式写 JSON、导入限制单条目大小。价值：中 × M

### 80. 导入与 GC 边界加固合集
- 现状：① 导入直落时间戳（`BackupManager.kt:92-93`），`updatedAt = Long.MAX_VALUE` 的笔记会永远置顶且此后导入「更新不动」；② `deleteCategory` 的 `clearCategory` + `delete` 不在同一事务（`NoteRepository.kt:233-236`），中途失败会留下悬空 `categoryId`；③ 导入不限制 `notes.json` 大小（`:145`）；④ zip 条目名白名单 `^[A-Za-z0-9._-]+$`（`:48`）放行 `..`/`.`，`img/..` 会抛「导入失败」而非「不是有效的备份文件」；⑤ `thumb_*` 无条件删除（`ImageStore.kt:70-73`），旧备份里正文引用的 `thumb_xxx` 会被下次 GC 秒删；⑥ 列表类查询的 `ORDER BY` 无 tiebreaker（`NoteDao.kt` 全部列表查询；对比 `NoteRevisionDao.kt:16` 有 `id DESC`），叠加上 `NotesScreen.kt:443` 的 `animateItem()` 会出现「等值时间戳下行自己跳」。
- 建议：逐项小改（时间戳钳制到 `[0, now+1d]`、`deleteCategory` 包事务、条目大小上限、白名单加纯点名校验、`thumb_` 仅在无引用时删、ORDER BY 末尾补 `, id DESC`）。价值：中 × S/M

### 批次 G · AI 助手（P1）

### 81. AI 服务商身份与聊天页保持一致
- 现状：`AppNavHost.kt:119` 在目的地 lambda 里 `container.aiSessionFactory()` 建会话，而 `AppContainer.kt:90-101` 把 provider **闭包捕获**（endpoint / serviceId / key / model / thinking 全按旧 provider 取），聊天页 push 在设置页之下、返回时 VM 不重建；而 `AiChatViewModel.kt:144` 的 `hasApiKey()` 用的是**当前** provider。
- 影响：DeepSeek 已配 Key 时打开聊天 → 设置里切到未配 Key 的硅基流动 → 返回发送被「未配置 API Key」拦下；用户照提示填了硅基流动的 Key 后再发，请求**仍然发给 DeepSeek**（账单与预期不符）。同 provider 换模型则立刻生效但顶栏副标题仍是旧模型名，与 `UiState.modelName` 注释宣称的正好相反。
- 建议：给 VM 传一个「当前服务商/设置版本」的 `StateFlow`，变化时重建 session 并刷新 `providerName/modelName`；或离开聊天页时强制重建该 BackStackEntry。价值：中高 × M

### 82. 看门狗超时与重发必须真正停流
- 现状：看门狗超时分支只 `finalizeAssistant` + 横幅，**不调用 `session.stop()`**（`AiChatViewModel.kt:293-306`，对照 `stop()` `:217-221` 是先 stop）；`AiApiSession.send()` 只 `job?.cancel()` 而不 `streamer.cancel()`（`AiApiSession.kt:44-46`），而阻塞中的 `readLine()` 不响应协程取消（`AiApiClient.kt:172` 自述）。
- 影响：超时后旧 HTTP 连接继续跑到服务端生成完（token 照常计费后被丢弃）；用户点「重试」也不会打断旧流。
- 建议：超时分支补 `session.stop()`；`send()` 在 `job?.cancel()` 后补 `streamer.cancel()`；测试补 `assertEquals(1, fake.stopCount)` 断言。价值：中 × S

### 83. 空回答按失败处理 + finish_reason 规则 + 重试入口
- 现状：HTTP 200 但读不到任何 `data:` 行时直接 `emit(Finished(""))`（`AiApiClient.kt:170-198`），`AiApiSession.kt:55` 转发 `Done("")` 并**丢弃 finishReason**，`AiChatViewModel.finalizeAssistant` 无判空（`:385-398`）→ 落库 `content=""`、`status=done`。
- 影响：服务商/网关忽略 `stream:true` 返回整段普通 JSON 时，有效回答被静默丢弃，界面显示「（没有内容）」；因 `status==done`，重试按钮不出现，用户只能重打字。设计文档要求的 `content_filter / insufficient_system_resource / aborted` 无内容按失败落库也未实现。
- 建议：`Done` 且文本空白 → 走 Failed 分支（不落库 + 横幅「回答为空，请重试」）；`AiSession` 透传/使用 `finishReason`；补 `Done("")` 用例。价值：中 × S

### 84. 历史裁剪保持 user/assistant 配对
- 现状：`AiApiMessageBuilder.trimHistory`（`:49-64`）用 `kept = [0] + subList(firstMiddle, size-1) + [last]`，`firstMiddle` 逐条 +1、不区分角色。
- 影响：长会话超 60k 字符后，保留列表可能出现「凭空出现的回答」（其对应提问已被丢弃）或连续两条 user；模型容易回答被丢弃的那个问题。
- 建议：按 user+assistant 成对丢弃（`firstMiddle` 每次 +2 或对齐到 user 边界），并补「保留列表 role 交替」断言。价值：中 × S

### 85. 流式 Markdown 渲染节流（避免 O(n²)）
- 现状：每个 SSE 分片都更新 `streamingText`（`AiChatViewModel.kt:337-341`，修好分片追加后每片都会触发 UI），而 `MarkdownContent.kt:37` 以 `content` 为 key 全量重解析、每块再 `MarkdownInline.build`（`:93-99`），流式气泡把所有块放在同一个 LazyColumn item（`AiChatScreen.kt:363-370`），且每片都 `scrollToItem`（`:301-303`）。
- 影响：长回答流式时主线程 O(n²) 解析 + 重排 → 掉帧、发热（此前被「只显示最后一片」掩盖，修复后会立刻暴露）。
- 建议：VM 侧对 `streamingText` 做 60–100ms 节流合并（零依赖），或只对最后一个未闭合块增量渲染；`UiState` 标 `@Immutable` 减少无效重组。价值：中 × M

### 86. 自定义地址明文 http 策略与错误文案
- 现状：设置页显式接受并宣传 `http://`（`SettingsViewModel.kt:85-93`、`SettingsScreen.kt:316-323,586-589`），但 manifest 无 `usesCleartextTraffic`/`networkSecurityConfig`（`res/xml/` 只有 `file_paths.xml`）、targetSdk 34 → 运行时明文被系统阻断，`IOException` 被吞成 `NETWORK_ERROR`（`AiApiClient.kt:255-262,417`）。
- 影响：局域网自建（Ollama / LM Studio 等 `http://192.168.x.x:11434/v1`）永远只会得到「网络错误，请检查网络后重试」，用户没有任何线索；空地址同理（聊天页不校验地址非空）。
- 建议：二选一 —— UI 只接受 https 并给出明确文案，或加作用域受限的明文放行配置；同时把 `UnknownServiceException` 映射成专有提示，并在聊天页前置校验地址非空。需真机确认（Robolectric 不复现明文策略）。价值：中 × S

### 87. Markdown 链接 scheme 白名单
- 现状：`MarkdownInline.kt:101-120` 用 `LinkAnnotation.Url(...)` 且不传 `linkInteractionListener`，点击交给 Compose 默认 `LocalUriHandler.openUri`（`Intent.ACTION_VIEW`，无 try/catch、无 scheme 过滤）。
- 影响：回答里出现 `file://`、`javascript:`、`intent://` 或未知 scheme 时，用户一点可能抛 `ActivityNotFoundException`/`FileUriExposedException`（进程内崩溃）或直接跳到任意第三方 App。
- 建议：传 `linkInteractionListener`，只放行 http/https，其余给 snackbar 提示；scheme 过滤抽成纯函数便于单测。价值：中 × S

### 88. AI 体验小坑合集
- 现状：① 「测试连接」不保存输入框里的 Key（`SettingsScreen.kt:459-469` vs `:441-451`）→ 测试成功但进聊天仍「未配置 API Key」；② 跨服务商共用同一个 `apiKeyInput`（`:102`）→ 切 tab 后未保存的输入残留，可能存错服务商；③ 选区折叠时「替换选中」静默 no-op（`AiResultApplier.kt:20` + `NoteEditScreen.kt:350-357` 已消费结果）；④ 空回答上点「插入正文」插入空串且无提示；⑤ 自定义服务商模型留空 → `"model": ""` → 服务商 400「参数错误」，设置页事前不校验；⑥ 删除会话不清理 `ai_drafts` 键（`AiChatViewModel.kt:223-231`），随删笔记无限累积；⑦ 会话抽屉不显示所属服务商（`serviceId` 已入库但未展示，多服务商无法区分）；⑧ 深度思考内容随回答完成即清空（设计如此），计费的思维链事后无法回看/复制；⑨ 无单条消息删除/编辑（能力缺口）。
- 建议：逐项小改（保存 Key、输入框按服务商隔离、折叠选区给提示、空回答禁用落地按钮、模型必填校验、purge 时清草稿键、抽屉显示服务商）。价值：中 × S/M

### 批次 H · 列表 / 搜索 / 导航（P1–P2）

### 89. 搜索 LIKE 转义 + 高亮词 trim + 深命中线索
- 现状：三处 `LIKE '%' || :query || '%'` 无 `ESCAPE`（`NoteDao.kt:56,59,62`），仓库只做 `trim()`（`NoteRepository.kt:34-38`）；而高亮用的是**未 trim 的原始 query**（`NotesScreen.kt:440` → `NoteRow.kt:59-63`）；投影只取 `substr(content,1,400)`（`NoteDao.kt:21/56`）。
- 影响：搜 `_` / `%` 会命中几乎全部笔记（而高亮一处都找不到 → 「全命中却零高亮」），字面 `%` 永远搜不准；输入「会议 」（尾随空格，中文输入法常见）能搜到但不高亮；命中在正文 400 字之后时该行完全没有命中线索。
- 建议：`LIKE ... ESCAPE '\'` + 绑定前转义 `\ % _`；高亮用 `query.trim()`；深命中改由 DAO 回传「命中位置超出投影」标记，行内给一句小字说明。价值：中 × S

### 90. 保留期文案与回收站口径统一
- 现状：两处破坏性确认框把保留期写死成「30 天」（`NoteEditScreen.kt:793` 单条删除、`NotesScreen.kt:511` 批量删除），而设置页与回收站用的是可配置的 `TrashRetentionStore`（`TrashScreen.kt:97,172`）；回收站恢复/彻底删除即使什么都没做也会提示「已恢复/已彻底删除」（`TrashViewModel.kt:42-54` 用 `getNote(id)?.let{}` 后无条件 `onDone()`），且 `_purging` 在 launch 内设置，双击可绕过；剩余天数向下取整、不足 1 天一律显示「即将自动清理」，而清理实际只发生在冷启动或打开回收站；术语上回收站里的「删除」实为永久删除，主页批量软删却提示「已删除 N 条」。
- 建议：保留期文案参数化（抽纯函数，两处共用）；回收站按 `getNote != null` 决定文案；剩余天数上取整并改「剩余不足 1 天」；行按钮改「彻底删除」、批量提示改「已移入回收站 N 条」。可选：回收站支持搜索（`deletedAt IS NOT NULL` 变体），缓解误删后难找回。价值：中 × S

### 91. 多选随搜索/数据收敛（不删看不见的笔记）
- 现状：`onQueryChange` 只改关键词、不清多选（`NotesViewModel.kt:59-61`，对比 `onFilterSelect:63-67` 会清）；搜索栏在多选下仍显示（`NotesScreen.kt:326`）；`runBatch`（`:111-120`）按 id 重读但不与当前可见集取交集；`selectAll` 读 `notes.value`，防抖窗口内可能选中上一轮结果集。
- 影响：搜「a」→ 长按选中 A → 关键词改「b」（A 不在结果里、屏幕上没有任何选中标记）→ 顶栏仍显示「已选 1 项」→ 删除 → **A 被删掉，而它根本不在用户眼前**；选中项被他处删除后计数也会虚高。
- 建议：`onQueryChange` 非空变更时 `exitSelection()`；`runBatch` 与可见集取交集；`notes` 更新时裁剪已消失的 id。价值：中高 × S

### 92. 首帧加载态统一 + debounce 首帧不延迟
- 现状：`query.debounce(200)` 连初始空串一起延迟（`NotesViewModel.kt:43`）→ 冷启动主页必然先闪 ≥200ms 的「加载中…」（Room 其实几毫秒就返回）；分类页与回收站则相反——用 `emptyList()` 作初值，进页面先闪「暂无分类 / 回收站是空的」（`CategoriesViewModel.kt:20-21`、`TrashViewModel.kt:24-26`），而主页明确用 `null` 表示加载中（`NotesViewModel.kt:40-53`）。
- 建议：`debounce { if (it.isEmpty()) 0L else 200L }`；分类页/回收站改为 `null`/`loaded` 初值并复用现有加载占位。价值：中 × S

### 93. 历史页与导航层级修正
- 现状：历史恢复成功后 `popBackStack("notes", inclusive = false)`（`AppNavHost.kt:146-151`）会连带弹掉编辑器（甚至从日历进来的路径也被扔回主页）；笔记已不存在时仍显示「保存一次后开始记录」（`NoteHistoryScreen.kt:123-130`）；全文视图用 `Column + verticalScroll` 一次性布局整篇（`:236-245`），而 diff 视图反而是 LazyColumn；恢复提示的消费时序为「显示后才消费」（`NotesScreen.kt:185-189`）→ 旋转会重弹、中途离开则永不消费。
- 建议：只弹一层（`popBackStack()`）并写到 `previousBackStackEntry`；空态区分「笔记不存在」；全文改 LazyColumn；先消费再 showSnackbar。价值：中 × S/M

### 批次 I · 编辑页 / 平台（P1–P2）

### 94. 编辑页加载态与保存失败反馈
- 现状：`canSave` 对存量笔记恒为 true（`NoteEditScreen.kt:404-405`），回填有「用户先敲字就不再回填」的守卫（`:322-336`），而 `saveNote` 目标行不存在时 `existing == null → return id` 静默成功（`NoteRepository.kt:91-93`）；`save()` 无 catch，DB 异常直接逃出 `viewModelScope`。
- 影响：① 冷启动/迁移后用空编辑器敲了标题就保存 → 正文被空文本覆盖（可经历史快照找回）；② 笔记在别处被删除/彻底清理 → 点保存**什么都没写**却直接退回列表，用户以为存住了；③ 任何 DB 失败 → 崩溃而不是「保存失败」。
- 建议：新增 `loaded = isNewNote || note != null`，未加载时正文只读并显示占位、保存禁用；`saveNote` 目标缺失时返回失败信号，UI 提示「笔记已不存在，无法保存」；`save/saveDraft/delete` 包 try/catch 并给文案。价值：中高 × S/M

### 95. 「不保存」抑制标志 + 保存期间只读输入
- 现状：ON_STOP 兜底只看 `dirty && !isSaving`（`NoteEditScreen.kt:407-411`），「不保存」分支直接 `onBack()`（`:774`），没有抑制标志；`saveDraft` 一进协程就在 `withTransaction` 处挂起，而 `popBackStack` 会让 VM 走向 `onCleared()` 取消 `viewModelScope` → **是否写入取决于时序**（设计文档 `plans/2026-09-13-edit-ux-fixes.md:293` 的验收标准明确要求「不保存 → 列表无新笔记」）。另外保存期间两个输入框没有 `readOnly = isSaving`（`:520`、`:582`）→ 点保存后补敲的字不属于这次保存，且没有任何「保存中」提示。
- 建议：加 `discarded` 标志并在 ON_STOP 守卫里排除（判定抽成可单测的纯函数）；`isSaving` 时输入框 `readOnly` + 顶栏显示「保存中…」。价值：中 × S

### 96. 旋转保留对话框与待写文件状态
- 现状：`NoteEditScreen.kt:301-313` 的 `menuOpen/showCategorySheet/showDatePicker/showAddCategoryDialog/showDeleteDialog/showExportDialog/showImageExport/showUnsavedDialog/previewFile/exporting` 全是 `remember`，新建分类输入框的 `name` 也是（`:738`）；`NoteExportDialog.kt:95-96` 的 `pendingFiles/writing` 同为 `remember`，而 SAF 回调用 `files != null` 把关（`:113-114`）。
- 影响：输入「工作安排」后旋转 → 对话框与已输入内容一起消失；导出选好目录后旋转 → 对话框与 `pendingFiles` 都没了，**既不写文件也不报错**。
- 建议：承载用户意图的开关与输入改 `rememberSaveable`；`pendingFiles/writing` 提到 `NoteExportDialog` 已有的 `DialogViewModelStoreOwner`（`:63-65`）里，或在重建时检测待写文件并重渲染。价值：中 × M

### 97. 图片编辑体验（标记可视化 / 删图入口 / 孤儿图）
- 现状：编辑态正文就是纯 `BasicTextField`，图片以 `![](img/<uuid>.webp)` 原文显示（`:582-605`），删图必须手工精确删掉整段标记（删漏一个括号正则就不再匹配）；插图后撤销、或插图后未保存直接返回，文件都留在 `notes_images/`（GC 只在 `saveNote` 判定移除标记时触发，`updateDraft` 明确不 GC）。
- 建议：预览模式长按图片 → 「删除这张图片」（等价于移除标记，保存走既有 GC）；底部给「共 N 张图片」计数；会话内记录本次插入的文件名，撤销/离开时清理已不再被正文引用的那些；补一条「半截标记按普通文本处理」的解析测试。价值：中 × M

### 98. 导出缺图统计与渲染同口径 + txt 图片说明
- 现状：缺图统计走 `imageDimensions`（只读文件头，`NoteImageRenderer.kt:255-258,452-461`），真正绘制走 `decodeImage`（完整解码，`:463-472`）且失败时静默 `return@forEach`（`:367`）——「头可读、像素被截断」的图片会被静默丢掉却不计数；`renderToCache` 也不回收实际跳过清单。另 txt 导出原样保留 `![](img/uuid)` 标记且不说明图片未导出（`BackupManager.kt:218-234`），而系统分享路径反而会追加「含 N 张图片，未包含」。
- 建议：`renderPages` 回传被跳过的图片名集合并并入 `unreadableImageCount`（统计与渲染同一口径）；txt 导出与分享口径对齐（剥标记 + 末尾说明）。价值：中 × S/M

### 批次 J · 体验打磨与平台加固（P2）

### 99. 无障碍一批
- 现状：编辑页两个输入框都没有 `semantics`/标签（占位文字画在 `decorationBox` 里，TalkBack 读作两个无名编辑框，`NoteEditScreen.kt:518-542,582-605`）；预览里的图片共用 `contentDescription = "图片"`（`:570`），全屏预览只能点图片本身关闭（`NoteImagePreviewDialog.kt:26-44`）；日历的 4dp 圆点无语义、格子固定 44dp/36dp（`CalendarScreen.kt:180-217`）；多选只靠底色表达（`NotesScreen.kt:442` + `NoteRow.kt:70-84`，无 `semantics { selected }`、无 Role）；分类页色点 24dp 且无描述（`CategoriesScreen.kt:228-236`）。
- 建议：输入框加 `semantics { contentDescription = "标题"/"正文" }`；预览图补可读的关闭按钮；日历格子按 `DateMark.count` 给「9月10日，3 篇笔记」；多选行加 `selected` 语义；色点点击区补到 48dp 并加描述。价值：中 × M

### 100. 撤销栈封顶 + 字数统计缓存 + 平台加固合集
- 现状：① `NoteUndoController` 只按**条数**限流（`maxEntries = 100`，存整份 `TextFieldValue`），长笔记常驻约 100 份全文副本（`NoteUndoController.kt:14,36-38`）；② 字数统计每次重组都对全文跑正则（`NoteEditScreen.kt:545` → `NoteContentParser.kt:34`）；③ `collectAsState`（约 30 处，如 `NotesScreen.kt:89-95`、`MainActivity.kt:23`）而非 `collectAsStateWithLifecycle`（依赖已在）；④ Keystore 往返在主线程（`AiChatViewModel.kt:61-70` 构造期读 Key、`SettingsViewModel.kt:60,106-118` 按钮回调直调）；⑤ `enableEdgeToEdge` 放在 `LaunchedEffect(darkTheme)` 里（`MainActivity.kt:40-50`）→ 首帧无 edge-to-edge，可能跳一下；⑥ 图片解码 `catch (e: Exception)` 兜不住 `OutOfMemoryError`，且顺带吞掉 `CancellationException`（`ImageStore.kt:50`）；⑦ 超长正文经 `rememberSaveable` 进 Bundle（`NoteEditScreen.kt:293-295`），AI 入口又把正文与选区写进导航 `SavedStateHandle`（`AppNavHost.kt:94-99,111-113`）→ 超长笔记有 `TransactionTooLargeException`/状态被丢弃的风险（**需真机确认**）。
- 建议：撤销栈加「保留字符数」上限（或改存 delta）；字数统计用 `derivedStateOf` 缓存；`collectAsStateWithLifecycle`；Key 读写移入 `withContext(Dispatchers.IO)` + 用 `StateFlow` 暴露「已配置」；`enableEdgeToEdge` 移出 `LaunchedEffect`；`catch` 补 `OutOfMemoryError`/重抛 `CancellationException`；长正文只把 `noteId/draftId` 放进 Bundle、正文靠 DB 草稿回读。价值：中 × S/M

---

## 协同开发建议

**文件冲突地图**（同一文件的多条目不可并行改）：

| 文件 | 涉及的条目 |
|---|---|
| `ui/notes/NoteEditScreen.kt` | 3(集成)、4、7(接入)、8、9(接入)、10、18、22、24、25、26、27、28 |
| `ui/notes/NotesScreen.kt` | 1、5、6(传参)、11(入口)、12、13、16、17、20、23、29、30、31、32 |
| `data/db/NoteDao.kt` + 仓库/VM | 2、11、12、16、20、21、22、23、29、32、40、41、42、43、44、45、46 |
| `ui/trash/`（TrashScreen/TrashViewModel） | 31、42 |
| `ui/ai/`（AiChatScreen/AiChatViewModel/AiApiSession） | 36（已完成）、37/38（已作废） |
| `ui/categories/` + `ui/settings/` | 33、34、35、49 |

**建议的并行轨道**（执行时可用子代理/workflow 分头开发，最终由一人串行集成与全量回归）：

1. **轨道 A（纯逻辑，先于一切并行）**：#3 的 `NoteUndoController`（新文件+单测）、#7 的 `FileNameSanitizer`（新文件+单测）、#9 的 `NoteImagePreviewDialog`（新组件）——不碰任何现有文件，零冲突。
2. **轨道 B（DAO/VM 层）**：#11 排序（DAO + VM + 设置存储）——与轨道 A 无交集。
3. **轨道 C（组件层）**：#6 分类名、#13 高亮（`NoteRow.kt`，新参数向后兼容）——与 A/B 无交集。
4. **轨道 D（编辑页集成，串行一人）**：#3 集成 → #4 → #8 → #10 → #9 接入 → #7 接入；一次小批次或连续提交，避免互相踩线。
5. **轨道 E（列表页集成，串行一人）**：#1 → #5 → #6 传参 → #11 入口。
6. **独立里程碑**：#2 回收站（建议 P1 收尾后启动）、#12 多选（依赖 P1 的列表页状态稳定后启动）。
7. **决策后执行**：#14、#15。

**每批收尾**：全量 `:app:testDebugUnitTest` 通过 + 手动真机验证清单项 + 按 AGENTS.md 递增版本、同步 README；文档状态回写本清单。

**五域审计批次（71–100）的推进建议**：

1. **批次 E（P0 数据安全，串行一人）**：#71 → #72 → #73 → #74 → #75 → #76。都动 `NoteRepository` / `ImageStore` / `BackupManager`，互相有交集，建议一次做完再统一回归（#71 与 #74 同属「文件先落盘」家族，最好同批）。
2. **批次 F（隐私与可靠性）**：#77 可独立并行（只动设置页）；#78/#79 各一处集中改动；#80 是若干独立小修，适合填充式完成。
3. **批次 G（AI）**：#82/#83 都是 `AiChatViewModel` + `AiApiSession` 的小改，可先做；#81 需要产品口径（设置变化是否立刻作用于已打开的聊天页）；#84/#85/#86/#87 相互独立可并行；#88 是合集，建议最后扫尾。
4. **批次 H（列表/搜索/导航）**：#89/#90/#91 都动 `NotesScreen`/`NoteDao`，串行；#92/#93 独立。
5. **批次 I（编辑页/平台）**：#94/#95 同文件（`NoteEditScreen` 的保存与状态机）串行；#96/#97/#98 相对独立。
6. **批次 J（打磨）**：#99/#100 建议作为收尾批，逐项独立可并行。
7. **真机验证项**：#86（明文策略）、#100 的第 ⑦ 项（Bundle 体积）、#100 的第 ⑤ 项（首帧 edge-to-edge）以及所有 Compose 组合期行为（#95/#96）只能上真机确认——本仓库没有 Compose UI 测试基础设施，补它需新增依赖，违反硬约束。

## 修订记录

| 日期 | 内容 |
|---|---|
| 2026-09-13 | 初稿：整理 15 条待办（P0×2、P1×9、P2×4），附协同开发轨道建议。 |
| 2026-09-13 | 第一批完成（v1.2.0）：条目 1、3–11 与 13 全部实现，单测 224 → 251 全绿。执行中的两个细节调整：撤销/重做只作用于正文（标题不纳入）；字数统计显示在标题行右侧（为底部栏撤销/重做按钮腾出空间）。 |
| 2026-09-13 | 条目 2 回收站完成（v1.3.0）：软删除 + 30 天自动清理 + 回收站页面（恢复/彻底删除/清空）；Room v4 + MIGRATION_3_4 + 迁移测试；备份带 deletedAt 且导出排除回收站；单测 265 全绿。 |
| 2026-09-13 | 条目 12 多选批量操作完成（v1.4.0）：长按进入多选，全选 / 批量置顶 / 批量取消置顶 / 批量移动分类 / 批量删除（进回收站）；选择状态在 ViewModel（旋转不丢）；单测 276 全绿。全部可执行条目（1–13）已完成。 |
| 2026-09-14 | 条目 16 未分类筛选入口完成（v1.4.1）：主页 tab 常驻「未分类」，配合多选批量移动实现未分类笔记整体归类；顺带修复「所选分类被删除后筛选悬空」问题；单测 276 → 283 全绿。 |
| 2026-09-14 | 6 域并行只读体验审计（数据可靠性 / 主页列表 / 编辑页与历史 / 分类设置导航 / AI 助手 / 数据层性能）完成，新增条目 17–50（全部「已建议，待实现」），按批次 A（1.4.2）/ B（1.4.3）/ C（1.4.4）/ D（1.5.0 含 Room v5 迁移）排期。同步修正两处文档漂移：条目 9 全屏图片预览从未接线（死代码，改见 26）；AGENTS.md 的 Room 版本 v3 → v4。另拍板遗留：条目 11 与 16 的排序验收口径冲突（见 29）。 |
| 2026-09-14 | 批次 A 完成（v1.4.2）：条目 21 排序文案「最早创建」→「最新创建」（SQL/测试语义本就为最新在前）；条目 22 撤销合并策略（600ms 窗口内相邻单字符增删/纯选区移动合并为一格，插图与贴 AI 结果 force 强制新格）；条目 23 主页冷启动加载态（notes 初始 null → 加载中占位，不再闪「还没有笔记」）；全量单测 295 全绿。 |
| 2026-09-14 | 批次 B 完成（v1.4.3）：条目 25 新建自动聚焦标题 + 标题 IME「下一项」跳正文 + 贴 AI 结果后恢复正文焦点；条目 26 全屏图片预览接线（NoteImagePreviewDialog 结束死代码状态）；条目 27 插图失败 snackbar + importAndCompress 失败清理半成品（统一走异常路径）+ saveNote 移除图片标记即触发 GC（历史快照引用仍保留）；条目 28 导出对话框 Ready 态显示「有 N 张图片无法读取，导出结果不含它们」（统计与渲染跳过同一口径、按名去重），分享文案追加含图说明；全量单测 306 全绿（含导出域缺图统计用例 5 条）。 |
| 2026-09-14 | 批次 C 完成（v1.4.4）：条目 29 排序改为全视图生效（#11 口径为准，#16 的禁用说明作废）＋搜索态显示分类名＋新增 `snippetForHighlight` 命中窗口摘要；条目 30 相对时间增加「刚刚 / N 分钟前 / N 小时前」（同一天内生效，跨天仍优先「昨天」）；条目 31 置顶图钉改主题色并加「置顶/其他」分组标题，回收站行可点开只读预览（标题/摘要/图片数/剩余清理天数）且彻底删除确认带摘要；条目 32 搜索输入 200ms 防抖 + `NoteRow` 缓存纯文本与高亮 + 分类 id→实体 Map；条目 33 新建分类可选 6 色（默认最少使用色，不再随机）、行内点色点循环换色并持久化；条目 34 设置页新增「通用」分区（默认排序读写 NoteSortStore、回收站保留期 7/30/90 天走新增的 TrashRetentionStore，启动与打开回收站清理均按该保留期，列表密度未做）；条目 35 动态取色开启时色板降透明并提示，点色圈关闭动态取色并 snackbar 反馈；全量单测 338 全绿。 |
| 2026-09-14 | 批次 D 完成（v1.5.0）：条目 39 `importAndCompress` 全程移入 `Dispatchers.IO`；条目 40 编辑页预览改用 300px 缩略图（缺失回退原图，点开仍看原图）+ `MyNoteApp` 实现 `ImageLoaderFactory` 显式限制图片内存缓存（堆约 10%）；条目 41 新增 `getDeletedBefore(cutoff)`，启动/打开回收站的到期清理改为 SQL 过滤 + 事务批量删；条目 42 新增 `purgeNotes(list)`（单事务 + 一次 GC），`TrashViewModel.purgeAll` 改用并暴露 `purging` 状态，回收站按钮在清理中禁用并显示「清理中…」；条目 45 `notes` 补三组索引（deletedAt / categoryId / pinned+updatedAt）+ `MIGRATION_4_5` + 迁移测试，全部列表查询投影化为 `NoteListItem`（`substr(content,1,400) AS summary`），列表不再整表携带正文；全量单测 342 全绿。同批发现并修复投影波及的接线点（NoteRepository/TrashViewModel/AiChatViewModelTest）。 |
| 2026-09-14 | **P1 且非 AI 相关条目全部完成**（21-23、25-35、39-42、45，共 19 条，覆盖 v1.4.2 → v1.5.0）。仍待决策/待实现：P0 条目 17-20、24、43、44；AI 域条目 36-38；可选条目 46-50；用户决策条目 14、15。 |
| 2026-09-15 | **AI 助手改为 DeepSeek 官方 API（v1.6.0）**：网页驱动全套（WebView / AiWebDriver / DeepSeekDriver / 登录态 / 可见网页）移除；用户自填 API Key（Keystore 加密），模型 deepseek-flash / deepseek-v4-pro + 深度思考开关，设置页新增「AI 助手」分区与测试连接；条目 37、38 作废，36 在 API 模式下仍适用（未实现）；全量单测 351 全绿。 |
| 2026-09-15 | **P0 批次完成（v1.6.1）**：条目 17 导出防「假成功」（zip 拿不到输出流抛错、txt 返回 false）；条目 18 txt 导出改用编辑态现值（含标题、未保存新笔记可导出、结果 snackbar、导出中禁用入口）；条目 19 历史恢复确认框在带草稿时提示「未保存的修改将一并丢弃」，恢复成功回列表页弹「已恢复历史版本」（`NavResults.RESTORE_MESSAGE` 通道）；条目 20 切筛选/搜索清多选、取消最后一项自动退出多选、批量菜单空选禁用；条目 24 按拍板采用 ON_STOP 静默保存（新增 `NoteRepository.updateDraft` 不写历史/不 GC；新笔记标题/正文非空才静默入库并记 `draftId`，后续保存不重复插入；空白新笔记禁用保存；新建笔记仅标题/正文非空才算脏）；条目 43 导入单事务 + 条目名白名单（防路径穿越）+ 图片流式落盘 + 分类同名复用 id 重映射 + 野 categoryId 置 null；条目 44 按新增/更新/跳过/回收站四类真实计数汇报，确认框补分类与回收站说明；全量单测 351 → 376 全绿。 |
| 2026-09-15 | **AI 助手优化批完成（v1.7.0，条目 51–64 全数落地）**：51 看门狗改空闲超时（`reasoning_content` 帧作心跳）；52 失败/中断气泡「重试」重发最后一条 user（不重复落库）；53 流式自动跟随（上滑暂停）；54 历史 60k 字符预算裁剪 + 笔记正文 20k 截断；55 会话删除二次确认；56 空失败不再落库；57 末块 `usage` 显示「上次用量」；58 设置页「查询余额」+ 测试连接列出官方模型并比对内置常量；59 `reasoning_content` 折叠展示；60 草稿按 noteId+sessionId 持久化；61 ``` 围栏代码块等宽渲染（`CodeBlockParser`）；62 每会话独立 HTTP 客户端（取消隔离）；63 `sendRequested` 异常复位；64 `Accept-Encoding: identity` + 429/503 预连接单次重试。并行六轨道（协议/VM/UI/组装/存储/设置）实现 + 串行集成；全量单测 376 → 442 全绿，debug/release 构建通过。 |
| 2026-09-15 | **AI 兼容性加固批完成（v1.7.1，条目 65–66）**：65 动态模型列表——「测试连接」成功后把官方 `/models` 结果持久化（`AiSettingsStore.models/setModels`）并作为设置页模型选项，内置常量退化为默认值/兜底，失效模型自动切换；66 422 参数降级——请求携带 `thinking` 遇 422 自动去掉该字段重试一次（与 429/503 退避互不叠加）。两条轨道并行子代理 + 串行集成；全量单测 442 → 456 全绿。 |
| 2026-09-15 | **新用户欢迎笔记完成（v1.7.2，条目 67）**：首次启动且主页无可见笔记时自动生成「欢迎使用 MyNote」功能介绍（`WelcomeNoteSeeder` + `OnboardingStore` 一次性标记，`NoteDao.countVisible()` 判断；老用户升级只写标记不生成，删除后不重建）；`MyNoteApp` 启动协程静默执行。全量单测 456 → 463 全绿。 |
| 2026-09-15 | **字体切换（条目 68）评估后暂缓**：现状无字体切换；三方案（系统字体 / 内置子集 / 用户自选）成本与收益均已评估，用户拍板保持极致轻量，不实现、不改代码。详见条目 68。 |
| 2026-09-15 | **笔记日期与日历视图完成（v1.8.0，用户新增需求）**：笔记新增可选归属日期（`NoteEntity.noteDate` 存本地零点毫秒，Room v6 + `MIGRATION_5_6` + 索引）；编辑页底栏「日期」入口 + Material3 DatePickerDialog（可清除，UTC↔本地零点经 `CalendarDates` 互转）；主页 ⋮ → 日历页（月网格圆点 + 当天笔记列表，只读，「今天」与前后月切换，切月选中日回 1 号）；旧笔记迁移后无日期、不进日历；备份含该字段且兼容旧文件；历史恢复旧版保留当前日期。全量单测 547 全绿（本次新增 28 个）。 |
| 2026-09-15 | **AI 回答 Markdown 渲染完成（v1.9.0，条目 61 升级）**：新增 `MarkdownParser`（标题/递归嵌套列表/引用/分隔线/表格/围栏代码块）、`MarkdownInline`（粗体/斜体/删除线/行内代码/可点链接/图片降级 alt/反斜杠转义）、`MarkdownPlainText`（插入正文与存笔记转纯文本，「复制」保留原文）、`MarkdownContent`（H1–H6、任务 ☐/☑、引用竖线、等宽列表格横向滚动，样式跟随主题）；流式回答逐帧实时渲染、完成后无跳变；移除 `CodeBlockParser`；零新增依赖、VM/数据库零改动。全量单测 547 全绿（本批净增 0：新增 `MarkdownPlainTextTest` 12 条、移除 `CodeBlockParserTest` 12 条；Markdown 特性累计新增 68 条、移除 12 条，其中 56 条已计入 v1.8.0 的 547 基线）。 |
| 2026-09-15 | **AI 服务商可切换完成（v1.10.0，条目 69）**：`AiProvider` 枚举收敛一切服务商差异（DeepSeek / 硅基流动 / 自定义 OpenAI 兼容：地址、`thinking` 对象 / `enable_thinking` 布尔、余额接口与 schema、`?sub_type=chat` 模型过滤、默认模型、Key 提示文案）；`DeepSeekApiClient`→`AiApiClient`、`DeepSeekApiSession`→`AiApiSession`、`DeepSeekSseParser`→`AiSseParser`（协议层不变，端点驱动）；请求体改 `buildJsonObject` 组装，422 去参数重试仅对支持思考的服务商生效；错误详情兼容 `error.message` / `message` / 纯字符串三种形态，文案去 DeepSeek 化；设置页新增服务商 tab、自定义地址（http(s) 校验）与模型自由填写，深度思考/查询余额按能力显示；Key、模型、思考开关按 serviceId 命名空间隔离（DeepSeek 沿用旧 key 名兼容）；聊天页隐私弹窗用 `session.displayName`。全量单测 547 → 582 全绿。 |
| 2026-09-15 | **AI 模型选择体验优化（v1.10.1，条目 70）**：连接成功文案模型数 > 5 时收敛为「共 N 个可用模型」；设置页模型数 > 8 改用可搜索 `ModelPickerDialog`（LazyColumn + 过滤 + 当前项对勾，≤ 8 仍用 tab）；聊天页顶栏副标题显示「服务商 · 模型」（`UiState.modelName` 进入会话时快照）。同批修正 AGENTS.md worktree 说明（signingConfig 配置期无条件读取签名文件，新 worktree 需从主树复制 `keystore.properties` 与 `app/mynote-release.keystore` 才能跑测试）。全量单测 582 → 584 全绿。 |
| 2026-09-15 | **版本演进欠账清理·文档批（非发布批，不改版本号）**：build-guide 单测数 376 → 582、release 产物体积统一为约 2.0MB；backlog 条目状态回写（36 已完成、37/38 已作废、40 缩略图部分已撤销、46 部分完成），总览表补齐 51–70 行（此前状态需在表格与正文两处维护，正是上述矛盾的成因）；补记一次从未记录的回退——v1.6.1 整体移除缩略图机制（`ImageStore` 仅保留 `thumb_*` 遗留清理）；已发布特性的设计文档状态由「实现中/待实现」回写为「已实现（版本）」，两份网页驱动 AI 文档加「已被 API 方案替换」横幅。校正历史口径：v1.6.0 修订记录的 351 经逐提交核对应为 352。 |
| 2026-09-15 | **版本演进欠账清理·代码批**：删除零调用者声明 `AppContainer.aiEndpoint()`、`AiChatRepository.getSession()`、`AiSessionDao.getById()`；删除被「半截回答按新消息落库」取代的 `AiMessageDao.updateContentAndStatus()`（v1.7.0 起无调用）与被动态模型列表取代的 `DeepSeekModels.label/isValid`（v1.10.1 起无调用）；删除 `hasUnsavedChanges` 从未使用的 `initialCategoryId` 参数（v1.6.1 脏值口径调整后遗留）；两套「标题→文件名」清洗（`FileNameSanitizer` 80/`note` 与 `ImageExportManager` 40/`笔记`）收敛为同一实现 + `maxLength` 参数（两侧输出不变，顺带修正截断点落在空白处会留尾随空格的瑕疵）；`PaperTopBar` 弃用图标换 `Icons.AutoMirrored.Filled.ArrowBack`；测试内历史 `serviceId="deepseek"` 统一为 `AiProvider.DEEPSEEK.serviceId`。全量单测 584 → 582 全绿（删 4 个随被替代实现失效或重复的用例、新增 2 个清洗参数化用例）。 |
| 2026-09-15 | **首批缺陷修复（用户可感知 5 项）**：① **预览模式崩溃**——预览态下正文输入框不在组合中，插图 / 贴 AI 结果 / 标题 IME「下一项」三处 `requestFocus()` 会抛 `IllegalStateException`（已对 Compose 1.7.3 字节码核实），新增纯 Kotlin `BodyFocusGate` 门控：预览中只登记待处理请求，切回编辑态再补一次聚焦；② **AI 流式只显示/只保存最后一个分片**——`Chunk` 契约为增量（`delta.content`）而 VM 用赋值，改为追加，停止/看门狗/退页落库的半截回答恢复完整；③ **AI 第二轮起丢失笔记正文**——落库的是裸输入，`AiApiMessageBuilder` 改为请求时对首条 user 幂等重包正文（新增 `AiPromptBuilder.NOTE_CONTEXT_HEADER` / `hasNoteContext`）；④ **保存键可能永久变灰**——`save()` 与 `saveDraft()` 原先共用 `saving` 标志，退后台草稿在途时点保存被静默拦下 → 界面 `isSaving` 永不复位、兜底一并失效；改为 `saveJob` / `draftJob` 分离，保存先 `join()` 在途草稿；⑤ **撤销合并丢整段起点**——`record()` 合并时不再改写栈顶，另用 `lastBefore` 判定邻接，`undo/redo/reset` 清空锚点：一次撤销恢复整段，且撤销后继续输入会新开一格。全量单测 582 → 593 全绿（新增 11 个用例，另修正 4 处把错误语义固化的旧断言）。 |
| 2026-09-15 | **五域审计入库（条目 71–100，全部「已建议，待实现」）**：数据持久化 / 编辑页状态 / AI 助手 / 列表搜索导航 / 跨域生命周期五路只读审计（已读码坐实并落到 file:line，行号基于 `f616283`），按批次 E（P0 数据安全 6 条）/ F（隐私与可靠性 4 条）/ G（AI 7 条 + 小坑合集）/ H（列表搜索导航 5 条）/ I（编辑页平台 5 条）/ J（打磨与平台加固 2 条）排期，总览表与"协同开发建议"同步补齐推进顺序与真机验证项。其中 6 条标注为高危：图片 GC 误删刚落盘的新图（71）、缩短保留期静默永久删除（72）、进程死亡后编辑页重复插入（73）、导入写图非原子（74）、AI 服务商身份不一致导致请求发给旧服务商（81）、编辑页未加载完可保存覆盖/静默丢弃（94）。同批已修复的 5 项不在本清单（见上一行）。 |
