# droid-fleet

> LAN-based Android device fleet management and remote control console  
> 基于局域网的 Android 多设备批量管理与远程控制控制台

`droid-fleet` 是一套面向自有或明确授权 Android 终端的局域网批量运维工具，包含 **Android 受控端** 与 **Windows 11 批量控制端** 两个核心组件。适用于手机、平板、工控屏、商用手持机（如 SUNMI 等）等设备的局域网远程响铃、闪光灯控制、弹窗通知、电量与硬件资产巡检等场景。

本项目强调 **局域网内、授权设备、运维用途**，不是公网云控平台，也不是绕过系统权限的隐蔽监控工具。请仅在你拥有或已获得明确授权的设备上使用。

---

## 目录

- [项目定位](#项目定位)
- [核心特性](#核心特性)
- [系统架构](#系统架构)
- [组件说明](#组件说明)
- [快速开始](#快速开始)
  - [Android 受控端](#android-受控端)
  - [Windows 11 控制端](#windows-11-控制端)
- [Android 本地编译说明](#android-本地编译说明)
- [使用流程](#使用流程)
- [HTTP API 协议](#http-api-协议)
- [配置文件](#配置文件)
- [安全与合规](#安全与合规)
- [常见问题](#常见问题)
- [已知限制](#已知限制)
- [贡献](#贡献)
- [License](#license)

---

## 项目定位

`droid-fleet` 解决的是局域网内多台 Android 终端的批量运维问题，例如：

- 多台收银机、手持机、工控屏的在线状态巡检；
- 批量响铃寻找设备；
- 批量控制闪光灯用于警示或照明；
- 批量下发弹窗通知；
- 查看设备电量、充电状态、IP、MAC、序列号和 Android 版本等资产信息。

项目由两部分组成：

1. **Android 受控端**：前台常驻服务，监听局域网 HTTP 请求，执行响铃、闪光、通知等指令。
2. **Windows 11 控制端**：Python + tkinter 桌面程序，负责设备发现、PIN 鉴权、心跳监测和批量指令下发。

---

## 核心特性

- **局域网设备发现**：支持并发扫描局域网网段，快速发现已启动服务的设备。
- **PIN 码安全认证**：受控端生成 6 位随机 PIN，控制端首次接入需通过 PIN 获取 Token。
- **多设备批量控制**：
  - 批量响铃：10 秒、30 秒或立即停止；
  - 批量闪光灯：常亮、10 秒爆闪、关闭；
  - 批量弹窗通知：自定义标题与正文。
- **设备状态监测**：在线/离线状态、电量百分比、充电状态、设备型号等。
- **硬件资产回传**：IP、MAC、设备唯一序列号、Android 系统版本等。
- **Android 通知栏管理**：
  - 静默低打扰模式：通知渠道重要性设为 `IMPORTANCE_MIN`，状态栏无图标、下拉折叠、无声音震动；
  - 系统级关闭通知：提供跳转系统通知管理入口，关闭应用通知权限后，按项目设计前台服务仍可继续运行。
- **Windows 控制端窗口记忆**：自动保存窗口尺寸、位置和最大化状态，下次启动恢复。
- **标准库优先**：Windows 控制端使用 Python 标准库 `tkinter`，通常无需 `pip` 安装额外第三方依赖。

---

## 系统架构

```text
┌────────────────────────────────────────────────────────┐
│             Windows 11 批量管理终端 (Python)            │
│   • 窗口记忆 (尺寸/位置/最大化)                         │
│   • 局域网多设备并发扫描                                │
│   • 异步并发心跳监测                                    │
│   • 批量指令下发 (响铃/闪光/通知)                       │
└───────────────────────┬────────────────────────────────┘
                        │ HTTP REST API (默认端口: 8888)
                        │ 6 位动态 PIN 码安全认证
        ┌───────────────┼───────────────┐
        ▼                               ▼
┌──────────────────┐            ┌──────────────────┐
│ Android 受控端 A  │            │ Android 受控端 B  │
│ (前台常驻服务)    │            │ (前台常驻服务)    │
│ • 静默/关闭通知栏 │            │ • 静默/关闭通知栏 │
│ • 闪光灯/爆闪控制 │            │ • 闪光灯/爆闪控制 │
│ • 蜂鸣响铃/电量   │            │ • 蜂鸣响铃/电量   │
└──────────────────┘            └──────────────────┘
```

通信方式为 HTTP JSON，默认端口 `8888`。控制端首次连接受控端时使用 PIN 码鉴权并获取 Token，后续请求通过 `X-Auth-Token` 头携带 Token。

---

## 组件说明

### Android 受控端

- 以前台服务方式常驻运行，监听局域网 HTTP 请求；
- 启动后显示本机局域网 IPv4 地址和 6 位安全连接 PIN 码；
- 支持响铃、闪光灯/爆闪、弹窗通知、电量与设备信息回传；
- 提供通知栏常驻管理：
  - 静默模式：降低通知重要性，减少打扰；
  - 系统通知设置入口：可跳转到系统设置关闭该应用通知。

### Windows 11 控制端

- Python + `tkinter` 桌面应用；
- 支持手动添加设备和局域网快速扫描；
- 支持多选设备、全选、仅选在线设备；
- 实时显示在线状态、电量和充电状态；
- 批量下发响铃、闪光和弹窗通知指令；
- 自动记忆窗口尺寸、位置和最大化状态。

---

## 快速开始

### Android 受控端

1. 使用 Android Studio 打开 Android 受控端工程并编译安装。
2. 打开应用，点击 **【启动控制接收服务】**。
3. 界面会显示：
   - 本机局域网 IPv4 地址；
   - 6 位安全连接 PIN 码。
4. 在 **【通知栏常驻管理】** 中按需设置：
   - 勾选 **【静默模式】**：通知降级为最低重要性，状态栏无图标，下拉通知栏折叠；
   - 点击 **【系统通知设置】**：跳转系统通知管理，可关闭该应用通知权限。

> 注意：Android 8.0 及以上系统要求后台长时间运行的网络监听服务声明为前台服务。不同厂商 ROM 对关闭通知权限后的前台服务保活策略可能不同，请在实际设备上验证。

### Windows 11 控制端

#### 环境要求

- Windows 10 / 11；
- Python 3.x；
- 通常无需 `pip` 安装第三方依赖，控制端使用标准库 `tkinter`；
- 若你的 Python 发行版未包含 `tkinter`，请安装带 Tcl/Tk 支持的 Python。

#### 启动方式

方式一：双击运行：

```text
windows_controller/run_win11_controller.bat
```

方式二：在终端中进入控制端目录运行：

```bash
python win11_controller.py
```

#### 首次使用

1. 确保 Windows 控制端与 Android 受控端处于同一局域网；
2. 在 Android 受控端启动服务，记录 IP、端口和 PIN；
3. 在 Windows 控制端手动添加设备：
   - 输入设备 IP；
   - 端口默认 `8888`；
   - 输入 6 位 PIN；
   - 填写备注，如“1号收银机”；
   - 点击 **【绑定并添加设备】**；
4. 或点击 **【局域网快速扫描】**，并发探测局域网并自动加入已启动服务的设备；
5. 在设备列表中勾选需要控制的设备；
6. 使用右侧功能区执行：
   - 响铃 10 秒 / 30 秒 / 停止；
   - 闪光灯常亮 / 爆闪 / 关闭；
   - 输入标题和正文后群发弹窗通知。

---

## Android 本地编译说明

从云端导出工程时可能未附带敏感签名证书。本地 Android Studio 编译时如果遇到：

```text
找不到用于签署配置“debugConfig”的密钥库文件“...\debug.keystore”。
```

请修改 `app/build.gradle.kts`，找到 `buildTypes` 模块，将 `debug` 下的自定义签名配置注释掉：

```kotlin
buildTypes {
    release {
        isCrunchPngs = false
        isMinifyEnabled = false
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"
        )
        signingConfig = signingConfigs.getByName("release")
    }
    debug {
        // signingConfig = signingConfigs.getByName("debugConfig") // 注释掉此行
    }
}
```

点击 Android Studio 右上角 **立即同步** 后，即可使用 Android 官方通用 Debug 密钥打包运行。

---

## 使用流程

1. Android 受控端启动控制接收服务；
2. 记录受控端显示的局域网 IPv4 地址和 6 位 PIN；
3. Windows 控制端手动添加设备或使用局域网快速扫描；
4. 控制端通过 `/api/auth` 提交 PIN 获取 Token；
5. 控制端通过心跳接口监测设备在线状态、电量和充电状态；
6. 勾选设备后批量下发响铃、闪光或弹窗通知；
7. 控制端自动保存窗口布局和设备配置到 `remote_devices.json`。

---

## HTTP API 协议

控制端与受控端通过 HTTP JSON 通信，默认端口 `8888`。

| 接口路径 | 方法 | 认证方式 | 功能说明 |
|---|---|---|---|
| `/api/auth` | `POST` | Body: `{"pin": "123456"}` | 验证 PIN 码并获取授权 Token |
| `/api/ping` | `GET` | Header: `X-Auth-Token` | 心跳检测，返回电量、充电状态、型号等信息 |
| `/api/ring` | `POST` | Header: `X-Auth-Token` | 控制响铃，参数 `{"duration": 10}` |
| `/api/torch` | `POST` | Header: `X-Auth-Token` | 控制闪光灯，参数 `{"mode": "strobe" \| "on" \| "off"}` |
| `/api/notify` | `POST` | Header: `X-Auth-Token` | 触发全屏弹窗通知，参数 `{"title": "...", "message": "..."}` |

示例：

```http
POST /api/auth
Content-Type: application/json

{"pin": "123456"}
```

```http
GET /api/ping
X-Auth-Token: <token>
```

```http
POST /api/ring
X-Auth-Token: <token>
Content-Type: application/json

{"duration": 10}
```

```http
POST /api/torch
X-Auth-Token: <token>
Content-Type: application/json

{"mode": "strobe"}
```

```http
POST /api/notify
X-Auth-Token: <token>
Content-Type: application/json

{"title": "通知标题", "message": "通知正文"}
```

> 实际字段名和取值以当前 Android 受控端实现为准。若实现中使用中文模式值，请同步调整控制端请求体。

---

## 配置文件

Windows 控制端会在本地维护 `remote_devices.json`，用于保存：

- 已绑定设备列表；
- 设备 IP、端口、备注；
- 窗口宽度、高度；
- 窗口屏幕坐标；
- 是否最大化。

下次启动时，控制端会读取该文件并恢复窗口布局与设备配置。

---

## 安全与合规

- 本项目默认面向 **可信局域网**，HTTP 通信默认未启用 TLS。不要将受控端端口直接暴露到公网。
- 首次接入需使用 6 位 PIN 获取 Token，请妥善保管 PIN 和 Token。
- 建议在路由器或主机防火墙层面限制可访问受控端 `8888` 端口的设备范围。
- 仅可管理你拥有或已获得明确授权的设备。
- 禁止将本项目用于未授权控制、隐蔽监控、骚扰、刷量或其他违法违规用途。
- 生产环境建议增加：HTTPS、Token 过期与轮换、请求频率限制、操作审计日志、设备白名单等能力。

---

## 常见问题

### 1. 控制端扫描不到 Android 设备？

- 确认 Windows 控制端和 Android 受控端在同一局域网；
- 确认 Android 受控端已点击“启动控制接收服务”；
- 确认端口 `8888` 未被防火墙拦截；
- 尝试手动输入 IP 和 PIN 添加设备；
- 部分企业 Wi-Fi 或 AP 隔离会阻止设备互访。

### 2. 提示 `debug.keystore` 找不到？

参考 [Android 本地编译说明](#android-本地编译说明)，注释掉 `debug` 构建类型中的自定义签名配置。

### 3. Android 通知栏无法完全隐藏？

- 可先开启应用内“静默模式”；
- 再跳转系统通知设置关闭该应用通知权限；
- 不同厂商 ROM 对前台服务通知和保活策略不同，请在实际设备上验证。

### 4. Windows 控制端需要安装依赖吗？

控制端主要使用 Python 标准库 `tkinter`，通常无需 `pip` 安装额外第三方依赖。若 Python 未包含 Tk，请安装带 Tcl/Tk 支持的 Python 发行版。

### 5. PIN 或 Token 失效怎么办？

重新在 Android 受控端查看当前 PIN，并在控制端重新绑定设备获取 Token。

---

## 已知限制

- 当前默认使用局域网 HTTP 明文通信，适合可信网络，不建议公网暴露；
- 设备发现依赖局域网广播/扫描和防火墙放行；
- 不同 Android 厂商对前台服务、通知权限和后台保活策略存在差异；
- API 字段和模式值可能随 Android 端实现调整；
- 本项目不提供绕过系统权限或隐蔽驻留能力。

---

## 贡献

欢迎提交 Issue 和 Pull Request。建议贡献时：

1. 说明使用场景和复现步骤；
2. 涉及 API 变更时同步更新 README；
3. 不提交真实设备 PIN、Token、MAC、序列号等敏感信息；
4. 不加入未授权控制、隐蔽监控或绕过系统权限的功能。

---

**droid-fleet** — LAN-based Android device fleet management and remote control console.
