// Sprite outline — GLES 2.0 GLSL ES 1.00 starter source.
precision mediump float;
uniform sampler2D uTexture; uniform vec2 uTexel; uniform vec4 uOutline; varying vec2 vUV;
void main(){ vec4 c=texture2D(uTexture,vUV); float a=max(max(texture2D(uTexture,vUV+vec2(uTexel.x,0.)).a,texture2D(uTexture,vUV-vec2(uTexel.x,0.)).a),max(texture2D(uTexture,vUV+vec2(0.,uTexel.y)).a,texture2D(uTexture,vUV-vec2(0.,uTexel.y)).a)); gl_FragColor=c.a>0.01?c:(a>0.01?uOutline:vec4(0.)); }
