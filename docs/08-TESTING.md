# 08 · 测试与验收标准

> 按新方向（原版 Vulkan 后端 + OF/Iris 格式引擎）重写。
> **测试不过不算完成。** 阶段划分与 `04-SPEC.md` §6 一致。
> 面向 agent 的可执行流程见 `01-DEV-LOOP.md`。

---

## 1. 验收总表

| 阶段 | 验收项 | 通过标准 | 证据 |
|---|---|---|---|
| **P0** 骨架 | 构建 | `gradlew build` 退出码 0，jar 含 `.class` | 命令输出 |
| **P0** 骨架 | 可启动 | 启动到主菜单，无 `Mixin apply failed` | 游戏日志 |
| **P0** 骨架 | 后端判定 | 日志显示当前为 Vulkan 后端 | 日志 |
| **P0** 骨架 | **首个可见产物** | 屏幕上出现自定义全屏 pass 画出的图案 | 截图 |
| **P1** 管线 | 参数可见变化 | 改 uniform 数值，画面实时变化 | 录屏/连续截图 |
| **P1** 管线 | 管线计数 | 注册数 == 编译成功数（不得静默少） | 诊断日志 |
| **P2** 解析 | 包识别 | 选中一个真实 OF 包后能被列出并选中 | 截图 |
| **P2** 解析 | `#include` | 含 `#include` 的 program 能编译通过 | 日志 |
| **P2** 解析 | composite 生效 | 选择开关后画面有对应变化 | 对比截图 |
| **P3** 完整链 | 影子 | `shadow` pass 输出非空、方向正确 | 调试视图 |
| **P3** 完整链 | gbuffers | 地形/实体走自定义目标而非原版目标 | 调试视图 |
| **P3** 完整链 | deferred | 延迟链各步输入输出正确 | 调试视图 |
| **P4** 兼容 | 主流包 | BSL / Complementary / Sildur's 主要效果可用 | 与 Iris 对比截图 |
| **P4** 兼容 | 切包无残留 | 切包/切 none 无顶点格式错乱 | 4 张对比截图 |
| **P4** 兼容 | 选项 GUI | pack 声明的选项能渲染并能改 | 截图 |

---

## 2. P0：骨架与首个可见产物

```bash
cd D:/Code/Minecraft/Shader-Core-Vulkan
./gradlew build
echo "exit=$?"

JAR=$(ls build/libs/*.jar | head -1)
unzip -l "$JAR" | grep -c '\.class'
unzip -l "$JAR"
```

**通过标准**
- `gradlew build` 退出码 0
- jar 内 `.class` 数量 > 0
- jar 内**不含** `net/caffeinemc/`、`net/minecraft/`、`dev/vitrail/`、`com/mojang/`
- `./gradlew runClient` 能进主菜单，日志无 `Mixin apply failed`

**关键断言（不得靠肉眼猜）**

```java
// 启动时打这条，用户可直接核对
LOGGER.info("vkdisp: backend={}, device={}", backendKind(), deviceInfo().name());
// 期望：backend=Vulkan（绝不能是 OpenGL）
// 值域注意：原版 backendName() 原值是 "Vulkan" / "OpenGL"（首字母大写，不是全大写），代码比较必须写 "Vulkan"
```

> ⚠️ **静默失败第一定律**：mixin 没生效时游戏**不会报错**，只会「什么都没发生」。
> 每个注入点必须自己打一条日志证明它被调用了。详见 `01-DEV-LOOP.md` §4。

---

## 3. P1：管线与 uniform

**测试步骤**
1. 起客户端，进入世界
2. 打开诊断日志
3. 修改全屏 pass 的一个 uniform 值

**通过标准**
```
[✓] 画面随参数实时变化（不是只在启动时生效）
[✓] 关闭 pass 后画面回到原状
[✓] 日志中的「注册管线数」== 「编译成功数」
[✓] 无 ERROR / 无 Vulkan validation error
```

**管线计数断言（必须实现）**

```java
if (registered != compiled) {
    LOGGER.error("vkdisp: pipeline count mismatch: registered={}, compiled={}", registered, compiled);
}
// 允许编译失败，但不允许「失败得无声无息」
```

---

## 4. P2：格式解析阶段

**测试 pack**

| Pack | 用于验证什么 |
|---|---|
| 自制的**最小 pack**（1 个 composite） | 解析器本身 |
| BSL | `shaders.properties` 选项、`#include`、多 stage |
| Sildur's Vibrant Extreme | `shaders/` 根目录 + 各维度子目录（`world0/`、`world-1/`、`world1/`、`world_end/`） |

**通过标准**
- 包能被扫描到（`.zip` 与文件夹两种形态都要能识别）
- `shaders.properties` 的选项能被枚举出来
- `#include` 按 OF 语义解析（相对路径、可嵌套）
- `const int X = 0; // [0 1 2]` 这类选项常量能被识别
- 缺文件（比如没有 `shadow.fsh`）时**显式降级**并打日志，不静默跳过

---

## 5. P3：完整绘制链

**检查点**

| 环节 | 检查 |
|---|---|
| shadow | 光空间列表非空；阴影贴图内容合理（不是全黑/全白） |
| gbuffers | 地形、实体、水、天空分别走到对的 program |
| deferred | 每步的输入纹理是上一步的输出 |
| composite | 链顺序正确，最后一步写入主目标 |
| final | 画面回到屏幕，无重复后处理 |

