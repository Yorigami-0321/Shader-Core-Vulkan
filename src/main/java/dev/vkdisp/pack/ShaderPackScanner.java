package dev.vkdisp.pack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * 【参考调研】ShaderPackScanner（B 线：扫包）
 * 0. 合规核对（🔴 先写这条，不通过就换参考）：
 *    本类为**原创设计**，无任何第三方代码参考（handover §5.2 明确「无直接参考，按原版资源包语义自行设计」）。
 *    → 能否并入本项目（MIT）：可以（无外部许可证负担、零代码搬运）。
 *    → 例外条款：无。
 * 1. 官方/主实现：无。OptiFine/Iris 的扫包逻辑为 LGPL/GPL，本项目**只读其事实性规则**（见 §2），不读其代码。
 * 2. 备选：无。
 * 3. 我们的差异点：仅做扫描 + 边界识别，**不注册**虚拟资源包（注册需碰原版资源系统，属主线 P2.1）。
 * 4. 许可证核对结论：原创，MIT 不受影响，可并入。
 * 5. 性能基线：冷路径，清晰优先，不做优化（docs/07 T14 / X14）。
 */
public final class ShaderPackScanner {

    public enum Kind { DIRECTORY, ZIP }

    /** 扫描过程中能识别出的「不健康」情况（T11：不崩且显式报错，不静默）。 */
    public enum ProblemKind {
        INVENTORY_MISSING,    // shaderpacks/ 目录本身不存在或不是目录
        NO_PACKS_FOUND,       // 目录存在但一个合法包都没有（空目录 / 只有杂项文件）
        EMPTY_PACK_ENTRY,     // 某个包条目是空目录（无任何内容）
        MISSING_SHADERS_DIR,  // 包内找不到 shaders/（也没有嵌套一层的情况）
        OUTER_FOLDER_NESTED,  // 「外壳文件夹」多嵌套一层（OF：列表里显示但加载不了）
        BROKEN_ZIP,           // .zip 损坏，无法打开
        NOT_A_PACK            // 既不是目录也不是 .zip（理论上已被忽略，保留作兜底）
    }

    /** 一个被发现、结构合法的包。 */
    public static final record DiscoveredPack(String name, Kind kind, Path source, String shadersPrefix) {
        /** 目录包：shaders/ 的真实路径；zip 包：返回 zip 文件本身（入口前缀见 shadersPrefix）。 */
        public Path effectiveShadersRoot() {
            return kind == Kind.DIRECTORY ? source.resolve(shadersPrefix) : source;
        }

        /**
         * 缓存身份（P4.5 {@link PackCompileCache} 用）：<b>路径 + 种类 + 大小 + 修改时间</b>。
         *
         * <p>为什么不只用路径：用户可能<b>原地替换</b>包文件（同路径、不同内容）。只用路径做
         * 缓存键会吃到上一个包的产物 —— 那会让「换了包但画面还是旧的」，且极难排查。
         * 带上大小与修改时间后，替换文件自然换键。
         *
         * <p>读文件属性失败（权限/文件被移动）→ 退化为「只有路径」，宁可多编一次也不
         * 拿到可能过期的产物（缓存只是加速器，miss 无正确性代价）。
         */
        public String identity() {
            StringBuilder text = new StringBuilder(64);
            text.append(source).append('|').append(kind);
            try {
                if (java.nio.file.Files.isRegularFile(source)) {
                    text.append('|').append(java.nio.file.Files.size(source))
                            .append('|').append(java.nio.file.Files.getLastModifiedTime(source).toMillis());
                } else if (java.nio.file.Files.isDirectory(source)) {
                    //目录包：取整个目录树的最新修改时间（任一子文件变了就换键）
                    text.append("|dir|").append(newestModifiedMillis(source));
                } else {
                    text.append("|missing");
                }
            } catch (java.io.IOException e) {
                //读不到属性 → 只用路径（退化为必然多编一次，但绝不返回错误产物）
                text.append("|stat-failed");
            }
            return text.toString();
        }

        /** 目录树里最新的修改时间（毫秒）；空目录 = 0。 */
        private static long newestModifiedMillis(Path root) throws java.io.IOException {
            final long[] newest = {0L};
            try (java.util.stream.Stream<Path> walk = java.nio.file.Files.walk(root)) {
                walk.filter(p -> !java.nio.file.Files.isDirectory(p)).forEach(p -> {
                    try {
                        newest[0] = Math.max(newest[0],
                                java.nio.file.Files.getLastModifiedTime(p).toMillis());
                    } catch (java.io.IOException ignored) {
                        // 单个文件读不到时间不影响整体（有其它文件兜底；全失败则 newest 保持 0）
                    }
                });
            }
            return newest[0];
        }
    }

    /** 一个问题条目：出问题的路径、类型、人类可读信息。 */
    public static final record PackProblem(Path entry, ProblemKind kind, String message) {}

    /** 一次扫描的完整结果：合法包 + 问题清单，二者分离，便于调用方分别处理。 */
    public static final record ScanResult(List<DiscoveredPack> packs, List<PackProblem> problems) {}

    private static final String SHADERS = "shaders";

    private ShaderPackScanner() {}

