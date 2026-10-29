# 启动图标设计（adaptive icon + 手绘「记」）

日期：2026-10-29
状态：已实现（分支 launcher-icon-1029）

## 背景

桌面图标在移动端观感差。排查后确认这主要是工程结构缺失，不是单纯审美问题。

## 根因（实测证据）

| 证据 | 结论 |
|---|---|
| `AndroidManifest.xml:9` 原为 `android:icon="@drawable/ic_note"`，无 `roundIcon` | 直接拿 24dp 矢量当桌面图标 |
| `res/` 下 `mipmap*` 与 `*launcher*` 资源为 0 | 没有 adaptive icon |
| `drawable/ic_note.xml`：24×24 viewport，图形仅占 22×18，单色 `#FF3F51B5` | 图形偏小内缩，放大到桌面尺寸又小又空 |

四条因果：① API 26+ 无 adaptive icon，系统会把老式图标缩小并垫白/浅灰底板再套遮罩，形成"白边 + 缩水"；② 24dp 矢量被拉大到 108dp 量级，比例关系丢失；③ 单帧无分层，取不到 Material You 主题色、无视差；④ 满铺 Indigo 500 与 app 内纸面美学无关联。

## 方案

- **图形**：字标「记」，脱字体重绘（纯 path，零字体依赖）。字形经过 4 轮渲染比对收敛：V1 字体渲染 → V5/V6/V7 手绘 → V8 三选一收笔方案。
- **讠 收笔**：最终采用**点 + 竖**（V8 的 B 方案）。A（带提）的提与竖底边会围成一个向下的箭头，C（底部短横）会切出台阶，均被淘汰。
- **配色**：Indigo `#3F51B5` → Teal `#00897B` 线性渐变，直接复用 `ui/theme/Color.kt:32,34`，不引新色。
- **分层**：background（渐变）/ foreground（字形）/ monochrome（Android 13 主题图标）。

## 设计参数

- 画布 108×108，安全区圆心 (54,54) 半径 33（66dp 直径），字形全部落在内。
- 主笔画宽 8.2/108，点画宽 7.0，点用圆头，其余平头、圆角折。
- 光学居中：字形整体右移 1.2（字面框 x49..74 / y36..72）。
- 字形坐标（108 网格，绘制前整体 `translateX=1.2`）：
  - 点 `M31,33.8L34.6,38.4`（strokeWidth 7.0，round cap）
  - 竖 `M34,44.5V59.5`
  - 己·横折+中横 `M49,36H74V50H49`
  - 己·竖弯钩 `M49,50V72H74V62.5`

## 文件清单

| 文件 | 作用 |
|---|---|
| `res/drawable/ic_launcher_background.xml` | 108 画布渐变底（`aapt:attr` 内联 gradient） |
| `res/drawable/ic_launcher_foreground.xml` | 字形前景层，同时复用为 monochrome 层 |
| `res/drawable/ic_launcher_legacy.xml` | API 24–25 满铺渐变 + 字形放大 1.16 |
| `res/mipmap-anydpi-v26/ic_launcher.xml`、`ic_launcher_round.xml` | 自适应图标（含 monochrome） |
| `res/mipmap-anydpi/ic_launcher.xml`、`ic_launcher_round.xml` | 老式回退，`<inset>` 别名指向 legacy |
| `AndroidManifest.xml` | `icon` 改 `@mipmap/ic_launcher`，新增 `roundIcon` |

`drawable/ic_note.xml` 全仓仅被 manifest 引用一处，改造后成为无引用资源，保留未删（避免误伤潜在外部引用），如需清理可单独提交。

## 验收

1. `./gradlew :app:assembleDebug` 通过（aapt2 校验 vector 与 gradient 语法）。
2. 圆 / 方圆 / 水滴三种 launcher 遮罩下字形均不被裁切。
3. 48 / 40 / 32 / 24px 尺度下仍可辨认为「记」。
4. 深色壁纸、浅色壁纸均可辨识；monochrome 层在 Android 13 主题图标下可读。
5. API 24–25 回退到 legacy 矢量，不出现资源缺失。

## 风险

- VectorDrawable 作 launcher 图标在部分老 launcher（API 24–25）可能不渲染；当前主图标本来就是矢量，风险与改造前同级。若真机复现，补 5 档 PNG 到 `mipmap-*` 即可，无需改结构。
- 矢量渐变依赖 API 24+ 的框架支持，与 minSdk 24 一致。
- launcher 有图标缓存，换图标后需卸载重装或重启 launcher 才能看到。

## 预览资产

`docs/icon-preview/`（`gen.mjs`、`v5–v8.mjs` 及同名 `.html`/`.png`）为设计期比对产物，不参与打包。定稿后可删除。
