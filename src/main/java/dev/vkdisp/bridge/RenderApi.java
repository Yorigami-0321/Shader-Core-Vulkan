package dev.vkdisp.bridge;
/**
 * 【参考调研】F1 契约冻结 / 原版 renderpearl 管线构建 API
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 原版 26.3 客户端 jar 内 com.mojang.renderpearl.api.pipeline.*（运行平台官方 API）
 *    + NeoForge 26.3.0.23-beta NeoForgeRenderPipelines（LGPL-2.1，官方注册用法范本）。
 *    只观察 javap 签名与官方调用点，零源码文本搬运；参考模组（VulkanMod/Sulkan 等）零接触。
 *    → 能否并入本项目（MIT）：本文件为独立的薄封装签名，仅引用公开 API 类型，可并入
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：原版 RenderPipeline.Builder（withLocation/withVertexShader/withFragmentShader/
 *    withBindGroupLayout/withVertexBinding/withColorTargetState/withSnippet/build）与
 *    NeoForgeRegisterRenderPipelinesEvent.registerPipeline 官方注册路径。
 * 2. 备选：无（官方 API 完整，GAP 判定：不需要登记）。
 * 3. 我们的差异点：本接口只冻结「业务包能拿到什么签名」，真实构建收敛在 bridge 实现（PipelineApi）；
 *    E 线（pipeline/model 纯计算件）消费这些签名做中间表示，不直接 import renderpearl。
 * 4. 许可证核对：本项目 MIT；只调用公开 API 签名，无代码复制。
 * 5. 性能基线：构建期一次性调用，冷路径，无优化需求。
 */
import java.util.List;

import net.minecraft.resources.Identifier;

/**
 * F1 冻结契约：管线构建相关的纯 Java 签名（{@code docs/18-PARALLEL.md} §3 F1）。
 *
 * <p><b>签名 + Javadoc 冻结，无实现</b>——真实构建走 {@link PipelineApi}。
 * E 线（{@code pipeline/model/}）依赖本接口的类型做 stride 计算、缓存键与
 * BindGroupLayout 中间表示；改动必须走 {@link ContractVersion} §3.2 流程。
 *
 * <p>本接口不暴露任何 {@code com.mojang.renderpearl.*} 类型（07 T5 红线）：
 * 原版类型只允许出现在 {@code bridge/} 实现内部。
 */
public interface RenderApi {
    /**
     * 一条已冻结的管线描述（纯数据，跨线消费）。
     *
     * @param location      管线注册位置，如 {@code vkdisp:pipeline/fullscreen}
     * @param vertexShader  顶点着色器标识，如 {@code vkdisp:fullscreen}（资源路径 shaders/<path>.vsh）
     * @param fragmentShader 片元着色器标识（资源路径 shaders/<path>.fsh）
     * @param vertexBindingNames 顶点绑定 0..n 的顶点格式逻辑名（如 "POSITION_COLOR"）；
     *                          与 {@code 04-SPEC.md} §4 的属性表逐项对应，stride 计算由 E 线做
     * @param optional      是否可选管线（true = 编译失败降级 WARN；false = 编译失败硬失败）
     */
    record PipelineSpec(
            Identifier location,
            Identifier vertexShader,
            Identifier fragmentShader,
            List<String> vertexBindingNames,
            boolean optional
    ) {}

    /**
     * 构建期注册一条管线（对应 NeoForge {@code RegisterRenderPipelinesEvent.registerPipeline}）。
     * 实现必须打注册成功/失败埋点（01-DEV-LOOP §5.1），失败绝不静默。
     *
     * @param spec 冻结的管线描述
     * @throws IllegalStateException 管线已注册（location 重复）或必填项缺失
     */
    void registerPipeline(PipelineSpec spec);

    /**
     * 查询某管线是否已注册完成。
     *
     * @param location 管线注册位置
     * @return 已注册返回 true；未注册返回 false（不抛异常，供轮询）
     */
    boolean isRegistered(Identifier location);

    /** 本契约版本（与 {@link ContractVersion#VERSION} 一致，便于跨线断言）。 */
    int contractVersion();
}
