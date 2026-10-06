package com.sengine.engine.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.ETC1Util
import android.opengl.GLES20
import com.sengine.project.Project
import java.nio.ByteBuffer
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** A GL texture plus the metadata the renderer needs. */
class Tex(val id: Int, val w: Int, val h: Int, var name: String = "", var etc1: Boolean = false)

/** A rectangular region inside a texture (atlas / sprite sheet cell). */
class TexRegion(
    val tex: Tex,
    /** Normalised UV rect (v0 is the top edge). */
    val u0: Float, val v0: Float, val u1: Float, val v1: Float,
    /** Pixel rect inside the texture (for pixel snapping and slicing). */
    val px: Int, val py: Int, val pw: Int, val ph: Int
) {
    val width get() = pw
    val height get() = ph
}

/** Packed atlas produced by [TextureAtlas.pack]. */
class AtlasData(val texture: String, val regions: LinkedHashMap<String, TexRegion>) {
    fun region(name: String): TexRegion? = regions[name]

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("texture", texture)
        val r = JSONObject()
        for ((n, reg) in regions) {
            r.put(n, JSONObject().apply {
                put("x", reg.px); put("y", reg.py); put("w", reg.pw); put("h", reg.ph)
            })
        }
        o.put("regions", r)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject, tex: Tex): AtlasData {
            val map = LinkedHashMap<String, TexRegion>()
            val r = o.optJSONObject("regions")
            if (r != null) {
                for (key in r.keys()) {
                    val e = r.optJSONObject(key) ?: continue
                    map[key] = region(tex, e.optInt("x"), e.optInt("y"), e.optInt("w"), e.optInt("h"))
                }
            }
            return AtlasData(o.optString("texture", tex.name), map)
        }

        fun region(tex: Tex, x: Int, y: Int, w: Int, h: Int) = TexRegion(
            tex,
            x.toFloat() / tex.w, 1f - y.toFloat() / tex.h,
            (x + w).toFloat() / tex.w, 1f - (y + h).toFloat() / tex.h,
            x, y, w, h
        )
    }
}

/**
 * Texture cache and 2D asset loader.
 *
 * Loads PNG/JPG/WebP/BMP through [BitmapFactory] and SVG through the built-in rasteriser, keeps
 * per-asset GL textures (with sprite-sheet regions), optionally packs many small images into a
 * single atlas to cut draw calls, and exposes upload statistics for the profiler.
 *
 * Must only be called on the GL thread.
 */
class TextureCache(private val project: Project) {
    private val images = HashMap<String, Tex?>()
    private val stamps = HashMap<String, Long>()
    private val svgSizes = HashMap<String, Int>()
    private val regionCache = HashMap<String, TexRegion?>()
    private val atlasCache = HashMap<String, AtlasData?>()
    private var whiteTex: Tex? = null
    private var fallback: Tex? = null

    /** Statistics for the profiler / asset diagnostics. */
    var textureCount = 0; private set
    var uploadedBytes = 0L; private set
    var uploadCount = 0; private set
    var failedAssets = ArrayList<String>(); private set

    /** Prefer power-of-two sizes (faster on low-end mobile GPUs). */
    var preferPowerOfTwo = true
    /** Generate mipmaps for large textures (nicer downscaling, more memory). */
    var mipmapsForLargeTextures = true
    /** Try ETC1 for opaque textures - halves GPU memory on devices that support it. */
    var compressOpaque = false
    /** Point filtering by default: pixel art must stay crisp. */
    var defaultNearest = true

    /** Square texture used for untextured shapes, UI panels, particles and lines. */
    fun white(): Tex {
        whiteTex?.let { return it }
        val px = IntArray(4) { 0xFFFFFFFF.toInt() }
        val tex = Tex(GL.uploadPixels(2, 2, px, nearest = true), 2, 2, "white")
        whiteTex = tex
        textureCount++
        return tex
    }

    /** Magenta/black checker used when an asset is missing (never silently invisible). */
    fun missing(): Tex {
        fallback?.let { return it }
        val px = IntArray(8 * 8)
        for (y in 0 until 8) for (x in 0 until 8) {
            px[y * 8 + x] = if ((x / 4 + y / 4) % 2 == 0) 0xFFFF00FF.toInt() else 0xFF202020.toInt()
        }
        val tex = Tex(GL.uploadPixels(8, 8, px, nearest = true), 8, 8, "missing")
        fallback = tex
        textureCount++
        return tex
    }

