# B4 端到端埋点客户端验证 —— 埋点是活的；端到端 3294ms vs 预编译 77ms

> 验证对象：Round 11 已提交到 master、但**只做过离线编译与单测、从未在客户端验证**的两处
> `src/main/` 生产改动。技术债清账。
>
> **结论先行**：🔴 **`onResourceLoadFinished` 在热重载路径上确实会被触发** ——
> 端到端埋点是**活的**，不是死的。这一点由静态源码与实测日志**双重**确认。

## 环境

| 项 | 值 |
|---|---|
| 机器 | AMD Ryzen 7 8745H / Linux amd64 / WSL2（Vulkan 后端 + lavapipe，`tools/vulkan-local/env.sh`） |
| 客户端 | `./gradlew runClient`，停在主菜单 |
| 库存 | `run/shaderpacks/BSL_v10.1.8.zip` + `vkdisp-fixture-dir` + `vkdisp-fixture-zip.zip` |
| 代码 | `fbc0461`（master） |
| **日志源** | **`run/logs/latest.log`**（不是 `debug.log` —— 后者会把同一条记录再写一遍，取它会重复计数） |
| 有效样本 | **5 组配对**（§7.1 要求 ≥5，**勉强达标**；作废 1 趟，原因见下） |

## 🔴 验证 1（最关键）：`onResourceLoadFinished` 会被触发吗？

**会。** 两条独立证据。

### (a) 静态证据 —— 读 sources jar 的真源码，不是猜

`ClientResourceLoadFinishedEvent` 的派发点是
`net.neoforged.neoforge.client.ClientHooks.fireResourceLoadFinishedEvent(boolean)`，
它被 `Minecraft` 的**两个**方法调用：

```java
// net/minecraft/client/Minecraft.java
private CompletableFuture<Void> reloadResourcePacks(boolean isRecovery, @Nullable GameLoadCookie loadCookie) {
    ...
    this.gui.setOverlay(new LoadingOverlay(this, reloadInstance,
        maybeT -> Util.ifElse(maybeT, t -> { ... rollbackResourcePacks ... }, () -> {
            this.levelExtractor.allChanged();
            this.reloadStateTracker.finishReload();
            this.downloadedPackSource.onReloadSuccess();
            result.complete(null);
            this.onResourceLoadFinished(loadCookie);        // ← 重载路径
        }), !isRecovery));
}

private void onResourceLoadFinished(@Nullable GameLoadCookie loadCookie) {
    net.neoforged.neoforge.client.ClientHooks.fireResourceLoadFinishedEvent(!this.gameLoadFinished);
    ...
}
```

⇒ 事件在 **`LoadingOverlay` 的完成回调**里派发，也就是**重载真正完成之后**才发 ——
语义正是我们要的（墙钟终点 = 新画面可见）。重载时 `gameLoadFinished` 已为 true ⇒ `initial=false`。

### (b) 实测证据 —— 预编译与端到端**成对出现**，且每次端到端都更大

日志原文（`run/logs/latest.log`，逐字复制）：

```
09:57:15.561 [FileWatcher-1-thread-1/INFO] vkdisp: pack precompile scheduled: selection='vkdisp-fixture-dir' profile='' (compile moved off render thread)
...
[Render thread/INFO] vkdisp: pack precompile done in 34 ms (precompile only, resource reload follows) (cache entries=5, hits=0, misses=5, evictions=0)
[Render thread/INFO] vkdisp: B4 pack switch end-to-end: 3294 ms (scheduled -> resource reload finished; precompile is a prefix of this, see the 'precompile done' line)
```

5 组配对（Lead 独立复算过一遍，与我的逐字一致）：

| # | 预编译 | 端到端 | 资源重载段 | entries | hits | misses | evictions | 端到端 > 预编译？ |
|--:|---:|---:|---:|---:|---:|---:|---:|:--:|
| 1 | 34 ms | 3294 ms | 3260 ms | 5 | 0 | 5 | 0 | ✅ |
| 2 | 2222 ms | 6127 ms | 3905 ms | 7 | 3 | 7 | 0 | ✅ |
| 3 | 28 ms | 3067 ms | 3039 ms | 8 | 7 | 8 | 0 | ✅ |
| 4 | 2280 ms | 6308 ms | 4028 ms | 8 | 10 | 10 | 2 | ✅ |
| 5 | 77 ms | 3099 ms | 3022 ms | 8 | 12 | 13 | 5 | ✅ |

