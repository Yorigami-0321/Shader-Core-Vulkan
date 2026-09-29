#version 330
#extension GL_ARB_separate_shader_objects : require
// vkdisp 几何顶点着色器（P3 前置：真实顶点缓冲 + 深度剔除验证）
// 属性名必须与 VertexFormat.Builder.addAttribute("Position"/"Color") 完全一致（04-SPEC §4 字面一致要求）。

// ⚠️ 顶点属性必须显式给 location：原版用 ARB_separate_shader_objects，SPIR-V 要求用户输入/输出有显式 location。
// 实测报错原文（未加时）：vkdisp:geometry:6: error: 'location' : SPIR-V requires location for user input/output
// location 序号与 VertexFormat 里 addAttribute 的顺序一致（Position=0，Color=1）。
layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;

// 两条管线共用本文件、以 SHADOW_MAP_PASS define 区分（块名必须与各自 BindGroupLayout 一致）：
//   阴影贴图 pass：需要光空间矩阵；世界视图 pass：需要真实透视相机矩阵。
#ifdef SHADOW_MAP_PASS
layout(std140) uniform LightMatrix {
    mat4 uLight;
};
#else
layout(std140) uniform Camera {
    mat4 uCamera;
};
#endif

layout(location = 0) out vec4 vColor;
layout(location = 1) out vec3 vWorldPos;

void main() {
    // 顶点数据是「世界」坐标（z 由顶点数据给定：0.3 近 / 0.7 远）。世界坐标两种情况都输出，
    // 供阴影采样（shadowed.fsh）回投光空间；裁剪空间按 pass 分支选择：
#ifdef SHADOW_MAP_PASS
    gl_Position = uLight * vec4(Position, 1.0);
#else
    gl_Position = uCamera * vec4(Position, 1.0);
#endif
    vWorldPos = Position;
    vColor = Color;
}
