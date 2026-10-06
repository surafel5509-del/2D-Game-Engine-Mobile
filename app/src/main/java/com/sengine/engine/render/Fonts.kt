package com.sengine.engine.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.sengine.project.Project
import java.io.File
import kotlin.math.ceil
import kotlin.math.max

/** One glyph inside a [FontAtlas]. */
class Glyph(
    val ch: Char,
    val region: TexRegion,
    /** Horizontal pen offset. */
    val xOffset: Float,
    /** Vertical offset from the baseline (negative moves up). */
    val yOffset: Float,
    val xAdvance: Float
)

/**
 * A rasterised font: glyph atlas + metrics. Two sources are supported:
 *  - system/TrueType fonts (any TTF in the project or a platform typeface) rasterised on demand,
 *  - BMFont `.fnt` descriptor files (text format) shipped with the game assets.
 */
class FontAtlas(
    val name: String,
    val tex: Tex,
    val glyphs: HashMap<Char, Glyph>,
    val sizePx: Int,
    val lineHeight: Float,
    val ascent: Float,
    val descent: Float,
    val spaceAdvance: Float,
    val bold: Boolean = false
) {
    /** Fallback advance for glyphs missing from the atlas. */
    val defaultAdvance: Float get() = spaceAdvance

    fun glyph(c: Char): Glyph? = glyphs[c] ?: glyphs['?'] ?: glyphs['#']

    /** Width of [text] in atlas pixels at the atlas size. */
    fun measure(text: String, letterSpacing: Float = 0f): Float {
        var w = 0f
        for (c in text) {
            if (c == '\n') continue
            val g = glyph(c)
            w += (g?.xAdvance ?: defaultAdvance) + letterSpacing
        }
        return w
    }

    fun lineHeightWith(spacing: Float) = lineHeight * spacing
}

/**
 * Font cache: rasterises fonts into glyph atlases with Android's text engine (real hinting and
 * kerning) and lays text out into positioned glyph quads for the batched 2D renderer.
 */
class FontCache(private val project: Project, private val textures: TextureCache) {

    private val atlases = HashMap<String, FontAtlas>()

    /** Baked font atlases, cleared with the texture cache. */
    fun clear() = atlases.clear()

    private val GLYPH_TOP_PAD = -1f

    /** The glyphs baked per atlas; ASCII + Latin-1 covers Western European text. */
    private val charset: CharArray = buildCharset()

    private fun buildCharset(): CharArray {
        val list = ArrayList<Char>(256)
        for (c in 32..126) list.add(c.toChar())
        for (c in 160..255) list.add(c.toChar())
        for (c in charArrayOf('°', '•', '…', '—', '–', '“', '”', '‘', '’', '€', '✓', '→', '←', '↑', '↓')) list.add(c)
        return list.toCharArray()
    }

    /**
     * Returns the atlas for a font asset reference:
     *  - "" / "default"        -> platform sans serif
     *  - "Roboto"              -> platform typeface by name
     *  - "fonts/ui.ttf"        -> TTF shipped in the project
     *  - "fonts/pixel.fnt"     -> BMFont descriptor + its page texture
     */
    fun atlas(font: String, sizePx: Int, bold: Boolean = false): FontAtlas {
        val size = sizePx.coerceIn(6, 256)
        val key = "$font|$size|$bold"
        atlases[key]?.let { return it }
        val built = if (font.endsWith(".fnt")) bitmapFont(font, size) ?: systemFont(null, size, bold)
        else systemFont(font.takeIf { it.isNotBlank() && it != "default" }, size, bold)
        atlases[key] = built
        return built
    }

    // ------------------------------------------------------------------ TTF rasterisation

