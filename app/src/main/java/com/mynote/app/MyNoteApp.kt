package com.mynote.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import com.mynote.app.di.AppContainer
import kotlinx.coroutines.launch

class MyNoteApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 新用户首次启动生成欢迎笔记；失败静默，不阻塞启动
        container.applicationScope.launch {
            runCatching { container.welcomeNoteSeeder.seedIfNeeded() }
        }
    }

    /**
     * 显式约束图片内存缓存（约堆内存 10%，≈16-25MB），贴合「内存占用小」硬目标；
     * 磁盘缓存与默认策略保持一致，不新增配置。
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(percent = 0.10)
                .build()
        }
        .build()
}