    /**
     * 扫描 inventoryDir（对应游戏里的 shaderpacks/），同时返回合法包与问题清单。
     * 设计为「永不抛非受检异常」：任何异常都降级为 problems 中的一条，保证调用方不会崩。
     */
    public static ScanResult scan(Path inventoryDir) {
        List<DiscoveredPack> packs = new ArrayList<>();
        List<PackProblem> problems = new ArrayList<>();

        if (inventoryDir == null || !Files.isDirectory(inventoryDir)) {
            problems.add(new PackProblem(inventoryDir, ProblemKind.INVENTORY_MISSING,
                "shaderpacks 目录不存在或不是目录: " + inventoryDir));
            return new ScanResult(List.copyOf(packs), List.copyOf(problems));
        }

        List<Path> entries;
        try (Stream<Path> children = Files.list(inventoryDir)) {
            entries = children.toList();
        } catch (IOException e) {
            problems.add(new PackProblem(inventoryDir, ProblemKind.INVENTORY_MISSING,
                "读取 shaderpacks 目录失败: " + e.getMessage()));
            return new ScanResult(List.copyOf(packs), List.copyOf(problems));
        }

        for (Path entry : entries) {
            String fileName = entry.getFileName().toString();
            if (Files.isDirectory(entry)) {
                inspectDirectory(entry, packs, problems);
            } else if (fileName.toLowerCase().endsWith(".zip")) {
                inspectZip(entry, packs, problems);
            }
            // 其它文件（如 .txt / .png）按杂项忽略，不算包
        }

        if (packs.isEmpty() && problems.isEmpty()) {
            problems.add(new PackProblem(inventoryDir, ProblemKind.NO_PACKS_FOUND,
                "shaderpacks 目录为空或没有任何合法着色器包"));
        }
        return new ScanResult(List.copyOf(packs), List.copyOf(problems));
    }

    private static void inspectDirectory(Path dir, List<DiscoveredPack> packs, List<PackProblem> problems) {
        Path shaders = dir.resolve(SHADERS);
        if (Files.isDirectory(shaders)) {
            packs.add(new DiscoveredPack(dir.getFileName().toString(), Kind.DIRECTORY, dir, SHADERS));
            return;
        }
        // 「外壳文件夹多嵌套一层」：恰好一个子目录，且该子目录内含 shaders/
        List<Path> subDirs;
        try (Stream<Path> s = Files.list(dir)) {
            subDirs = s.filter(Files::isDirectory).toList();
        } catch (IOException e) {
            problems.add(new PackProblem(dir, ProblemKind.MISSING_SHADERS_DIR,
                "无法列举包目录内容: " + e.getMessage()));
            return;
        }
        if (subDirs.size() == 1 && Files.isDirectory(subDirs.get(0).resolve(SHADERS))) {
            problems.add(new PackProblem(dir, ProblemKind.OUTER_FOLDER_NESTED,
                "外壳文件夹多嵌套一层（" + subDirs.get(0).getFileName() + "/shaders），OF 下会显示但加载失败"));
            return;
        }
        // 空目录 vs 缺 shaders/
        boolean empty;
        try (Stream<Path> s = Files.list(dir)) {
            empty = s.findAny().isEmpty();
        } catch (IOException e) {
            empty = false;
        }
        problems.add(new PackProblem(dir,
            empty ? ProblemKind.EMPTY_PACK_ENTRY : ProblemKind.MISSING_SHADERS_DIR,
            empty ? "包目录为空（无 shaders/）" : "包内找不到 shaders/ 目录"));
    }

    private static void inspectZip(Path zip, List<DiscoveredPack> packs, List<PackProblem> problems) {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            List<String> names = new ArrayList<>();
            zf.stream().forEach(en -> names.add(en.getName()));
            boolean hasShaders = names.stream()
                .anyMatch(n -> n.equals(SHADERS + "/") || n.startsWith(SHADERS + "/"));
            if (hasShaders) {
                packs.add(new DiscoveredPack(stripZipSuffix(zip.getFileName().toString()), Kind.ZIP, zip, SHADERS + "/"));
                return;
            }
            // 嵌套检测：恰好一个顶层目录，且该目录下含 shaders/
            List<String> topDirs = names.stream()
                .filter(n -> n.endsWith("/") && n.indexOf('/') == n.length() - 1)
                .map(n -> n.substring(0, n.length() - 1))
                .distinct().toList();
            if (topDirs.size() == 1) {
                String t = topDirs.get(0);
                boolean nested = names.stream().anyMatch(n -> n.startsWith(t + "/" + SHADERS + "/"));
                if (nested) {
                    problems.add(new PackProblem(zip, ProblemKind.OUTER_FOLDER_NESTED,
                        "外壳文件夹多嵌套一层（" + t + "/shaders），OF 下会显示但加载失败"));
                    return;
                }
            }
            problems.add(new PackProblem(zip, ProblemKind.MISSING_SHADERS_DIR, "zip 内找不到 shaders/ 目录"));
        } catch (IOException e) {
            problems.add(new PackProblem(zip, ProblemKind.BROKEN_ZIP, "zip 损坏无法打开: " + e.getMessage()));
        }
    }

    private static String stripZipSuffix(String name) {
        return name.toLowerCase().endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
    }
}
