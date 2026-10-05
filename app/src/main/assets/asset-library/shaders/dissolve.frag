// Threshold dissolve — GLES 2.0 GLSL ES 1.00 starter source.
precision mediump float; uniform sampler2D uTexture; uniform float uAmount; varying vec2 vUV;
void main(){ vec4 c=texture2D(uTexture,vUV); float n=fract(sin(dot(floor(vUV*128.0),vec2(12.9898,78.233)))*43758.5453); if(n<uAmount) discard; gl_FragColor=c; }