    /** Uploads the built-in textures; call once with a current GL context. */
    fun initGl() {
        white()
        missing()
    }

    /** Releases every GL texture (surface destroyed / project closed). */
    fun release() {
        for (t in images.values) {
            val tex = t ?: continue
            if (tex.id != 0) GL.deleteTexture(tex.id)
        }
        images.clear()
        stamps.clear()
        regionCache.clear()
        atlasCache.clear()
        whiteTex = null
        fallback = null
        textureCount = 0
        uploadedBytes = 0L
        uploadCount = 0
    }

    fun clear() {
        for (t in images.values) t?.let { GL.deleteTexture(it.id) }
        images.clear(); stamps.clear(); regionCache.clear(); atlasCache.clear()
        whiteTex?.let { GL.deleteTexture(it.id) }; whiteTex = null
        fallback?.let { GL.deleteTexture(it.id) }; fallback = null
        textureCount = 0
        uploadedBytes = 0
        uploadCount = 0
    }

    /** Reloads assets whose source file changed while the editor is running. */
    fun refreshChanged() {
        val stale = images.keys.filter { name ->
            val f = project.assetFile(name)
            f.exists() && stamps[name] != f.lastModified()
        }
        for (name in stale) {
            images[name]?.let { GL.deleteTexture(it.id) }
            images.remove(name); stamps.remove(name); regionCache.clear(); atlasCache.clear()
        }
    }

    // ------------------------------------------------------------------ loading

    fun image(name: String): Tex? {
        if (name.isBlank()) return null
        val file = project.assetFile(name)
        if (!file.exists()) {
            if (failedAssets.size < 64 && !failedAssets.contains(name)) failedAssets.add(name)
            return null
        }
        val stamp = file.lastModified()
        if (images.containsKey(name)) {
            if (stamps[name] == stamp) return images[name]
            images[name]?.let { GL.deleteTexture(it.id); textureCount-- }
            images.remove(name)
        }
        var bmp: Bitmap? = try {
            when (file.extension.lowercase()) {
                "svg" -> Svg.rasterize(file.inputStream(), svgSizes[name] ?: 0, svgSizes[name] ?: 0)
                else -> BitmapFactory.decodeFile(file.absolutePath)
            }
        } catch (t: Throwable) {
            android.util.Log.w(GL.TAG, "Failed to decode asset $name: ${t.message}")
            null
        }
        if (bmp == null) {
            if (failedAssets.size < 64 && !failedAssets.contains(name)) failedAssets.add(name)
            images[name] = null
            stamps[name] = stamp
            return null
        }
        if (preferPowerOfTwo && !isPowerOfTwo(bmp.width) || (preferPowerOfTwo && !isPowerOfTwo(bmp.height))) {
            bmp = padToPowerOfTwo(bmp)
        }
        val nearest = defaultNearest && maxOf(bmp.width, bmp.height) <= 192
        val tex: Tex = if (compressOpaque && isOpaque(bmp) && ETC1Util.isETC1Supported() && nearest) {
            uploadEtc1(bmp, name) ?: upload(bmp, name, nearest = true)
        } else {
            upload(bmp, name, nearest = nearest, mipmaps = mipmapsForLargeTextures && !nearest)
        }
        bmp.recycle()
        images[name] = tex
        stamps[name] = stamp
        uploadedBytes += tex.w.toLong() * tex.h * 4
        uploadCount++
        android.util.Log.i(GL.TAG, "Texture loaded: $name ${tex.w}x${tex.h}")
        return tex
    }

    /** Full-texture region helper. */
    fun fullRegion(name: String): TexRegion? {
        val t = image(name) ?: return null
        return TexRegion(t, 0f, 1f, 1f, 0f, 0, 0, t.w, t.h)
    }

