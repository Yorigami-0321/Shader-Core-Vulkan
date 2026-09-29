package dev.vkdisp.pack;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 【参考调研】ShaderPackRepository（B 线：路径规划）
 * 0. 合规核对：原创设计，无任何第三方代码（handover §5.2）。MIT 不受影响。
 * 1. 官方/主实现：无（不参考 LGPL/GPL 实现）。
 * 2. 备选：无。
 * 3. 我们的差异点：只做「解包索引 + 路径规划」，**不注册**虚拟资源包（注册需碰原版资源系统，属主线）。
 * 4. 许可证核对结论：原创，可并入 MIT。
 * 5. 性能基线：冷路径，清晰优先。
 */
public final class ShaderPackRepository {

    /**
     * 一个合法包的挂载路径规划：
     * - 目录包：shadersRoot 是真实目录，shaderFiles 是相对 shaders/ 的路径；
     * - zip 包：source 是 zip 文件，shadersPrefix 恒为 "shaders/"，shaderFiles 是 zip 内部相对路径。
     */
    public static final record MountPlan(
        String packName,
        ShaderPackScanner.Kind kind,
        Path source,
        String shadersPrefix,
        List<String> shaderFiles
    ) {
        /** 解析一个相对着色器路径到可读流（仅用于索引/校验，不注册资源包）。关闭流会同时关闭 zip。 */
        public InputStream openShader(String relativePath) throws IOException {
            if (kind == ShaderPackScanner.Kind.DIRECTORY) {
                return Files.newInputStream(source.resolve(shadersPrefix).resolve(relativePath));
            }
            ZipFile zf = new ZipFile(source.toFile());
            ZipEntry e = zf.getEntry(shadersPrefix + relativePath);
            if (e == null) {
                zf.close();
                throw new IOException("zip 内无此条目: " + shadersPrefix + relativePath);
            }
            InputStream raw = zf.getInputStream(e);
            return new FilterInputStream(raw) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        zf.close();
                    }
                }
            };
        }
    }

    private ShaderPackRepository() {}

    /**
     * 为已发现的合法包规划挂载路径：枚举 shaders/ 下所有文件并建索引。
     * 仅读取、不修改任何资源；不向原版资源系统注册（那一步属主线）。
     */
    public static MountPlan plan(ShaderPackScanner.DiscoveredPack pack) throws IOException {
        List<String> files = new ArrayList<>();
        if (pack.kind() == ShaderPackScanner.Kind.DIRECTORY) {
            Path root = pack.source().resolve(pack.shadersPrefix());
            if (!Files.isDirectory(root)) {
                throw new IOException("包的有效 shaders 根不存在: " + root);
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                    .forEach(p -> files.add(root.relativize(p).toString().replace('\\', '/')));
            }
        } else {
            try (ZipFile zf = new ZipFile(pack.source().toFile())) {
                String prefix = pack.shadersPrefix();
                zf.stream()
                    .filter(en -> !en.isDirectory() && en.getName().startsWith(prefix))
                    .forEach(en -> files.add(en.getName().substring(prefix.length())));
            }
        }
        files.sort(null);
        return new MountPlan(pack.name(), pack.kind(), pack.source(), pack.shadersPrefix(), List.copyOf(files));
    }
}
