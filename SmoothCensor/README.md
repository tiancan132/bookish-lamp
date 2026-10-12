# SmoothCensor —— 最小可跑版本

一个安卓 App：**录屏 → AI 检测身体部位 → 在屏幕上盖黑块**。

这是针对原版软件"一闪一闪"问题的**改进版骨架**。

---

## 先说清楚：这个版本能干什么

✅ **能做的：**
- 录屏抓帧
- 用 AI 检测 18 类身体部位
- 在屏幕上画黑块遮挡
- **时序平滑**（这是重点，见下文）

❌ **还没做的：**
- 界面美化
- 预设方案
- 滚动检测优化
- 性能调优

**这是骨架，不是成品。** 目的是让你**先跑起来，看到效果**。

---

## 它比原版好在哪

原版的做法：

```
检测到 → 画框
检测不到 → 立刻删      ← 闪的根源
```

**本版本的做法**（在 `TemporalSmoother.kt`）：

| 机制 | 作用 |
|---|---|
| **多帧投票** | 连续 2 帧都检测到才显示 → 消除偶发误检 |
| **消失缓冲** | 检测不到时先保留 6 帧再删 → 消除偶发漏检 |
| **指数平滑** | 框的位置渐进移动，不瞬移 → 视觉顺滑 |

**代价**：响应慢 2~3 帧（约 0.2~0.3 秒）。

**收益**：基本不闪。

这三个参数都在 `TemporalSmoother` 的构造函数里，**你可以自己调**：

```kotlin
TemporalSmoother(
    confirmFrames = 2,     // 调大 → 更稳，但更迟钝
    retainFrames = 6,      // 调大 → 丢失后保持更久
    positionAlpha = 0.45f, // 调小 → 更平滑，但更拖沓
)
```

---

## 文件结构

```
SmoothCensor/
├── settings.gradle.kts
├── build.gradle.kts
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/example/smoothcensor/
        │   ├── MainActivity.kt        ← 界面 + 权限申请
        │   ├── CaptureService.kt      ← 核心：录屏 + 调度
        │   ├── DetectorEngine.kt      ← AI 推理
        │   ├── TemporalSmoother.kt    ← 🔥 平滑（解决闪烁）
        │   ├── OverlayView.kt         ← 画黑块
        │   └── Detection.kt           ← 数据结构
        └── res/
            ├── layout/activity_main.xml
            └── values/themes.xml
```

---

## 怎么编译

### 第 1 步：装 Android Studio

下载：https://developer.android.com/studio

装好后它会自带 SDK，**不用单独配**。

### 第 2 步：放模型文件

**这一步不能跳过**，否则跑起来检测不到东西。

本版本需要 `320n_fp16.onnx` 或 `320n.onnx`，放到：

```
SmoothCensor/app/src/main/assets/
```

**模型从哪来？** 三个途径：

1. **从你已有的 APK 里提取**（那个 Beta Blocker 就有）：
   ```bash
   # 解压 APK，取出 assets/320n_fp16.onnx
   ```
2. **用 NudeNet 官方发布的模型**（开源项目）
3. **自己训练一个**（最干净，但要数据和时间）

> ⚠️ **注意版权**：那个模型是 AGPL-3.0 协议。**自己用没问题，但如果你要发布软件，必须开源你的代码**，或者换一个模型。

### 第 3 步：编译

用 Android Studio 打开 `SmoothCensor` 文件夹，等它同步完，点运行按钮。

**或者命令行：**
```bash
cd SmoothCensor
./gradlew assembleDebug
```

生成的 APK 在 `app/build/outputs/apk/debug/`。

### 第 4 步：装到手机

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## 怎么用

1. 打开 App
2. 点 **① 开启悬浮窗权限** → 去系统设置里允许
3. 点 **② 开始检测** → 允许录屏
4. **按 Home 键**，切到别的应用
5. 黑块会自动盖住检测到的部位

---

## 技术要点

### 数据流

```
MediaProjection 录屏
      ↓
ImageReader 拿帧（RGBA）
      ↓
Channel 队列（容量 2，满了丢帧，绝不卡）
      ↓
独立线程 ONNX 推理
      ↓
TemporalSmoother 平滑
      ↓
OverlayView 画框
```

### 为什么用队列丢帧

录屏帧率（60fps）远高于检测速度（约 10fps）。

**如果不丢帧**，队列会无限堆积 → 内存爆 + 延迟越来越大（框越来越滞后）。

**丢帧策略**：队列满了就丢**最新**的，保证处理的永远是**接近实时**的画面。

### 三级降级

和原版一样，模型加载按顺序尝试：

```
NNAPI（手机 AI 芯片，最快）
   ↓ 失败
XNNPACK（CPU 优化库）
   ↓ 失败
CPU（纯软件，最慢但一定能跑）
```

**保证低端手机也能跑起来。**

### 遮挡层为什么能穿透点击

```kotlin
FLAG_NOT_TOUCHABLE   // 不吃触摸事件
FLAG_NOT_FOCUSABLE   // 不抢焦点
```

所以黑块盖着，**你照样能点下面的东西**。

---

## 已知问题

| 问题 | 原因 | 怎么改 |
|---|---|---|
| 首次检测延迟 | 模型加载要几秒 | 加个加载提示 |
| 快速滚动时可能有残留 | 没做滚动检测 | 参考原版的 `detectScrollState` |
| 横屏坐标可能偏 | 只按竖屏算的 | 处理屏幕旋转 |
| 耗电 | 持续推理 | 降帧率、降分辨率 |

---

## 下一步可以做什么

按**投入产出比**排序：

1. **调参数** —— 先跑起来，看看哪个参数最影响效果
2. **加滚动检测** —— 参考原版思路，滚动时重置跟踪
3. **卡尔曼滤波** —— 比指数平滑更聪明，能预测运动
4. **渐变过渡** —— 框出现/消失时加透明度动画
5. **换更大模型** —— 精度更高，但更慢

---

## 法律提醒

这个项目**只用于你自己拥有或有权检查的设备**。

- ✅ 自己的手机、自己做测试
- ✅ 学习安卓开发、AI 推理、图形处理
- ❌ 不要用来规避他人的内容审核
- ❌ 不要发布侵犯他人权益的软件

**模型有 AGPL 协议约束，商用前务必搞清楚。**

---

## 最后

这是**教学用骨架**，代码写得尽量直白，注释都是大白话。

**遇到问题先看这里，再改代码。**
