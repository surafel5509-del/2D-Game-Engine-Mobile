// Retro scanlines — GLES 2.0 GLSL ES 1.00 starter source.
precision mediump float; uniform sampler2D uTexture; uniform float uStrength; varying vec2 vUV;
void main(){ vec4 c=texture2D(uTexture,vUV); float s=sin(vUV.y*720.0*3.14159)*.5+.5; gl_FragColor=vec4(c.rgb*(1.0-uStrength*(1.0-s)),c.a); }
