package com.mynote.app.data.repository

import com.mynote.app.data.db.NoteDao
import com.mynote.app.data.settings.OnboardingStore

/**
 * 新用户首次启动引导：主页没有任何可见笔记时，落一篇功能介绍笔记。
 * 一次性标记在成功路径之后写入；已有笔记的老用户只写标记、不生成内容。
 */
class WelcomeNoteSeeder(
    private val noteDao: NoteDao,
    private val noteRepository: NoteRepository,
    private val onboardingStore: OnboardingStore
) {

    suspend fun seedIfNeeded() {
        if (onboardingStore.isWelcomeSeeded()) return
        if (noteDao.countVisible() > 0) {
            onboardingStore.markWelcomeSeeded()
            return
        }
        noteRepository.saveNote(null, WELCOME_TITLE, WELCOME_CONTENT, null, false, null)
        onboardingStore.markWelcomeSeeded()
    }

    companion object {
        const val WELCOME_TITLE = "欢迎使用 MyNote"

        /** 与 README 功能列表同口径；已发放的欢迎笔记不回写（改版后仅新用户可见新版）。 */
        val WELCOME_CONTENT = """
            这是 MyNote 为你准备的欢迎笔记，带你快速了解主要功能（阅读后可以随时删除）。

            【笔记】
            纯文本记录，支持标题、置顶、全文搜索和多选批量操作；删除的笔记进入回收站，到期自动清理前都可以恢复（保留期可在「设置 → 通用」调整）。

            【图文混排】
            在编辑页插入图片，正文中图片与文字交错显示；图片会自动压缩后保存在应用私有目录，不占用系统相册。

            【分类】
            单维度分类管理；主页的「未分类」入口可以集中整理没有归类的笔记。

            【历史】
            每次保存自动留存版本（最多 50 条），可查看字段与行级差异，并一键恢复旧版。

            【主题】
            浅色 / 深色 / 跟随系统，8 档主题色；Android 12+ 支持动态取色。

            【备份与导出】
            支持 zip 全量备份与导入；单条笔记可导出为 txt 或图片（长笔记自动分页）。

            【AI 助手】
            编辑页顶部的 AI 图标可唤起助手：支持 DeepSeek、硅基流动等大模型服务商（也可自定义兼容 OpenAI 协议的接口地址），需要先到「设置 → AI 助手」选择服务商并填写你自己的 API Key。会话按笔记留档，回答可以插入正文、替换选中、复制或存为新笔记。

            【隐私】
            应用不需要存储权限：插图、备份与导出都通过系统文件选择器完成；AI 内容只发送给你配置的 DeepSeek 账号。
        """.trimIndent()
    }
}
