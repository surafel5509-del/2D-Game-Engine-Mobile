// Texture tint — GLES 2.0 GLSL ES 1.00 starter source.
precision mediump float;
uniform sampler2D uTexture;
uniform vec4 uTint;
varying vec2 vUV;
void main() { gl_FragColor = texture2D(uTexture, vUV) * uTint; }