    private fun systemFont(fontRef: String?, sizePx: Int, bold: Boolean): FontAtlas {
        val typeface = resolveTypeface(fontRef, bold)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.typeface = typeface
        paint.color = Color.WHITE
        paint.textSize = sizePx.toFloat()
        val fm = paint.fontMetrics
        val lineHeight = ceil(fm.descent - fm.ascent) + 2f

        // measure a cell then pack the glyphs into a square-ish atlas, growing if needed
        var atlasSize = 128
        var placement: HashMap<Char, IntArray>? = null
        while (atlasSize <= 2048) {
            val p = packGlyphs(paint, atlasSize, lineHeight)
            if (p != null) { placement = p; break }
            atlasSize *= 2
        }
        if (placement == null) placement = packGlyphs(paint, 2048, lineHeight) ?: HashMap()

        val bmp = Bitmap.createBitmap(atlasSize, atlasSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT)
        paint.color = Color.WHITE
        val glyphs = HashMap<Char, Glyph>()
        val bounds = android.graphics.Rect()
        for (c in charset) {
            val rect = placement[c] ?: continue
            val txt = c.toString()
            // the ascent line of every cell sits at cellTop + 1, so glyphs line up perfectly
            canvas.drawText(txt, rect[0].toFloat() + 1f, rect[1] + 1f - fm.ascent, paint)
            val region = AtlasData.region(Tex(0, atlasSize, atlasSize, "font"), rect[0], rect[1], rect[2], rect[3])
            glyphs[c] = Glyph(c, region, 1f, GLYPH_TOP_PAD, paint.measureText(txt))
        }
        val tex = Tex(GL.uploadBitmap(bmp, nearest = false, mipmaps = false), atlasSize, atlasSize, "font:$fontRef:$sizePx")
        bmp.recycle()
        // rebind the regions to the real texture
        val finalGlyphs = HashMap<Char, Glyph>(glyphs.size)
        for ((c, g) in glyphs) {
            finalGlyphs[c] = Glyph(c, AtlasData.region(tex, g.region.px, g.region.py, g.region.pw, g.region.ph), g.xOffset, g.yOffset, g.xAdvance)
        }
        android.util.Log.i(GL.TAG, "Font baked: ${fontRef ?: "default"} ${sizePx}px ${tex.w}x${tex.h} (${finalGlyphs.size} glyphs)")
        return FontAtlas(fontRef ?: "default", tex, finalGlyphs, sizePx, lineHeight, -fm.ascent, fm.descent, paint.measureText(" "))
    }

    private fun resolveTypeface(fontRef: String?, bold: Boolean): Typeface {
        if (!fontRef.isNullOrBlank()) {
            val file = project.assetFile(fontRef)
            if (file.exists() && (fontRef.endsWith(".ttf") || fontRef.endsWith(".otf"))) {
                try {
                    return Typeface.createFromFile(file.absolutePath)
                } catch (t: Throwable) {
                    android.util.Log.w(GL.TAG, "TTF load failed for $fontRef: ${t.message}")
                }
            }
            val named = try { Typeface.create(fontRef, if (bold) Typeface.BOLD else Typeface.NORMAL) } catch (t: Throwable) { null }
            if (named != null) return named
        }
        return Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    /** Shelf-packs glyph cells; returns null when the atlas is too small. */
    private fun packGlyphs(paint: Paint, atlasSize: Int, cellHeight: Float): HashMap<Char, IntArray>? {
        val out = HashMap<Char, IntArray>()
        val pad = 1
        var x = pad
        var y = pad
        var rowH = 0
        val h = max(1, cellHeight.toInt() + 2)
        for (c in charset) {
            val txt = c.toString()
            val w = max(1, ceil(paint.measureText(txt)).toInt() + 3)
            if (x + w + pad > atlasSize) {
                x = pad
                y += rowH + pad
                rowH = 0
            }
            if (y + h + pad > atlasSize) return null
            out[c] = intArrayOf(x, y, w, h)
            x += w + pad
            rowH = max(rowH, h)
        }
        return out
    }

    // ------------------------------------------------------------------ BMFont .fnt

    private class BmChar(val ch: Char, val rect: IntArray, val xOffset: Float, val yOffset: Float, val xAdvance: Float)

    /** Parses a BMFont text descriptor: `char id=65 x=0 y=0 width=8 height=10 xoffset=0 yoffset=0 xadvance=9`. */
    private fun bitmapFont(fntRef: String, sizePx: Int): FontAtlas? {
        val file = project.assetFile(fntRef)
        if (!file.exists()) return null
        return try {
            val lines = file.readLines()
            var page = fntRef.substringBeforeLast('.') + ".png"
            var declaredLineHeight = 0f
            var base = 0f
            val chars = ArrayList<BmChar>()
            for (line in lines) {
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("page ") -> {
                        val f = trimmed.substringAfter("file=", "").trim().trim('"')
                        if (f.isNotEmpty()) page = f.substringAfterLast('/')
                    }
                    trimmed.startsWith("common ") -> {
                        val m = parseAttrs(trimmed.substringAfter("common "))
                        declaredLineHeight = m["lineHeight"]?.toFloatOrNull() ?: 0f
                        base = m["base"]?.toFloatOrNull() ?: 0f
                    }
                    trimmed.startsWith("char ") -> {
                        val m = parseAttrs(trimmed.substringAfter("char "))
                        val id = m["id"]?.toIntOrNull() ?: continue
                        chars.add(
                            BmChar(
                                id.toChar(),
                                intArrayOf(
                                    m["x"]?.toIntOrNull() ?: 0, m["y"]?.toIntOrNull() ?: 0,
                                    max(1, m["width"]?.toIntOrNull() ?: 0), max(1, m["height"]?.toIntOrNull() ?: 0)
                                ),
                                m["xoffset"]?.toFloatOrNull() ?: 0f,
                                m["yoffset"]?.toFloatOrNull() ?: 0f,
                                m["xadvance"]?.toFloatOrNull() ?: 0f
                            )
                        )
                    }
                }
            }
            if (chars.isEmpty()) return null
            val tex = textures.image(page) ?: return null
            val nativeSize = if (declaredLineHeight > 0f) declaredLineHeight else sizePx.toFloat()
            val scale = if (sizePx > 0) sizePx.toFloat() / nativeSize else 1f
            val glyphs = HashMap<Char, Glyph>()
            for (bc in chars) {
                glyphs[bc.ch] = Glyph(
                    bc.ch, AtlasData.region(tex, bc.rect[0], bc.rect[1], bc.rect[2], bc.rect[3]),
                    bc.xOffset * scale,
                    ((bc.yOffset - base) * scale) + GLYPH_TOP_PAD,
                    bc.xAdvance * scale
                )
            }
            val lineHeight = (if (declaredLineHeight > 0f) declaredLineHeight else sizePx.toFloat()) * scale
            FontAtlas(
                fntRef, tex, glyphs, sizePx, lineHeight, base * scale, (nativeSize - base) * scale,
                (chars.firstOrNull { it.ch == ' ' }?.xAdvance ?: chars.first().xAdvance) * scale
            )
        } catch (t: Throwable) {
            android.util.Log.w(GL.TAG, "BMFont load failed: ${t.message}")
            null
        }
    }

