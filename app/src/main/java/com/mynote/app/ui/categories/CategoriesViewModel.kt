package com.mynote.app.ui.categories

import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mynote.app.data.db.CategoryEntity
import com.mynote.app.data.repository.NoteRepository
import com.mynote.app.ui.theme.NoteColors
import com.mynote.app.ui.theme.PaperPalette
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CategoriesViewModel(private val repository: NoteRepository) : ViewModel() {

    val categories: StateFlow<List<CategoryEntity>> = repository.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 新建分类：使用对话框选定的颜色创建（不再随机取色）。 */
    fun add(name: String, color: Int, onDone: () -> Unit) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.addCategory(name.trim(), color)
            onDone()
        }
    }

    /** 行内点击色点：循环切到色板下一个颜色并立即持久化。 */
    fun cycleColor(category: CategoryEntity) {
        viewModelScope.launch {
            repository.updateCategoryColor(category, nextColor(category.color))
        }
    }

    /**
     * 「最少使用色」：在既有分类中出现次数最少的色板颜色（并列取色板顺序第一个）；
     * 全未使用时取色板第一个。纯函数，可单测。
     */
    fun leastUsedColor(categories: List<CategoryEntity>): Int {
        val counts = IntArray(PALETTE_ARGB.size)
        categories.forEach { cat ->
            val index = PALETTE_ARGB.indexOf(cat.color)
            if (index >= 0) counts[index]++
        }
        var best = 0
        for (i in 1 until PALETTE_ARGB.size) {
            if (counts[i] < counts[best]) best = i
        }
        return PALETTE_ARGB[best]
    }

    /** 行内换色的下一个颜色：先按最近邻把当前色映射到色板，再循环到下一色。 */
    fun nextColor(currentArgb: Int): Int {
        val shown = PaperPalette.nearest(currentArgb).toArgb()
        val index = PALETTE_ARGB.indexOf(shown)
        return PALETTE_ARGB[(index + 1) % PALETTE_ARGB.size]
    }

    fun rename(category: CategoryEntity, newName: String, onDone: (Boolean) -> Unit) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            onDone(repository.renameCategory(category, newName))
        }
    }

    fun delete(category: CategoryEntity) {
        viewModelScope.launch { repository.deleteCategory(category) }
    }

    companion object {
        /** 色板 ARGB 列表，供最少使用色与换色计算复用。 */
        private val PALETTE_ARGB: List<Int> = NoteColors.map { it.toArgb() }

        fun factory(repository: NoteRepository): ViewModelProvider.Factory =
            viewModelFactory { initializer { CategoriesViewModel(repository) } }
    }
}