# h23 · 🔴 GAP-010 **根因定位并修复**：`resourceLoad/ERROR` 从 **12 → 0**

> 取证方式：**纯静态定位** → **实现修复** → **客户端验证**。
>
> 判定：🔴 **GAP-010 已解决**。这是本会话第一次拿到「用户可见问题被真正消掉」的闭环。

---

## 〇、一句话结论

根因是一行「顺手多清一下」的代码：`takeTerrainSourceMemo()` 在取走**片元** memo 时，
**顺手把适配层 memo 也置了 null**；而调用点恰好是**先取片元、再取适配层**
⇒ 适配层字节恒为 `null` ⇒ 资源加载期找不到它的 VERTEX 源 ⇒ **12 条** `resourceLoad/ERROR`。

---

## 一、根因

~~~java
private static String takeTerrainSourceMemo() {
    String memo = terrainSourceMemo;
    terrainSourceMemo = null;
    terrainAdapterMemo = null;   // 🔴 问题就在这一行
    terrainMemoKey = null;
    return memo;
}
~~~

调用点恰好是**先取片元、再取适配层**：

~~~java
String terrainSource   = takeTerrainSourceMemo();   // ← 这一行把适配层清了
String terrainAdapter = takeTerrainAdapterMemo();  // ← 于是永远拿到 null
~~~

🔶 ⇒ 适配层字节恒为 `null` ⇒ `VirtualPackResources` 不投放 `terrain_pack_adapter`
⇒ 资源加载期 `PipelineBuilder` 找不到它的 VERTEX 源 ⇒ **12 条** `resourceLoad/ERROR`。

🟡 之所以看起来「非致命」：日志里资源重载了**两次**，第二次就不报了 ⇒
症状是**依赖一次重载才自愈**，而且**用户在 UI 上看得见**。

🔖 代码注释里原本写着「与 `takeTerrainSourceMemo()` 同批取走：两者要么都给、要么都不给」——
**实现与注释的意图正好相反**：不是同批取走，而是把适配层**丢掉了**。

---

## 二、修法

**删掉那一行**，并在原地写清为什么不能有它。

🔖 **语义不变**：「同生共死」依然成立 —— 片元为 `null` 时适配层也是 `null`；
而 `takeTerrainAdapterMemo()` **本来就会清自己**，不需要这里多一手。

---

## 三、验证

| 判据 | 修复前 | 修复后 |
|---|---|---|
| `resourceLoad/ERROR` | **12** | **🔶 0** |
| `terrain source reused from early contract` | 有 | 有（片元源仍被正确复用，未多编一次） |
| 单测 | — | **705** 全绿（**+4**） |

---

## 四、回归测试（4 条，纯源码断言）

新建 `PackTerrainMemoTakeTest`：

1. 取片元 memo 时**不得**出现 `terrainAdapterMemo`（守住本轮根因）；
2. 但**自己**的 `terrainSourceMemo = null;` 与 `terrainMemoKey = null;` 必须留着
   （删掉会拿到上一轮的过期源）；
3. 适配层 memo **必须由 `takeTerrainAdapterMemo()` 自己清理**（取走即清空是既定契约）；
4. 生成链必须**同生共死**：取不到片元时走 else 分支重生成，并**重取适配层**。

🔰 `methodBody` 剥掉注释后再断言 —— 否则「注释里提到 `terrainAdapterMemo`」会误报。
这是本轮实际踩到并修掉的：**第一版断言失败，恰恰是因为它把注释也读了**。

---

## 五、净结果

| 项 | 状态 |
|---|---|
| GAP-010 根因 | 🔴 **定位并修复** |
| `resourceLoad/ERROR` | **12 → 0** |
| 回归测试 | **+4** 条 |
| 单测总数 | **705** 全绿 |
| 残留游戏进程 | **0** |
| 配置 | 已复原为默认 |

---

## 六、本轮**没有**证明的

1. ❌ 闪烁/黑天（GAP-011）**仍未定位** —— 与 GAP-010 无关。
2. ❌ GAP-008 仍未判定；GAP-007 / GAP-009 未动；M-04 仍需用户裁决。

---

## 七、产物与哈希

| 文件 | sha256 |
|---|---|
| `run/logs/latest.log`（`resourceLoad/ERROR` = 0） | `eba43bbadeeec4b5a2e17c8ef6822ce373444d771007eb8313f86cf0d9687aec` |

收尾 `game_procs.sh kill` → 残留 0。
