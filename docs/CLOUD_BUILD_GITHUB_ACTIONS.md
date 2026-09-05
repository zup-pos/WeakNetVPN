# WeakNetVPN — 云端构建（GitHub Actions，无需本地电脑）

> 本方案用 **GitHub Actions 免费 x86_64 托管 Runner** 实现「无本地电脑」的云端
> Android Gradle 构建：把工程推到 GitHub，云端自动跑 `./gradlew assembleDebug`，
> 完成后在 Actions 页面 Artifacts 下载 debug APK。

## 为什么走这条路
- 本机/本地容器为 **aarch64**，跑不了 Google 官方 **x86_64 版 aapt2**，无法在本地产出 APK。
- GitHub Actions 托管 Runner 是 **x86_64 Ubuntu**，官方 aapt2/build-tools 可正常运行，
  因此能完成完整 AGP 资源链接并产出 APK。
- 仓库为 public 时 **免费**，无需本地 Android Studio。

## 已就绪的云端构建四件套（本目录内）
| 文件 | 作用 |
|---|---|
| `gradlew` / `gradlew.bat` / `gradle/wrapper/*` | Gradle Wrapper，云端 `./gradlew` 自举（distributionUrl → Gradle 8.9，对齐 AGP 8.7.3） |
| `.github/workflows/build.yml` | Actions 工作流：checkout → JDK17 → setup-gradle → setup-android(SDK) → `./gradlew assembleDebug` → upload artifact |
| `.gitignore` | 忽略 build 产物 / APK / 本地配置 |
| `/root/WeakNetVPN.zip` | 整工程打包（约 85KB），可直接上传或解压后推送 |

## 操作步骤（用户在 GitHub 页面完成，约 3 分钟）
1. **建仓库**：GitHub → New repository（建议 public，可私有但免费额度可能受限）。
2. **上传工程**：上传 `/root/WeakNetVPN.zip`（或本地 `git push` 解压后的目录），
   注意**保留 `.github/workflows/build.yml` 与 `gradle/` wrapper 目录**（隐藏文件需在页面上传时勾选显示）。
3. **触发构建**：
   - push 到任意分支会自动触发；或
   - 仓库 **Actions** 页 → 选 `Build` 工作流 → **Run workflow**（`workflow_dispatch` 手动触发）。
4. **等待构建**：点进最新一次 run，等待 `Build Debug APK` 步骤绿色通过。
5. **下载 APK**：构建成功后页面底部 **Artifacts** → 下载 `weaknetvpn-debug-apk`，
   解压即得 `app-debug.apk`（debug 签名可直接安装）。
6. **真机安装验证**：覆盖 Android 9–15，重点验证前台服务/通知/权限行为。

## 若构建失败排查
- 红色步骤若为 `setup-android` 之后 — 查看日志确认 SDK platform 35 + build-tools 已装（workflow 已带 setup-android@v3，通常可自动装）。
- 若为 `assembleDebug` 编译错误 — 展开步骤日志看 `--stacktrace` 输出，把报错贴回给 Operit 定位。
- 若 Artifacts 提示无文件 — 确认 `app/build/outputs/apk/debug/*.apk` 产物路径正确（workflow 已设 `if-no-files-found: error` 会立即报错便于定位）。

## 后续迭代入口
- 弱网业务逻辑待办：`TcpForwarder.kt` 内 TODO（TCP 乱序重组 / 重传去重 / 窗口缩放、QoS 动态下发、NAT 会话老化）。
- 每次改完 `git push` 即自动重建并出新 APK —— 形成「改代码 → 云端构建 → 下载验证」闭环。