    /**
     * Resolves a region reference:
     * - `sprite.png`            -> whole texture
     * - `sheet.png#3`           -> 4th cell of a 4x4 sheet
     * - `sheet.png#2,1,32,32`   -> pixel rect inside the texture
     * - `atlas.atlas#hero_idle` -> named region from a packed atlas
     */
    fun region(ref: String): TexRegion? {
        if (ref.isBlank()) return null
        regionCache[ref]?.let { return it }
        val result = resolveRegion(ref)
        regionCache[ref] = result
        return result
    }

    private fun resolveRegion(ref: String): TexRegion? {
        if (ref.endsWith(".atlas") || ref.endsWith(".json")) {
            val data = atlas(ref) ?: return null
            val regionName = ref.substringBefore('#')
            return data.regions.values.firstOrNull() ?:
                data.region(regionName)
        }
        val file = ref.substringBefore('#')
        val hash = ref.substringAfter('#', "")
        val tex = image(file) ?: return null
        if (hash.isBlank()) return TexRegion(tex, 0f, 1f, 1f, 0f, 0, 0, tex.w, tex.h)
        if (hash.contains(',')) {
            val p = hash.split(',').mapNotNull { it.trim().toIntOrNull() }
            if (p.size >= 4) return AtlasData.region(tex, p[0], p[1], max(1, p[2]), max(1, p[3]))
            return TexRegion(tex, 0f, 1f, 1f, 0f, 0, 0, tex.w, tex.h)
        }
        // grid frame index: a square grid, row-major, counted from the top-left
        val index = hash.toIntOrNull() ?: return TexRegion(tex, 0f, 1f, 1f, 0f, 0, 0, tex.w, tex.h)
        val cell = cellSize(tex)
        if (cell <= 0) return TexRegion(tex, 0f, 1f, 1f, 0f, 0, 0, tex.w, tex.h)
        val cols = max(1, tex.w / cell)
        val cx = index % cols
        val cy = index / cols
        return AtlasData.region(tex, cx * cell, cy * cell, cell, cell)
    }

    private fun cellSize(tex: Tex): Int {
        // assume square cells; pick the largest cell size that divides the texture evenly
        for (size in intArrayOf(tex.h, tex.w, 64, 48, 32, 24, 16, 8)) {
            if (size <= 0) continue
            if (tex.w % size == 0 && tex.h % size == 0) return size
        }
        return minOf(tex.w, tex.h)
    }

    /** Loads an atlas asset (`.atlas`) and its regions. */
    fun atlas(name: String): AtlasData? {
        if (atlasCache.containsKey(name)) return atlasCache[name]
        val file = project.assetFile(name)
        if (!file.exists()) { atlasCache[name] = null; return null }
        val data = try {
            val json = JSONObject(file.readText())
            val texName = json.optString("texture", name.substringBeforeLast('.') + ".png")
            val tex = image(texName) ?: return null.also { atlasCache[name] = null }
            AtlasData.fromJson(json, tex)
        } catch (t: Throwable) {
            null
        }
        atlasCache[name] = data
        return data
    }

    /** The atlas a sprite is drawn from (for batching) - null means "own texture". */
    fun atlasOf(ref: String): AtlasData? = if (ref.endsWith(".atlas")) atlas(ref) else null

    // ------------------------------------------------------------------ upload helpers

    private fun upload(bmp: Bitmap, name: String, nearest: Boolean, mipmaps: Boolean = false): Tex {
        val id = GL.uploadBitmap(bmp, nearest, mipmaps)
        textureCount++
        return Tex(id, bmp.width, bmp.height, name)
    }

    /**
     * Uploads an opaque bitmap as an ETC1 compressed texture. ETC1 halves GPU memory on devices
     * that support it (which is every modern Android GPU); textures with an alpha channel are
     * uploaded uncompressed instead because ETC1 has no alpha.
     */
    private fun uploadEtc1(bmp: Bitmap, name: String): Tex? = try {
        val pixels = ByteBuffer.allocateDirect(bmp.width * bmp.height * 4).order(java.nio.ByteOrder.nativeOrder())
        bmp.copyPixelsToBuffer(pixels)
        pixels.position(0)
        val compressed = ETC1Util.compressTexture(pixels, bmp.width, bmp.height, bmp.width * 4, 0)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        ETC1Util.loadTexture(GLES20.GL_TEXTURE_2D, 0, 0, GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, compressed)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        textureCount++
        uploadedBytes += (bmp.width.toLong() * bmp.height / 2)
        Tex(ids[0], bmp.width, bmp.height, name, etc1 = true)
    } catch (t: Throwable) {
        android.util.Log.w(GL.TAG, "ETC1 compression failed for $name: ${t.message}")
        null
    }

