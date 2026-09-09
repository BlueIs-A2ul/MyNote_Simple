# MyNote 构建问题记录与构建指南

> 本文档记录 2026-09-09 实际构建过程中遇到的三个问题、根因与修复方式，并给出在其它机器上构建的注意事项。计划文档中的对应修订记录见 `docs/superpowers/plans/2026-08-13-mynote.md`（「修订记录」一节）。

## 1. 环境前提

- **JDK 17**：实测 Temurin 17.0.19，`JAVA_HOME` 已配置（AGP 8.5 要求 JDK 17）。
- **Android SDK**：`ANDROID_HOME=C:\Android\Sdk`，含 platform 34/35 与 build-tools 34/35。
- **Gradle**：项目自带 Wrapper（`gradlew.bat`，Gradle 8.7），无需本机安装。
- **网络**：首次构建需联网下载 AGP/Kotlin/Compose 等依赖。

本机环境特殊性：Windows + 全局 Gradle 初始化脚本 `C:\Users\<user>\.gradle\init.d\mirror.gradle`（阿里云 Maven 镜像），且 **dl.google.com 的 TLS 连接不稳定**（时好时坏）。`settings.gradle.kts` 已按此环境调优。

---

## 2. 遇到的问题与修复

### 问题 1：`FAIL_ON_PROJECT_REPOS` 与全局镜像 init 脚本冲突（首次构建即失败）

**现象**：执行 `.\gradlew :app:assembleDebug` 直接失败：

```
FAILURE: Build failed with an exception.
* Where: Initialization script 'C:\Users\17383\.gradle\init.d\mirror.gradle' line: 32
* What went wrong:
  Build was configured to prefer settings repositories over project repositories
  but repository 'maven' was added by initialization script '...\mirror.gradle'
```

**原因**：本机全局镜像脚本通过 `gradle.beforeProject` 钩子向**每个 project** 注入阿里云镜像仓库；而计划 Task 1 配置的 `RepositoriesMode.FAIL_ON_PROJECT_REPOS` 禁止 settings 之外声明的任何仓库，二者直接冲突。

**修复**：`settings.gradle.kts` 改为 `RepositoriesMode.PREFER_SETTINGS`（settings 声明的仓库优先，project/init 脚本注入的仓库作为后备，不再报错）。这是兼容全局 init 脚本的标准模式，对没有该脚本的机器也无副作用。

### 问题 2：DAO 测试代码编译失败（计划原代码缺陷，非环境问题）

**现象**：`NoteDaoTest` 编译报错 `Unresolved reference 'size'` / `No 'get' operator method providing array access`。

**原因**：`dao.search("苹果")` 返回 `Flow<List<NoteEntity>>`，测试未先 `.first()` 就做 `hits.size` / `hits[0]` 断言。

**修复**：`val hits = dao.search("苹果").first()`（测试与计划文档同步修正）。

### 问题 3：dl.google.com TLS 握手被远端中断，release 构建的 lint 依赖下载失败

**现象**：`.\gradlew :app:assembleRelease` 在 `lintVitalAnalyzeRelease` 失败（多次重试均失败，每次约 4–14 分钟）：

```
Could not GET 'https://dl.google.com/dl/android/maven2/com/android/tools/external/
com-intellij/kotlin-compiler/31.5.2/kotlin-compiler-31.5.2.jar'
Caused by: javax.net.ssl.SSLHandshakeException: Remote host terminated the handshake
Caused by: java.io.EOFException: SSL peer shut down incorrectly
```

**原因**：
1. `lintVitalAnalyzeRelease` 需要额外下载 lint 工具链 jar（`com.android.tools.external.com-intellij:kotlin-compiler:31.5.2`，约 55.8MB），只在 release 构建时发生（debug 构建首次依赖下载恰好成功，掩盖了该问题）。
2. 本网络环境下 dl.google.com 的 TLS 连接被间歇性掐断。
3. 关键机制：**Gradle 对仓库的传输层错误（TLS/网络异常）不会像 404「找不到」那样跳过并尝试下一个仓库，而是直接中止解析** —— 所以即使 settings 里排在后面的阿里云镜像有该构件，也不会被尝试。

**排查**：HEAD 请求确认镜像有该构件：
`https://maven.aliyun.com/repository/google/com/android/tools/external/com-intellij/kotlin-compiler/31.5.2/kotlin-compiler-31.5.2.jar` → HTTP 200。

**修复**：`settings.gradle.kts` 的 `dependencyResolutionManagement.repositories` 把阿里云镜像**置前**，google()/mavenCentral() 保留为后备：

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google()
        mavenCentral()
    }
}
```

修复后：`lintVitalRelease` 1m32s 通过，`assembleRelease` 37s 通过（含 R8 混淆 + 资源压缩）。

---

## 3. 构建命令速查

| 目的 | 命令 | 预期 |
|---|---|---|
| debug APK | `.\gradlew :app:assembleDebug` | `app\build\outputs\apk\debug\app-debug.apk`（约 17.8MB） |
| 全部单元测试 | `.\gradlew :app:testDebugUnitTest` | 22 个测试全部 PASS |
| 单个测试类 | `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.data.db.NoteDaoTest"` | 该类 PASS |
| release APK | `.\gradlew :app:assembleRelease` | `app\build\outputs\apk\release\app-release-unsigned.apk`（约 1.51MB，R8 + 资源压缩，未签名） |

**耗时说明**：

- 首次构建需下载全部依赖（本机约 8 分钟），之后增量构建为秒级。
- 首次运行单元测试时 Robolectric 需额外下载 android-all 官方 jar（约 100–200MB，一次性）。
- release 首次构建包含 `lintVitalRelease`（下载 lint 工具 jar）+ R8 混淆，网络正常时约 1–2 分钟。
- git 提交时 `LF will be replaced by CRLF` 警告为 Windows 正常现象，无害。

---

## 4. 在其它机器上构建

- **直连网络正常（google/Maven Central 可达）的机器**：现有配置可直接构建（阿里云镜像同样公网可达）；若想提速，可把 `settings.gradle.kts` 中三行 `maven { url = uri("https://maven.aliyun.com/...") }` 删除，或移到 `google()`/`mavenCentral()` 之后。
- **受限网络 / dl.google.com 不稳的机器**：保持现状即可 —— 镜像置前保证解析不因传输层错误而中断；这也是问题 3 的通用规避方式。
- **环境变量**：`JAVA_HOME`（JDK 17）、`ANDROID_HOME`（含 platform 34 与 build-tools 34；`local.properties` 亦可替代 `ANDROID_HOME`）。
- 若目标机器存在其它全局 `init.d` 脚本注入 project 级仓库：`PREFER_SETTINGS` 模式已兼容，无需处理。

---

## 5. 已知遗留事项

- **签名**：release 产物为未签名 APK（`app-release-unsigned.apk`），上架/分发前需在 `app/build.gradle.kts` 配置 `signingConfigs`。
- **ABI 拆分**：计划中的可选步骤未启用（应用含少量 Compose 原生库，本地分发如需进一步减小体积可开启）。
- **性能/内存校验**（计划 Task 11 Step 4）：需真机或模拟器 + Android Studio Profiler 人工完成。
