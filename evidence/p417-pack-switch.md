# P4.2 切包回归证据（2026-10-01，四张截图单会话闭环）

> G-01 文本摘要：日志关键行（原文）+ sha256 + 一行复现 + 判定。
> 被摘要的日志/截图本体在 gitignored 路径（`run/logs/`、`tools/vulkan-local/evidence/`），
> 本文件只存可复核的事实；复现后按下方 sha256 对账即可确认取到了同一份证据。

## 一行复现

```bash
source tools/vulkan-local/env.sh && export JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true" && ./gradlew runClient -PquickPlay --console=plain
```

- **切包驱动（本轮新增，无需输入注入）**：外部改 `run/config/vkdisp-client.toml` 的
  `shaderPack` 值 → FML nightconfig FileWatcher（500ms 去抖）→ `ModConfigEvent.Reloading`
  → `VkDispConfigHotReload` → `Minecraft.reloadResourcePacks()`。
  三态：`""` = 自动（扫描顺序）/ `"none"` = 强制内置 passthrough / 其它 = 按包名精确匹配。
- **四步**：S1 默认 `""`（BSL 自动选中）→ 改 `"vkdisp-fixture-dir"` 截 S2 → 改 `"none"`
  截 S3 → 改回 `""` 截 S4。每次改值等 `client resources loaded (initial=false)` 再截图：
  `python3 tools/vulkan-local/x11_capture.py OUT.png --window-id 0x…`
  （窗口 id 每次启动变化，先 `--list-windows`；本跑 = 0x002017e1，930×577 depth=32）。
- 判读口径同 p416：crop `(35,62,w-37,h-39)`，`luma=(r*299+g*587+b*114)//1000`；
  静态地面对比带 = 全局行 400–520。环境：WSL2，llvmpipe（Mesa 26.2.3），存档基线同 p413。

## 机制（本轮实现，字节码核实后落地）

调研推翻了此前「FML 无配置文件监视」的中途结论（当时只 javap 了 `ConfigTracker` 公有方法，
漏了同包 `ConfigWatcher`）：

- **FML 已内置热加载链**（loader-12.0.0 字节码核实）：`ConfigTracker.openConfig` 注册
  `nightconfig FileWatcher.addWatch(path, ConfigWatcher)`（日志 `Watching TOML config file …`）
  → 外部改 TOML → `ConfigWatcher.run`（500ms 去抖，日志 `Config file {} changed, re-loading`）
  → `ConfigTracker.loadConfig(config, path, Reloading::new)` 重读落盘 → 发
  **`ModConfigEvent.Reloading`**（mod bus，IModBusEvent）。GUI 保存路径同事件。
- **我方接线（新增 `VkDispConfigHotReload`）**：modid 过滤 → T11 显式 INFO（新值全量落日志）
  → `minecraft.execute(() -> reloadResourcePacks())`（观察者线程 → 渲染线程）。
  不订阅 `Loading`（启动首载，此刻资源加载未开始，重载是空转）/ `Unloading`（无后续语义）。
- **选择语义（`PackCompositeSource.generate` 三参重载，+4 单测）**：
  `""` = P2.4 扫描顺序逐包尝试（原行为一行不改）；`"none"` = 保留名，不扫包直接 WARN + 内置
  passthrough；其它 = 只让 `DiscoveredPack.name` 精确匹配的包参与选择，缺名/坏包 → 显式 WARN +
  兜底，**绝不静默落到别的包**（否则 S2 判据失去意义）。
- **无残留的机制保证**：资源重载重建 ShaderManager → 9 条自定义管线随重载重编译、旧包管线
  随重载释放；三源/布局/链路开关在 `openResources` 全量重写；链路埋点是边沿触发（停用不打
  日志、再激活重打），日志天然记录切换往返。

## 四阶段对照（单会话，`run/logs/p417-run1.log` 行号可复核）

