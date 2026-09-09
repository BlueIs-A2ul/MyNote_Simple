pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    // 修订记录第 1、12 条：本机全局镜像 init 脚本会注入 project 级仓库，
    // FAIL_ON_PROJECT_REPOS 会与之冲突，故采用 PREFER_SETTINGS；
    // 且 dl.google.com 的 TLS 连接不稳定，镜像仓库置前以保证解析不中断。
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google()
        mavenCentral()
    }
}
rootProject.name = "MyNote"
include(":app")