    private fun parseAttrs(s: String): HashMap<String, String> {
        val map = HashMap<String, String>()
        for (token in s.split(' ')) {
            val eq = token.indexOf('=')
            if (eq > 0) map[token.substring(0, eq)] = token.substring(eq + 1).trim('"')
        }
        return map
    }

    // ------------------------------------------------------------------ layout

    /** A positioned glyph ready to be drawn by the renderer. */
    class GlyphQuad(val glyph: Glyph, val x: Float, val y: Float, val scale: Float)

    class TextLayout(
        val quads: ArrayList<GlyphQuad> = ArrayList(),
        var width: Float = 0f,
        var height: Float = 0f
    )

    /**
     * Lays text out in world units: [size] is the cap-to-descender height the caller asked for,
     * so the same layout works at any zoom level. Returns quads relative to the text origin
     * (top-left, +Y down like the UI and sprite space).
     */
    fun layout(
        atlas: FontAtlas,
        text: String,
        size: Float,
        letterSpacing: Float = 0f,
        lineSpacing: Float = 1.15f,
        wrapWidth: Float = 0f,
        align: Int = 0
    ): TextLayout {
        val scale = if (atlas.lineHeight <= 0f) 1f else size / atlas.lineHeight
        val out = TextLayout()
        val lineHeight = atlas.lineHeight * lineSpacing * scale
        val lines = ArrayList<String>()
        if (wrapWidth > 0f) {
            for (raw in text.split('\n')) {
                var current = StringBuilder()
                for (word in raw.split(' ')) {
                    val candidate = if (current.isEmpty()) word else current.toString() + " " + word
                    if (atlas.measure(candidate, letterSpacing) * scale <= wrapWidth) {
                        current = StringBuilder(candidate)
                    } else {
                        if (current.isNotEmpty()) lines.add(current.toString())
                        current = StringBuilder(word)
                    }
                }
                lines.add(current.toString())
            }
        } else {
            lines.addAll(text.split('\n'))
        }
        var maxWidth = 0f
        for ((li, line) in lines.withIndex()) {
            val lineWidth = atlas.measure(line, letterSpacing) * scale
            maxWidth = max(maxWidth, lineWidth)
            var penX = when (align) {
                1 -> -lineWidth * 0.5f
                2 -> -lineWidth
                else -> 0f
            }
            val baselineY = li * lineHeight + atlas.ascent * scale
            for (c in line) {
                val g = atlas.glyph(c)
                if (g != null) {
                    out.quads.add(GlyphQuad(g, penX + g.xOffset * scale, baselineY + g.yOffset * scale, scale))
                    penX += (g.xAdvance + letterSpacing) * scale
                } else {
                    penX += (atlas.defaultAdvance + letterSpacing) * scale
                }
            }
        }
        out.width = maxWidth
        out.height = lineHeight * lines.size
        return out
    }
}
