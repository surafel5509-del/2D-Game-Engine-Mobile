package com.sengine.engine.render

import android.opengl.GLES20
import com.sengine.engine.core.Light
import com.sengine.engine.core.MeshRenderer
import com.sengine.engine.core.Scene
import com.sengine.engine.math.Mat4
import java.nio.FloatBuffer
import kotlin.math.sqrt

/** Forward Blinn-Phong mesh renderer with gradient sky, fog, 1 directional + 4 point lights. */
class Renderer3D {
    lateinit var shaders: ShaderLibrary
    private var skyProg = 0
    private var skyPos = 0; private var skyInv = 0; private var skyTop = 0; private var skyHorizon = 0; private var skyGround = 0
    private lateinit var fullscreen: FloatBuffer

    private val mvp = FloatArray(16)
    private val inv = FloatArray(16)
    private val normalMat = FloatArray(16)

    // per-frame lighting
    private val ambient = FloatArray(3)
    private val dirDir = floatArrayOf(-0.4f, -1f, -0.3f)
    private val dirColor = FloatArray(3)
    private val pointPos = FloatArray(16)
    private val pointColor = FloatArray(12)
    private val fogColor = FloatArray(3)
    private val fog = FloatArray(3)
    lateinit var view: View3D

    var drawCalls = 0
    var time = 0f
    var resW = 1f
    var resH = 1f