| 阶段 | 改配置时刻 | hot-reload INFO | 选择/产出 | 重载完成 | 截图 |
|---|---|---|---|---|---|
| S1 | （启动，`shaderPack` 缺省 `""`） | —（启动首载非热加载） | `selection=''` → `pack=BSL_v10.1.8 bytes=24515 diagnostics=371` | `initial=true` 15:22:04 | p417_s1_bsl.png mean=32.84 |
| S2 | 15:25:12 改 `"vkdisp-fixture-dir"` | 15:25:12.952（FileWatcher-1-thread-1） | `selection='vkdisp-fixture-dir'` → `pack=vkdisp-fixture-dir bytes=1655 diagnostics=3` | `initial=false` 15:25:17.5 | p417_s2_fixture.png mean=43.13 |
| S3 | 15:27:55 改 `"none"` | 15:27:55.744 | `selection='none'` → **`fallback=true pack=null bytes=424`** | `initial=false` 15:27:59.3 | p417_s3_none.png mean=60.65 |
| S4 | 15:28:45 改回 `""` | 15:28:45.909 | `selection=''` → `pack=BSL_v10.1.8 bytes=24515 diagnostics=371`（**与 S1 字节同长**） | `initial=false` 15:28:51.2 | p417_s4_bsl_return.png mean=32.73 |

- 端到端延迟：改文件 → 源重生成 ≤ 40ms，重载完成 ≈ 4–5s（含 9 管线 GLSL→SPIR-V 重编译）。
- 链路边沿埋点的往返闭合：
  `deferred chain wired` / `final chain wired` 启动各 1 次 → B 换 fixture 后 final 停用（按设计
  不打日志）→ C 换 none 后 deferred 停用、`composite input source: deferred output → scene capture
  (P3.2 terrain)` → D 回 BSL 后 **`deferred chain wired` / `final chain wired` / `composite input
  source: deferred output (P3.3)` 三行全部重打**（15:28:47.948–949）。

## 截图判读（§6 通过标准）

**S1 == S4（切回 A 无残留）**：

- 静态地面带（行 400–520）互相关 **identity = +1.0000**（垂直镜像 = +0.2567）；
  带 RGB S1=(95.1,87.7,86.1) vs S4=(95.1,87.7,86.1)，**三通道差 0.0** —— 静态场景逐像素复原。
- 全 crop 相关 = +0.9390、diff%(thr8) = 24.36%：差异全部来自天空带（70–180 identity 仅 +0.2417）
  —— 6 分钟会话内云飘移（p416 已登记的同源环境因素，run2↔run3 亦有 26.21% 通道差）；
  均值比 S4/S1 = 0.9964。目检：构图/地平线/地面纹理/右下沟壑一致，方向正立（p416 修复经
  两次切换保持）。

**S2/S3 无「彩色尖刺」、无残留**：

- 饱和尖刺像素（crop 内非黑且 max−min 通道 > 80）：S1=0.088%、S4=0.090%（BSL 自身内容）、
  **S2=0.000%、S3=0.000%** —— 切换态无 BSL 风格化残留、无撕裂色块。
- S2 逐像素亮度比 S2/S3 **median=0.7108 vs 理论 0.711**（fixture deferred `×(1,0.7,0.7)` 叠
  composite `×0.9`：0.9×(0.299+0.701×0.7)=0.711）—— fixture 全链（deferred→offscreen2→composite）
  在重载后的画面里逐像素成立，顺带补上 p416 未覆盖的「deferred 链视觉传导」抽证。
- 目检：S2 全画面均匀粉调（fixture 色调）+ 无 BSL 蓝色地平线伪影；S3 中性明亮（passthrough）；
  S1/S4 恢复 BSL 观感。三态两两 diff%(thr8) = 58.8–61.5%（换包确实换画面）。

## 不变量与零回归

- `stages=190 ok=49 failed=141` **×4 次逐字一致**（启动 + 3 次重载重扫）；
  ERROR 直方图 = 已知 141 阶段矩阵的 6 类指纹 × **恰好 4 份**（176/144/132/80/12/12/8 = 44×4、
  36×4、33×4、20×4、3×4×2、2×4）+ 环境类（SoundEngine ×4、Narrator ×1）—— **零新错误类**。
- `pipeline count check: registered=9, compiled=9 (aligned)`；三次重载均无
  `pipelines not compiled` ERROR（该 ERROR 若会触发则必现：启动时未触发 = 门闩仍开着）；
  `Missing uniform` = 0、`fullscreen pass failed` = 0、`解析失败` = 0（p415 修复保持）。