    private fun isPowerOfTwo(v: Int) = v > 0 && (v and (v - 1)) == 0

    private fun padToPowerOfTwo(bmp: Bitmap): Bitmap {
        val w = nextPowerOfTwo(bmp.width)
        val h = nextPowerOfTwo(bmp.height)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).drawBitmap(bmp, 0f, 0f, null)
        bmp.recycle()
        return out
    }

    private fun nextPowerOfTwo(v: Int): Int {
        var p = 1
        while (p < v) p = p shl 1
        return p
    }

    private fun isOpaque(bmp: Bitmap): Boolean {
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                if (((bmp.getPixel(x, y) ushr 24) and 0xFF) != 255) return false
                x += 3
            }
            y += 3
        }
        return true
    }

    /** Assets that failed to load (asset diagnostics in the profiler). */
    fun clearFailures() = failedAssets.clear()
}

/**
 * Runtime texture atlas packer (shelf/row packing, no rotation so UVs stay axis aligned).
 * Used by the asset pipeline to build `.atlas` files that collapse many sprites into one draw call.
 */
object TextureAtlas {

    class Entry(val name: String, val bitmap: Bitmap)

    class Result(val bitmap: Bitmap, val regions: LinkedHashMap<String, IntArray>, val texture: String)

    /** Packs all bitmaps into a single texture no larger than [maxSize]. */
    fun pack(entries: List<Entry>, textureName: String, maxSize: Int = 2048, padding: Int = 1): Result? {
        if (entries.isEmpty()) return null
        val sorted = entries.sortedByDescending { it.bitmap.height }
        var size = 64
        while (size <= maxSize) {
            val attempt = tryPack(sorted, size, padding)
            if (attempt != null) return Result(attempt.bitmap, regionsOf(attempt, sorted), textureName)
            size *= 2
        }
        return null
    }

    private fun regionsOf(packed: PackedAtlas, sorted: List<Entry>): LinkedHashMap<String, IntArray> {
        val map = LinkedHashMap<String, IntArray>()
        for (p in packed.placements) map[p.name] = intArrayOf(p.x, p.y, p.w, p.h)
        return map
    }

    private class Placement(val name: String, val x: Int, val y: Int, val w: Int, val h: Int)
    private class PackedAtlas(val bitmap: Bitmap, val placements: List<Placement>)

    private fun tryPack(entries: List<Entry>, size: Int, padding: Int): PackedAtlas? {
        var x = padding
        var y = padding
        var rowHeight = 0
        val placements = ArrayList<Placement>()
        for (e in entries) {
            val w = e.bitmap.width
            val h = e.bitmap.height
            if (w + padding > size || h + padding > size) return null
            if (x + w + padding > size) {
                x = padding
                y += rowHeight + padding
                rowHeight = 0
            }
            if (y + h + padding > size) return null
            placements.add(Placement(e.name, x, y, w, h))
            x += w + padding
            rowHeight = maxOf(rowHeight, h)
        }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        for ((i, p) in placements.withIndex()) {
            canvas.drawBitmap(entries[i].bitmap, p.x.toFloat(), p.y.toFloat(), null)
        }
        return PackedAtlas(bmp, placements)
    }

    /** Fallback for very large sets: one texture row per image, no packing (still one draw call). */
    fun buildStrip(entries: List<Entry>): Result? {
        if (entries.isEmpty()) return null
        val width = entries.maxOf { it.bitmap.width }
        val height = entries.sumOf { it.bitmap.height }
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val regions = LinkedHashMap<String, IntArray>()
        var y = 0
        for (e in entries) {
            canvas.drawBitmap(e.bitmap, 0f, y.toFloat(), null)
            regions[e.name] = intArrayOf(0, y, e.bitmap.width, e.bitmap.height)
            y += e.bitmap.height
        }
        return Result(bmp, regions, "strip")
    }
}