    fun init(shaders: ShaderLibrary) {
        this.shaders = shaders
        skyProg = GL.compile(SKY_VS, SKY_FS)
        skyPos = GLES20.glGetAttribLocation(skyProg, "aPos")
        skyInv = GLES20.glGetUniformLocation(skyProg, "uInvVP")
        skyTop = GLES20.glGetUniformLocation(skyProg, "uTop")
        skyHorizon = GLES20.glGetUniformLocation(skyProg, "uHorizon")
        skyGround = GLES20.glGetUniformLocation(skyProg, "uGround")
        fullscreen = GL.floatBuffer(8)
        fullscreen.put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0)
    }

    fun drawSky(v: View3D, top: Int, horizon: Int) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(skyProg)
        GLES20.glUniformMatrix4fv(skyInv, 1, false, v.invViewProj, 0)
        GLES20.glUniform3f(skyTop, GL.r(top), GL.g(top), GL.b(top))
        GLES20.glUniform3f(skyHorizon, GL.r(horizon), GL.g(horizon), GL.b(horizon))
        GLES20.glUniform3f(skyGround, GL.r(horizon) * 0.45f, GL.g(horizon) * 0.45f, GL.b(horizon) * 0.5f)
        fullscreen.position(0)
        GLES20.glEnableVertexAttribArray(skyPos)
        GLES20.glVertexAttribPointer(skyPos, 2, GLES20.GL_FLOAT, false, 0, fullscreen)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(skyPos)
        GLES20.glDepthMask(true)
        drawCalls++
    }

    /** Collects lights from the scene. With no lights, a default sun is used. */
    fun setupLights(scene: Scene, v: View3D) {
        view = v
        val amb = scene.ambient
        ambient[0] = GL.r(amb); ambient[1] = GL.g(amb); ambient[2] = GL.b(amb)
        fogColor[0] = GL.r(scene.fogColor); fogColor[1] = GL.g(scene.fogColor); fogColor[2] = GL.b(scene.fogColor)
        fog[0] = scene.fogStart; fog[1] = scene.fogEnd; fog[2] = if (scene.fog) 1f else 0f
        var hasDir = false
        dirColor.fill(0f)
        pointPos.fill(0f); pointColor.fill(0f)
        // default: sun from above
        dirDir[0] = -0.4f; dirDir[1] = -1f; dirDir[2] = -0.3f
        var points = 0
        // point lights: nearest 4 to camera
        val pts = ArrayList<Pair<Float, Pair<FloatArray, Light>>>()
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            val l = go.get<Light>() ?: continue
            val w = go.world3
            if (l.kind == 0) {
                if (!hasDir) {
                    val d = Mat4.dir(w, 0f, 0f, -1f)
                    val len = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]).coerceAtLeast(1e-6f)
                    dirDir[0] = d[0] / len; dirDir[1] = d[1] / len; dirDir[2] = d[2] / len
                    dirColor[0] = GL.r(l.color) * l.intensity; dirColor[1] = GL.g(l.color) * l.intensity; dirColor[2] = GL.b(l.color) * l.intensity
                    hasDir = true
                }
            } else {
                pts.add(v.distanceTo(w[12], w[13], w[14]) to (floatArrayOf(w[12], w[13], w[14]) to l))
            }
        }
        val anyLight = hasDir || pts.isNotEmpty()
        if (!anyLight) { dirColor[0] = 0.85f; dirColor[1] = 0.83f; dirColor[2] = 0.8f }
        for ((_, pl) in pts.sortedBy { it.first }) {
            if (points >= 4) break
            val (p, l) = pl
            pointPos[points * 4] = p[0]; pointPos[points * 4 + 1] = p[1]; pointPos[points * 4 + 2] = p[2]; pointPos[points * 4 + 3] = l.range
            pointColor[points * 3] = GL.r(l.color) * l.intensity; pointColor[points * 3 + 1] = GL.g(l.color) * l.intensity; pointColor[points * 3 + 2] = GL.b(l.color) * l.intensity
            points++
        }
    }

    fun drawMesh(mesh: Mesh, model: FloatArray, mr: MeshRenderer, tex: Tex?, program: MeshProgram?) {
        val p = program ?: shaders.defaultMesh
        GLES20.glUseProgram(p.id)
        Mat4.mul(mvp, view.viewProj, model)
        Mat4.invert(inv, model)
        Mat4.transpose(normalMat, inv)
        GLES20.glUniformMatrix4fv(p.uMVP, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(p.uModel, 1, false, model, 0)
        GLES20.glUniformMatrix4fv(p.uNormalMat, 1, false, normalMat, 0)
        GLES20.glUniform4f(p.uColor, GL.r(mr.color), GL.g(mr.color), GL.b(mr.color), GL.a(mr.color))
        GLES20.glUniform1f(p.uTiling, mr.tiling)
        GLES20.glUniform3f(p.uCamPos, view.eye[0], view.eye[1], view.eye[2])
        GLES20.glUniform3fv(p.uAmbient, 1, ambient, 0)
        GLES20.glUniform3fv(p.uDirDir, 1, dirDir, 0)
        GLES20.glUniform3fv(p.uDirColor, 1, dirColor, 0)
        GLES20.glUniform4fv(p.uPointPos, 4, pointPos, 0)
        GLES20.glUniform3fv(p.uPointColor, 4, pointColor, 0)
        GLES20.glUniform1f(p.uSpec, mr.specular)
        GLES20.glUniform1f(p.uShine, mr.shininess)
        GLES20.glUniform1f(p.uEmission, mr.emission)
        GLES20.glUniform1f(p.uUnlit, if (mr.unlit) 1f else 0f)
        GLES20.glUniform3fv(p.uFogColor, 1, fogColor, 0)
        GLES20.glUniform3fv(p.uFog, 1, fog, 0)
        if (p.uTime >= 0) GLES20.glUniform1f(p.uTime, time)
        if (p.uParam >= 0) GLES20.glUniform1f(p.uParam, mr.shaderParam)
        if (p.uResolution >= 0) GLES20.glUniform2f(p.uResolution, resW, resH)
        if (tex != null) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex.id)
            if (mr.tiling != 1f && isPot(tex.w) && isPot(tex.h)) {
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
            }
            GLES20.glUniform1i(p.uTex, 0)
            GLES20.glUniform1f(p.uUseTex, 1f)
        } else GLES20.glUniform1f(p.uUseTex, 0f)
        val b = mesh.buffer
        b.position(0)
        GLES20.glEnableVertexAttribArray(p.aPos)
        GLES20.glVertexAttribPointer(p.aPos, 3, GLES20.GL_FLOAT, false, 32, b)
        if (p.aNormal >= 0) {
            b.position(3)
            GLES20.glEnableVertexAttribArray(p.aNormal)
            GLES20.glVertexAttribPointer(p.aNormal, 3, GLES20.GL_FLOAT, false, 32, b)
        }
        if (p.aUV >= 0) {
            b.position(6)
            GLES20.glEnableVertexAttribArray(p.aUV)
            GLES20.glVertexAttribPointer(p.aUV, 2, GLES20.GL_FLOAT, false, 32, b)
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.vertexCount)
        GLES20.glDisableVertexAttribArray(p.aPos)
        if (p.aNormal >= 0) GLES20.glDisableVertexAttribArray(p.aNormal)
        if (p.aUV >= 0) GLES20.glDisableVertexAttribArray(p.aUV)
        drawCalls++
    }

    private fun isPot(n: Int) = n > 0 && (n and (n - 1)) == 0

    companion object {
        const val SKY_VS = """
attribute vec2 aPos;
varying vec2 vNdc;
void main() { vNdc = aPos; gl_Position = vec4(aPos, 0.9999, 1.0); }
"""
        const val SKY_FS = """
precision mediump float;
varying vec2 vNdc;
uniform mat4 uInvVP;
uniform vec3 uTop;
uniform vec3 uHorizon;
uniform vec3 uGround;
void main() {
  vec4 a = uInvVP * vec4(vNdc, -1.0, 1.0);
  vec4 b = uInvVP * vec4(vNdc, 1.0, 1.0);
  vec3 d = normalize(b.xyz / b.w - a.xyz / a.w);
  vec3 c;
  if (d.y >= 0.0) c = mix(uHorizon, uTop, pow(d.y, 0.6));
  else c = mix(uHorizon, uGround, clamp(-d.y * 4.0, 0.0, 1.0));
  gl_FragColor = vec4(c, 1.0);
}
"""
    }
}
