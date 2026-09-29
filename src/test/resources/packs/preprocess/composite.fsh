#version 150
#include "/lib/common.glsl"
#include "lib/local.glsl"

const int shadowMapResolution = 2048; // [512 1024 2048]
#define SHADOW_DARKNESS 0.10 // 阴影浓度 [0.05 0.10 0.20]

#ifdef SHADOW_DARKNESS
uniform float shadowDarkness = SHADOW_DARKNESS;
#endif

void main() {
}