**预编译中位数 77ms ／ 端到端中位数 3294ms ／ 资源重载段中位数 3260ms（占 99.0%）。**

## ✅ 验证 2：`end-to-end > precompile` 全部成立

5 组全部成立（见上表最后一列），无一例外。这是不变量，若不成立就说明埋点逻辑坏了。

**这正是 Round 10 发现的核心问题**：过去那行 `done in {} ms -> resource reload`
只报 77ms 那一段，而用户实际等了 3294ms —— **漏掉的 3260ms（99%）过去完全不可见**。
§5 把 B4 定义为「切包等待**墙钟**」，现在才真的在记墙钟。

## ✅ 验证 3：初次进游戏不会误记（0 哨兵）

`PENDING_RELOAD_START_NANOS` 以 0 作哨兵。严格核对：

```
启动冷路径(initial=true) 所在行号 = 1526
启动之前 pack precompile 行数     = 0   （应 0）
启动之前 end-to-end 行数          = 0   （应 0）  ✅ 哨兵工作
首个 precompile 行号 = 1605     （晚于启动）
首个 end-to-end  行号 = 2617     （更晚，符合「重载完成后才发」）
```
且 `precompile` 行数 = 5、`end-to-end` 行数 = 5 ⇒ **一一配对**，启动那一趟没有多记。

## ✅ 验证 4：`evictions=` 出现，且 entries 不再归零

**逐条淘汰 vs 清空整表 —— 与 Round 10 基线对比**：

| | Round 10（`CACHE.clear()`） | 本轮（逐条淘汰） |
|---|---|---|
| entries 序列 | 6 趟全部撞顶 **8/8** | **5, 7, 8, 8, 8** |
| 撞顶后 | **整表被清空**（Round 10：第 7 次 / 第 11 次切包） | **每次只丢 1 条** |
| 撞顶后的签名 | entries 掉回 1（清空后只 put 回当前这条） | **entries 稳定在 8，从未掉回 1** ✅ |
| 淘汰累计 | （旧代码无此计数） | **0 → 0 → 0 → 2 → 5** |

⇒ 逐条淘汰路径**确实被走到**（`evictions` 从 0 累到 5），且**表从未被抹掉**。
这直接对应 Round 10 指出的后果：「一次清空之后，下一次切包要把整包重新编一遍 —— 对 B4 是直接的成本」。

## ✅ 验证 5：行为等价

| 判据 | 实测 |
|---|---|
| `pack compile done` | **6 次全部 `stages=190 ok=190 failed=0`** |
| vkdisp 日志级 ERROR | **0** |
| vkdisp 日志级 WARN | **0** |
| SPIR-V 产物行数 | **1140 = 190 × 6** ✅ 自洽 |

> 注：`pack diagnostic: WARN` 有 474 条，那是**着色器源级**的业务诊断
> （如「输入未声明位置属性」），**不是 mod 的错误日志**，与 Round 10 的基线同类同量。

## 🔴 本文件**没有**证明的事

| 未覆盖 | 说明 |
|---|---|
| **样本只有 5 组** | §7.1 要求 ≥5，**勉强达标**。且这 5 组**不是同质的**：预编译 34/28/77ms 是缓存命中档，2222/2280ms 是冷编译档（我每次切包前 `touch` 改了 mtime 强制换键）。**两组混在一起的中位数只能当整体量级看，不能当某一档的估计** |
| **有 1 趟作废** | 见下 |
| **没有做 A/B** | 未对比 `-Dvkdisp.reuse.preprocess=false`；本轮只验证「埋点是否生效」，不涉及复用开关 |
| **未锁 CPU 频率** | 同 `g0-caliber-fix.md` 的噪声红线 ⇒ 绝对值不可跨轮引用 |
| **B4 仍然超标** | 端到端中位数 **3294ms** vs 预算 **2s**；资源重载段 3260ms 占 99% ⇒ **瓶颈不在编译，在资源重载本身**。本轮没有修它 |
| **选项存储仍有值** | `BSL_v10.1.8.SHARPEN=3` 仍在 ⇒ Round 10 指出的「复用闸门在切包路径上默认关闭」**依然存在**，本轮未处理 |
| **只测了这一个客户端版本 / 这一套库存** | 3 个包；用户装更多包时撞顶更早，结论方向不变但数值会变 |

