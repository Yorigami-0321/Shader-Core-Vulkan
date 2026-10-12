# `libs/` —— 随仓库分发的第三方 jar

## 为什么这三个 jar 要入库

`build.gradle` 用 **`files()`** 引用它们（不是 Maven 坐标）。`files()` 不查任何仓库、也不做解析，
路径不在就等于该依赖不存在 ⇒ `JcppDifferentialProbeTest` 的 `import org.anarres.cpp.*` 在
`compileTestJava` 阶段直接编不过，`test` 任务根本跑不到。

用文件依赖的原因：本机 Gradle 全局代理（`127.0.0.1:12334`）未运行时 Maven Central 不可达，
jar 只能 `curl --noproxy` 手动取。但**取回本地不等于解决** —— 2026-10-10 的 `2a8ceb8` 就是这样把
`/libs/` 写进了 `.gitignore`，结果本机增量构建全绿、CI 干净检出连红 6 次（#148~#153）。
所以这三个 jar 与 `gradle-wrapper.jar` 同类：**必须入库**。

## 清单

| jar | Maven 坐标 | 许可证 | 上游 | SHA-256 |
|---|---|---|---|---|
| `jcpp-1.4.14.jar` | `org.anarres:jcpp:1.4.14` | Apache-2.0 | <https://github.com/shevek/jcpp> | `e47d785bff4d116bf5223b96aabd57a03498a91bd259b4919ded6c511b47cc8c` |
| `slf4j-api-1.7.12.jar` | `org.slf4j:slf4j-api:1.7.12` | MIT | <https://www.slf4j.org> | `0aee9a77a4940d72932b0d0d9557793f872e66a03f598e473f45e7efecdccf99` |
| `java-semver-0.8.0.jar` | `com.github.zafarkhaja:java-semver:0.8.0` | MIT | <https://github.com/zafarkhaja/java-semver> | `6fabfdab05e463fabe48907fad677d6a81e01dc853c9fa7d11602621c112b282` |

三个都是**上游原始构件，未做任何修改**（无移植、无重打包），故 `07-CONSTRAINTS.md` §1.3 的
"声明改动"义务不适用。jcpp 的许可判定与裁决记录见 `docs/19-IMPROVEMENT-PATHS.md` §7-1、
`docs/07-CONSTRAINTS.md` §423；jcpp jar 内未带 `LICENSE` 文本，需要全文时走上游 URL 取。

## 为什么需要三个而不是一个

`JcppDifferentialProbeTest` 只用 `org.anarres.cpp.*`，但 jcpp 自身带运行期引用：

- `Preprocessor` / `MacroTokenSource` / `DefaultPreprocessorListener` 里有 `org.slf4j.Logger` 字段
  ⇒ 缺 `slf4j-api` 会 `NoClassDefFoundError`；
- `BuildMetadata` 引用 `com.github.zafarkhaja.semver` ⇒ 缺 `java-semver` 同理。

上游 POM 声明的其余传递依赖（guava / ant / logback）测试路径不触及，**没有**跟着下载。

## 作用域：只进测试源集

声明位置是 `testImplementation`，不是 `implementation` ⇒ 这三个 jar 不进发布的 mod jar、
不进运行期类路径，生产路径对 jcpp 零依赖（`07-CONSTRAINTS.md` §480 那条自检针对的是分发产物）。

## 重新获取

```bash
mkdir -p libs
curl --noproxy '*' -o libs/jcpp-1.4.14.jar \
  https://repo1.maven.org/maven2/org/anarres/jcpp/1.4.14/jcpp-1.4.14.jar
curl --noproxy '*' -o libs/slf4j-api-1.7.12.jar \
  https://repo1.maven.org/maven2/org/slf4j/slf4j-api/1.7.12/slf4j-api-1.7.12.jar
curl --noproxy '*' -o libs/java-semver-0.8.0.jar \
  https://repo1.maven.org/maven2/com/github/zafarkhaja/java-semver/0.8.0/java-semver-0.8.0.jar
sha256sum libs/*.jar   # 必须与上表逐位一致
```
