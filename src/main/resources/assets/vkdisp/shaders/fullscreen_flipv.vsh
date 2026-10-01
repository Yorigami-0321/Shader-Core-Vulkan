#version 330
#extension GL_ARB_separate_shader_objects : require
// vkdisp 全屏三角形顶点着色器（1-v 翻转版，P2.4 帧链接线 ⑤）
// 资源 id：vkdisp:fullscreen_flipv → 由 RenderPipeline withVertexShader(Identifier) 解析到本文件。
//
// ⭐ 实测约定（P-1f，docs/18-PARALLEL.md §10）：**中间目标 → 主目标** 的采样必须 V 翻转。
// P2.4 起 Pass 3 换成包 composite 程序 —— 包片元保持 OF 原语义（用**原始 vUv** 采样，
// 与「中间目标 → 中间目标」口径一致），翻转因此上移到本顶点着色器：
//   本文件 vUv = (uv.x, 1.0 - uv.y) ⇒ 片元 texture(InSampler, vUv)
//   ≡ 旧 blit.vsh 的原始 vUv + blit.fsh 的 texture(InSampler, vec2(vUv.x, 1.0 - vUv.y))
// （数学上同一次坐标变换，只是发生在顶点插值前）。
// 与 fullscreen.vsh 唯一的差别就是最后的 vUv 赋值。

layout(location = 0) out vec2 vUv;
// P4.1.2 驱动层接口链接（PipelineBuilder:204 逐 location 校验，javap 取证）：
// 包 composite 片元的插值输入 = texCoord(vec2) + sunVec/upVec(vec3)，顶点必须在
// location/类型/插值三者上一一对齐，否则 `Vertex shader missing output at location N`。
// sunVec/upVec 真值依赖 timeAngle / gbufferModelView 的 uniform 上传（OfUniformManager
// 缺口，18-PARALLEL §5 P4.1 ⑤ 已登记 sunVec 系 NaN 风险）→ 本子轮按登记口径发
// vec3(0) 占位：dot(0,0)=0 ⇒ sunVisibility=0.5 为有限值（比零矩阵 normalize 出的 NaN 安全）。
// 已知边界：换 varying 集不同的包需按包生成顶点（P4.2+ 待办），未消费的多余顶点输出
// 不参与校验（本文件供 fullscreen/blit/fixture 等无此输入的片元时同样成立）。
layout(location = 1) out vec3 sunVec;
layout(location = 2) out vec3 upVec;

void main() {
    vec2 uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(uv * vec2(2, 2) + vec2(-1, -1), 0, 1);
    vUv = vec2(uv.x, 1.0 - uv.y);
    sunVec = vec3(0.0);
    upVec = vec3(0.0);
}