## ⚠️ 一趟作废的原因 —— 自动化脚本自身的 bug 让样本被静默丢弃

第一轮脚本里我这样数行数：

```bash
cnt() { grep -c "$1" "$LOG" 2>/dev/null || echo 0; }
```

`grep -c` 在**零匹配时仍然打印 `0`，但退出码是 1**，于是 `|| echo 0` 又追加了一个 `0`，
变量变成**两行** `"0\n0"`。后面 `[ "$now" -gt "$base" ]` 就炸在
`integer expected`，第 2 次切包因此**超时作废**。

**这一类错误比崩溃更危险**：它不报错、不中断，只是**安静地让样本少一个**，
如果我没核对行数就会把 n=6 当成 n=6 报上去。
🔖 **和本轮反复出现的「不崩，只让数变假」是同一族**（G2 的
`WrongMethodTypeException` 被吞掉、把异常开销记成跨界开销，也是同一族）。

修正方式：`cnt() { grep -c "$1" "$LOG" 2>/dev/null; true; }`（只取 grep 自己的输出，
不再 `||` 追加），并在每步用**行数增量**而不是「是否存在」判定新事件。

第一轮（4 次切包意图）因此只拿到 **1 组**有效样本；第二轮（6 次意图）拿到 **5 组**。
本文件用的是第二轮。

## 结论

1. 🔴 **`onResourceLoadFinished` 会触发，埋点是活的** —— 静态源码 + 实测日志双重确认。
2. 🔴 **补上了 Round 10 指出的缺口**：过去只看得到 77ms 那一段，
   现在能看到 3294ms 的真实墙钟，**漏掉的 3260ms（99%）终于可见**。
3. ✅ **初次进游戏不误记**（0 哨兵严格核对通过），5 组一一配对。
4. ✅ **逐条淘汰生效**：entries 稳定在 8 从未归零，`evictions` 累计到 5；
   Round 10 的「撞顶即清空整表」已被消除。
5. ✅ **行为等价**：6 次 `stages=190 ok=190 failed=0`、0 ERROR/WARN、1140 条 SPIR-V 自洽。
6. ⏭️ **B4 仍然超标**（3294ms vs 2s），且瓶颈已定位在**资源重载本身**（占 99%），不在编译。
   下一轮该攻的是它，不是缓存也不是编译。

## 📌 我对运行期环境做了什么（副作用披露）

| 对象 | 改动 | 是否还原 |
|---|---|---|
| `run/config/vkdisp-client.toml` | 切包期间把 `shaderPack` 在 `""` / `BSL_v10.1.8` / `vkdisp-fixture-dir` 之间来回改 | ✅ **已还原为 `""`**（原始值） |
| `run/config/vkdisp-pack-options.properties` | **未改动** | sha256 `e76d3fd3…` 与本轮开头记录一致 |
| `run/shaderpacks/BSL_v10.1.8.zip` | 每次切包前 `touch` 改 mtime（**内容逐字节不变**，目的是换缓存键强制冷编译） | ⚠️ **mtime 未还原**（内容未变） |
| 游戏进程 | 2 次 runClient | ✅ 已 kill，`残留游戏进程数=0` |

`run/` 是 gitignore 的，不进版本库；但 mtime 的变化会让**下一次**运行的
`PackCompileCache` 键不同（该键含 pack identity/mtime），下一个人若要复现本文件
的条目数，需知道这一点。

⚠️ 一处失误如实登记：我进本轮时**备份了** `run/config/` 的两个文件到 `/tmp/b4v-backup/`，
但收尾时**该目录已不存在**（`/tmp` 被清理），导致 `cp` 还原失败。
我没有假装还原成功，而是按**本轮开头实际读到的原始内容**手工还原了 `shaderPack = ""`，
并用 sha256 核对了选项存储确实未被动过。
🔖 **教训：备份放在会被清理的临时目录，等于没有备份** ——
这类取证任务若要动运行期配置，备份应放在工作区内或至少放在 `/tmp` 之外。
