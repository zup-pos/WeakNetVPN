# WeakNetVPN — Android 9–15 兼容性适配清单

> 工程代号 WeakNetVPN · namespace/appId `com.weaktool.vpn`
> minSdk=28 (Android 9) / targetSdk=34 / compileSdk=35
> 核心目标：以非阻塞 Reactor 单线程事件循环重写 VPN 包转发核心，从根源消除
> QNET 旧实现中忙轮询 / 忙等待带来的高 CPU 占用。

---

## 1. 结论速览（为什么要这样写）

| 关注点 | 旧 QNET(逆向推断) | WeakNetVPN(本工程) | 收益 |
|---|---|---|---|
| tun 读取 | 若忙轮询则高 CPU 元凶 | 阻塞 `read()`：fd 无包时内核挂起，空闲 0 忙等 | CPU 空闲趋近 0 |
| 出站连接 | 每连接逻辑 / 简单 Selector | **单线程 Selector Reactor**，所有 TCP/UDP socket 注册到一个 Selector，OP_READ/OP_WRITE 事件驱动 | 不自旋、不每连接一线程 |
| 限速 | — | 令牌桶 + 定时器事件 | 不靠小粒度忙等睡循环 |
| 丢包/延迟 | — | 定时任务队列 | 事件驱动，非忙等 |

---

## 2. 版本区间与 SDK 配置
- minSdk **28**（Android 9）— 明确不支持 ≤ Android 8，省去老 API 兼容分支。
- targetSdk **34**（Android 14）— 命中前台服务类型、通知权限等新规。
- compileSdk **35**（Android 15 SDK）— 拿到最新 API 面，同时向后兼容到 28。

## 3. Android 14–15 重点坑与工程内对策

### 3.1 前台服务 + 可见通知（防被杀）★核心
- **坑**：Android 14（targetSdk 34+）要求每个前台服务必须声明 `foregroundServiceType`；`VpnService` 必须以前台服务方式运行并常驻一条**可见**通知，否则系统在后台限制/低内存时直接杀进程，VPN 隧道路由随之断开。
- **对策（已内置于工程）**：
  - `AndroidManifest.xml`：`<service android:name=".core.vpn.WeakNetVpnService" android:foregroundServiceType="vpn" android:permission="android.permission.BIND_VPN_SERVICE" android:exported="false">` + intent-filter `action=android.net.VpnService`。
  - 权限：`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_VPN`。
  - `NotificationHelper` 在 `startForeground` 前建立高优先级可见通知，带"停止"Action + `PendingIntent`（`FLAG_IMMUTABLE`），防被杀。

### 3.2 POST_NOTIFICATIONS 运行时权限（Android 13+ / targetSdk34）
- **坑**：targetSdk 34 下通知渠道/通知需先获 `POST_NOTIFICATIONS` 运行时权限，否则通知不显示 → 前台服务"可见"前提不成立。
- **对策**：Manifest 声明 `POST_NOTIFICATIONS`；`PermissionFlow` / `MainActivity` 启动时用 `registerForActivityResult(ActivityResultContracts.RequestPermission())` 请求；拒授则降级提示但保证前台服务类型仍走 `startForeground`。

### 3.3 悬浮窗权限管控（SYSTEM_ALERT_WINDOW）更严
- **坑**：Android 14+ 对 `SYSTEM_ALERT_WINDOW`（悬浮窗/覆盖层）授予路径收紧；非豁免 App 默认不允许覆盖。
- **对策**：工程范围(b) **不自己创建悬浮窗**（复用现有 QNET 悬浮窗），故无需在 VPN 进程内申请覆盖权限。若未来内建，用 `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` 引导到系统设置页手动授权（`PermissionFlow` 已含此入口，作为可复用能力）。

### 3.4 非阻塞 IO 取代忙轮询（最核心目标）
- **对策**：
  - tun fd：单线程 `IpPacketReader` 用阻塞 `FileInputStream.read()`。VpnService 的 tun 在无包时内核态阻塞，**不会**像 select 空转一样烧 CPU。
  - 出站：`VpnEngine.reactorLoop()` 单一 `Selector` + `selector.select(timeoutMs)` 阻塞式等待就绪事件，超时由定时器(`TimerSource.nextDelayMs()`)驱动唤醒，**两条路径都不空转**。
  - 不引入任何 `while(true){ Thread.sleep(小) }` 忙等。

### 3.5 通知 / PendingIntent 的可变性（Android 12+）
- **坑**：Android 12 起 `PendingIntent` 必须显式 `FLAG_IMMUTABLE` 或 `FLAG_MUTABLE`。
- **对策**：`NotificationHelper` 统一用 `FLAG_IMMUTABLE`（跨 targetSdk 安全）。

### 3.6 前台服务启动时机限制（Android 12+）
- **坑**：后台不能随意 `startForegroundService`。
- **对策**：VPN 由用户在前台通过 `MainActivity` 按钮启动（UI 触发），规避后台启动限制。

### 3.7 自适应图标（minSdk 28 ≥ 26）
- **对策**：已用 `mipmap-anydpi-v26` 自适应图标（background 纯色 + foreground vector），覆盖 API 26+，无需旧式 png。

---

## 4. Android 9–11 需留意的行为
- Android 9（API28）：无 `FOREGROUND_SERVICE_VPN` 类型概念，Manifest 中该属性在低版本被忽略，`startForeground` 老签名仍可用——已按 `Build.VERSION.SDK_INT` 分支兼容。
- 前台服务通知渠道需 `NotificationChannel`（API26+，minSdk28 满足），统一创建。

## 5. 仍待 AndroidStudio/真机验证项（aarch64 容器无法跑官方 x86_64 aapt2）
本清单基于源码级静态审查。完整 `assembleDebug` 出 APK 需在 Android Studio / x86_64 CI 上执行（AGP 会自动生成真实 `R`、链接资源、签名）。核心引擎（core/qos/config，13 文件）已在容器内用真实 kotlinc + android-35 平台 jar 编译验证 RC=0。

---

## 6. 后续弱网业务迭代 TODO（已用 TODO 标注于 TcpForwarder 等）
- TCP 乱序重组 / 重传去重 / 接收窗口缩放语义。
- QoS 参数（带宽/延迟/抖动/丢包）运行中动态下发，不重建隧道。
- NAT 会话表老化与回收、DNS 直通等。