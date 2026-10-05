// Animated water ripple — GLES 2.0 GLSL ES 1.00 starter source.
precision mediump float; uniform sampler2D uTexture; uniform float uTime; varying vec2 vUV;
void main(){ vec2 uv=vUV+vec2(sin(vUV.y*28.0+uTime)*.006,cos(vUV.x*22.0-uTime)*.004); vec4 c=texture2D(uTexture,uv); gl_FragColor=vec4(c.rgb*vec3(.82,1.04,1.13),c.a); }
