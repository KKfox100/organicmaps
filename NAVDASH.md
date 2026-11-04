# NavDash fork（魔改说明）

这个仓库是 [Organic Maps](https://github.com/organicmaps/organicmaps) 的 fork，
用于配套自研的**骑行导航仪表（ESP32-S3 + 手机 App）**。

上游代码保持不动，只加了「把导航数据与语音文本推给外接仪表」这一件事。

- 配套 App 仓库：`com.rider.navdash`（骑行助手）
- 本 fork 分支：`master`
- 基线：上游 master（2026-11-04 同步），版本号 `2026.11.04-1`

---

## 一、做了什么

两条**显式广播**（`setPackage("com.rider.navdash")`），由配套 App 接住后走它自己的 BLE 链路：

| 广播 Action | 内容 | 发出位置 |
|---|---|---|
| `com.rider.navdash.OM_NAV` | 转向 / 距离 / 剩余 / ETA / 路名 / 限速 | `NavigationService.onLocationUpdated`（约 1Hz） |
| `com.rider.navdash.OM_SAY` | 要念的那句话原文（中文整句） | `TtsPlayer.speak()` / `playTurnNotifications()` |

### 改动文件（共 6 个）

| 文件 | 说明 |
|---|---|
| `android/libs/routing/.../NavDashBroadcast.java` | 新增。把 `RoutingInfo` 转成广播 |
| `android/sdk/.../sound/NavDashSay.java` | 新增。把播报文本转成广播 |
| `android/sdk/.../sound/TtsPlayer.java` | 在 `speak()` / `playTurnNotifications()` **最开头**转发文本 |
| `android/libs/routing/.../NavigationService.java` | 开始导航时推一句「开始导航」 |
| `android/app/src/main/AndroidManifest.xml` | `<queries>` 声明 `com.rider.navdash` |
| `android/app/src/main/res/xml/prefs_voice_instructions.xml` | 新增「静音手机语音」开关 |

### 为什么不在 OM 里直接连 BLE

在 OM 里实现 GATT 客户端要几百行（扫描、连接、MTU、分片写、重连、Android 12+ 蓝牙运行时权限），
而这些在配套 App 里已经跑通并踩完了坑。OM 这边只加一个文件 + 一行调用，改动面最小，上游升级好合并。

---

## 二、两个真实踩坑（都是「没声音」）

### 坑 1：转发点被 early-return 挡住

```java
public void speak(String text) {
  if (!isReady()) return;                        // ← 国产 TTS 上常为 false
  ...
}
private boolean speakFirstString(String text) {
  if (!Config.TTS.isEnabled()) return false;      // ← 还有一层
  ... → NavDashSay.push(...)                      // ← 永远走不到
}
```

小米 `com.xiaomi.mibrain.speech` 上 `isReady()` 经常是 false
（日志里 `getUsableLanguages()` 对一大片语言全部返回不支持）。

**所以转发必须放在 `isReady()` 之前**：车上设备有自己的 TTS，OM 的引擎能不能用与它无关。

### 坑 2：Android 11+ 包可见性（真正致命）

```java
// NavDashSay.push() 里原本有这行「装没装」预检
context.getPackageManager().getPackageInfo("com.rider.navdash", 0);
catch (PackageManager.NameNotFoundException e) { return false; }   // ← 广播压根没发
```

`<queries>` 没有声明该包 → `getPackageInfo` 必抛异常 → **在 `sendBroadcast()` 之前就返回了**。

**修法**：`<queries>` 里加 `<package android:name="com.rider.navdash" />`，
并去掉这个预检（广播发给不存在的包是安全的空操作）。

### 排查陷阱

`intent.getPackage()` 是 `setPackage()` 设的**目标包名**，永远是 `com.rider.navdash`，
不是发送者。据此判断「是不是 OM 发的」会误判（本项目误判过两次）。

---

## 三、编译（Windows）

```bash
git clone https://github.com/KKfox100/organicmaps.git
cd organicmaps/android

export JAVA_HOME="D:/jdk/jdk-17.0.20.1+1"
export PATH="/c/Users/jack/AppData/Local/Programs/Python/Python312:$JAVA_HOME/bin:$PATH"

./gradlew assembleWebDebug
# 产物：app/build/outputs/apk/web/debug/OrganicMaps-<versionCode>-web-debug.apk
# 包名：app.organicmaps.web.debug
```

### Windows 上必须注意的两点

**1. CMake 找不到 Python3**（`Cannot use the interpreter "C:/Windows/System32/python"`）
→ 把真 Python 放到 PATH 最前，并清掉 CMake 缓存：`rm -rf android/sdk/.cxx android/app/.cxx`

**2. 符号链接被物化**（每次 `clone` / `checkout` / `rebase` 都会复发）
本仓库有 150 个 `mode=120000` 的符号链接，Windows 会把它们检出成「内容为路径的文本文件」。后果：

- `values-zh-rHK/strings.xml:1:1: Error: 前面有非法内容`（aapt2 报错）
- 启动即崩 `E OMcore: vulkan_shaders/reflection.json FileAbsentException`

修法：跑配套仓库里的还原脚本

```bash
python "C:/Users/jack/WorkBuddy AI/导航者/tools/restore-symlinks.py" E:/om-src
```

---

## 四、验证

装好配套 App 后：

```bash
adb logcat -s NavDash | grep -E "OM 语音|语音已下发"
```

在 OM 里按「设置 → 语音指令 → 测试语音指令」，期望：

```
✓ 收到 OM 语音 #1：感谢您使用我们社区构建的地图！
→ 语音：…
  下发 36288 字节
✓ 语音已下发
```

设备回包 `audio:recv=N/N` 表示字节数完整，`turn_icon` 会跟着转向变化。

**注意**：`navdash_mute_local` 为 `true` 时手机不播、只推仪表；
开关就在「设置 → 语音指令 → 静音手机语音（只由车上的喇叭播）」。

---

## 五、上游同步

```bash
git remote add upstream https://github.com/organicmaps/organicmaps.git
git fetch upstream
git rebase upstream/master        # 冲突点集中在 TtsPlayer / NavigationService
```

rebase 之后**务必重跑符号链接还原脚本**，否则编译会挂在资源打包。

---

## 许可

与上游一致，见 [LICENSE](LICENSE)。本 fork 的改动同样以 Apache-2.0 发布。