- `builtins uploaded` 三槽 = 启动一次性埋点（composite26/512、final24/512、deferred24/512）；
  重载后不再重打 —— 重载后的 uniform/采样器正确性由 S2 的 0.7108 逐像素比值直接证明。
- debug.log：`Watching TOML config file … vkdisp-client.toml` ×1（注册）+
  `Config file vkdisp-client.toml changed, re-loading` ×3（三次外部改动，全部命中）。

## 证据文件 sha256

| 文件 | sha256 |
|---|---|
| `run/logs/p417-run1.log`（2258677 B，15:21:51–15:29，单会话四阶段全链） | `0917851a985103246f414f1397c69144df87b12ac4af25b7722b832676cc52ad` |
| `tools/vulkan-local/evidence/p417_s1_bsl.png`（119134 B，S1 BSL 基线 mean=32.84） | `effa22ae4909134182975349c33eb458acc73ba7925be059f58cebf97706cf9d` |
| `tools/vulkan-local/evidence/p417_s2_fixture.png`（80044 B，S2 fixture 全链 tint mean=43.13） | `fac45e125b9592e44f799e6403e5de78c37ec516c3ff171b2df4f0f36889e95b` |
| `tools/vulkan-local/evidence/p417_s3_none.png`（96168 B，S3 none passthrough mean=60.65） | `394abbeb479dca4c49917b0ba0944fa9e19ce492712c9d61d81443ea39cc62be` |
| `tools/vulkan-local/evidence/p417_s4_bsl_return.png`（118417 B，S4 切回 mean=32.73） | `6c11f5bd8ddbbb71b1a995dda710c998273738d2c8734c8cabb897da2cd3103e` |

## 单测

`./gradlew build` exit=0；**505 用例 0 失败 0 错误**（501 → 505，+4：
`PackCompositeSourceTest` 选择四件套 —— `noneSelectionForcesPassthroughWithoutInventoryClaims` /
`namedSelectionPicksExactPackRegardlessOfScanOrder` /
`unknownNamedSelectionFallsBackWithoutTouchingOtherPacks` /
`namedSelectionWithBrokenTargetDoesNotFallThroughToNextPack`）。

## 判定

| 判据（08-TESTING §6） | 结果 |
|---|---|
| S1 == S4（切回 A 无残留） | ✅ 静态地面带 identity=+1.0000、带 RGB 三通道差 0.0；全 crop +0.9390（残差=云飘移，已登记） |
| S2 无残留、无彩色尖刺 | ✅ 尖刺 0.000%；目检均匀 fixture tint、无 BSL 伪影；亮度比 = 理论 0.711 |
| S3 无残留、无彩色尖刺 | ✅ 尖刺 0.000%；目检中性明亮 passthrough；`fallback=true pack=null` 日志锚定 |
| 切换在会话内完成（非冷启动） | ✅ 单 runClient 会话四阶段，热加载 → 重载链日志齐全（3× hot-reload + 4× ready + 4× loaded） |
| 选择确定性（不静默换包） | ✅ 单测 ×4 + 日志 `selection=` 三态逐阶段可见；named 模式缺名/坏包走兜底有 WARN |
| 零回归 | ✅ stages 190/49/141 ×4、registered=9 compiled=9、Missing uniform=0、解析失败=0、无新错误/告警类 |
| 方向经切换保持 | ✅ S1/S4 目检正立（p416 修复跨切换不回归） |

**未覆盖（登记）**：
1. **GUI 保存路径**触发同一 `Reloading` → 自动重载（字节码已证同事件），但本轮未点 GUI 实测
   —— P4.3 选项 GUI 轮一并取证；
2. `packProfile` 热切换（同一驱动链，本轮只切了 `shaderPack`；profile 改画面在 P2.4 已单独证）；
3. **141 阶段矩阵**（6 类指纹）仍是登记缺口 —— 本轮仅证其 ×4 稳定复现、零新增
   （01-DEV-LOOP P4.2 行不含矩阵修复，留后续轮）；
4. `builtins uploaded` 重载后的一次性埋点不重打（重载期 uniform 正确性以 S2 像素比值代证）；
5. 菜单态（未进世界）下的热切换 —— 本轮四阶段全程在世界内。