**顶点 stride 自检（关键，必须实现）**

```java
// 不匹配会画成「拉伸的彩色尖刺」且不报错
int meshStride = meshFormat.getVertexSize();
int bindStride = pipeline.getVertexFormatBinding(0).getVertexSize();
if (meshStride != bindStride) {
    LOGGER.error("vkdisp: stride mismatch mesh={} binding={}, refusing to draw",
        meshStride, bindStride);
    return;
}
```

---

## 6. P4：切包回归（必跑）

```
步骤：
1. 加载 pack A，进世界，截图       → S1
2. 切到 pack B，截图               → S2
3. 切到 none，截图                 → S3
4. 再切回 pack A，截图             → S4

通过标准：S1 == S4；S2/S3 无「彩色尖刺」、无残留
```

> 这是着色器加载器的经典坑：管线缓存按 program 键，顶点格式变了旧管线会残留。

**执行方式（P4.2 落地，2026-10-01）**：切包由 `vkdisp-client.toml` 的 `shaderPack` 驱动，
**外部改值保存即自动生效**（FML FileWatcher → `ModConfigEvent.Reloading` → `VkDispConfigHotReload`
→ 资源重载），无需输入注入、无需重启 —— 全四步在**同一 runClient 会话**内完成：
`""` = 自动选包（S1/S4）、指定包名 = 切 B（S2）、`"none"` = 强制内置 passthrough（S3）。
每步等日志 `client resources loaded (initial=false)` 再截图。
首跑取证 `evidence/p417-pack-switch.md`：S1↔S4 静态地面带 identity=+1.0000、S2/S3 尖刺 0.000%。

---

## 7. 边界条件清单

| 边界 | 检查 |
|---|---|
| 空世界（超平坦、无方块） | 不崩、不画错 |
| 极高/极低 Y | 不崩 |
| 超大渲染距离（32+） | 不 OOM、不崩 |
| 极小渲染距离（2） | 正常 |
| 资源重载（F3+T） | 管线重建、不崩 |
| 退出世界再进 | 资源正确释放、不泄漏 |
| 窗口 resize / 全屏切换 | 不崩 |
| OpenGL 后端下运行 | 明确拒绝并给出可读提示（不硬崩） |

---

## 8. 性能验收（**硬指标**，带数字）

> 授权来源：用户要求「**模组要兼顾性能，部分需求可改成用 c++ 或 rust 实现**」。
> **口径与测量规范见 `17-NATIVE.md` §2 与 §7。本节只列验收线。**
> ⚠️ 注意：C++/Rust 是**未验证的可选项**（`17-NATIVE.md` 状态声明），
> 本节所有性能线**都必须由纯 Java 实现来满足**，不许以「将来上原生就能达标」为由放行。

| 指标 | 验收线 | 测量 | 适用阶段 |
|---|---|---|---|
| **不开包**（模组加载但未启用） | 帧时间相对纯原版 **≤ +2%** | F3 屏，同场景同视角，三次取中位数 | **P0 起必过** |
| 开包（中等包 BSL） | 帧时间 **≤ 同机 Iris+OF 的 110%** | 同场景对比 | P2 起 |
| 加载 / 切换包 | **≤ 3 秒**（中等包） | 计时日志 | P2 起 |
| 首帧编译 | 无 **> 200ms** 单帧卡顿 | 帧时间直方图 | P3 起 |
| 解析 + 转译全部 program | **≤ 1 秒**（中等包） | `vkdisp: parse took {} ms` | P2 起 |
| 常驻内存 | 1 小时无持续增长；增量 **≤ 200MB** | 任务管理器 / JFR | P1 起 |

**必留证据**：`17-NATIVE.md` §7.3 的基线数据表要回填实测值。
**没有实测数据的性能声明一律不认。**

### 8.1 优化纪律

```
① 先跑通（默认纯 Java）→ ② 测量 → ③ 超线才优化 → ④ 只优化超线的那一个环节 → ⑤ 再测
```

- **达标即停**，不许继续优化（超出授权范围）
- 优化前先确认该环节是 🔥 热路径（`17-NATIVE.md` §3.2）
- 上原生（C++/Rust）必须先走完 `17-NATIVE.md` §5 的六问决策树

> ⚠️ **本项目不追求**性能优于任何第三方实现。目标是**功能正确 + 不拖累原版**。
> 这两件事不冲突：先正确，再达标，不多做。

---

## 9. 回归清单（每次改动后跑；脚本见 `01-DEV-LOOP.md` §3）

```
[ ] gradlew build 退出码 0，jar 含 class
[ ] jar 不含 net/caffeinemc、net/minecraft、dev/vitrail、com/mojang
[ ] mixins.json 的 compatibilityLevel 是 JAVA_25
[ ] 启动到主菜单，无 Mixin apply failed
[ ] 进入世界
[ ] 无 pack：世界正常渲染
[ ] 无 pack：帧时间相对原版 ≤ +2%（§8）
[ ] 加载 pack：composite 生效
[ ] 切 pack → none → 再切回：无彩色尖刺
[ ] 管线注册数 == 编译成功数
[ ] 日志无 ERROR、无 validation error
[ ] 若含原生库：Java 保底路径可独立跑通（`17-NATIVE.md` N1）
```
