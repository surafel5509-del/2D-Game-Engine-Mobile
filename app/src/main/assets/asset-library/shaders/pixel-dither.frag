// Ordered pixel dither — GLES 2.0 GLSL ES 1.00 starter source.
precision mediump float;
uniform sampler2D uTexture; uniform vec2 uResolution; varying vec2 vUV;
void main(){ vec4 c=texture2D(uTexture,vUV); float d=mod(floor(gl_FragCoord.x)+floor(gl_FragCoord.y),2.0)*0.07; gl_FragColor=vec4(floor(c.rgb*5.0+d)/5.0,c.a); }
