package com.sengine.export

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Patches a compiled (binary XML) AndroidManifest.xml:
 *  - renames the package (every string equal to / prefixed by the old package,
 *    which also covers provider authorities and custom permissions),
 *  - sets a literal application label, versionName and versionCode.
 */
object AxmlPatcher {
    private const val RES_STRING_POOL = 0x0001
    private const val RES_XML_START_ELEMENT = 0x0102
    private const val UTF8_FLAG = 1 shl 8
    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_DEC = 0x10

    class Result(val bytes: ByteArray, val strings: List<String>)

    fun patch(axml: ByteArray, oldPkg: String, newPkg: String, label: String?, versionName: String?, versionCode: Int?): Result {
        val bb = ByteBuffer.wrap(axml).order(ByteOrder.LITTLE_ENDIAN)
        require(bb.getShort(0).toInt() == 0x0003) { "Not a binary XML file" }
        val poolStart = bb.getShort(2).toInt() and 0xFFFF
        require(bb.getShort(poolStart).toInt() == RES_STRING_POOL) { "String pool missing" }
        val poolHeader = bb.getShort(poolStart + 2).toInt() and 0xFFFF
        val poolSize = bb.getInt(poolStart + 4)
        val stringCount = bb.getInt(poolStart + 8)
        val styleCount = bb.getInt(poolStart + 12)
        val flags = bb.getInt(poolStart + 16)
        val stringsStart = bb.getInt(poolStart + 20)
        val stylesStart = bb.getInt(poolStart + 24)
        val utf8 = (flags and UTF8_FLAG) != 0

        val strings = ArrayList<String>(stringCount + 2)
        for (i in 0 until stringCount) {
            val off = poolStart + stringsStart + bb.getInt(poolStart + poolHeader + i * 4)
            strings.add(if (utf8) readUtf8(axml, off) else readUtf16(bb, off))
        }
        val styleOffsets = IntArray(styleCount) { bb.getInt(poolStart + poolHeader + stringCount * 4 + it * 4) }
        val styleData = if (styleCount > 0) axml.copyOfRange(poolStart + stylesStart, poolStart + poolSize) else ByteArray(0)

        // rename package-prefixed strings
        for (i in strings.indices) {
            val s = strings[i]
            if (s == oldPkg) strings[i] = newPkg
            else if (s.startsWith("$oldPkg.")) strings[i] = newPkg + s.substring(oldPkg.length)
        }
        var labelIdx = -1
        var versionIdx = -1
        if (label != null) { labelIdx = strings.size; strings.add(label) }
        if (versionName != null) { versionIdx = strings.size; strings.add(versionName) }

        // re-encode the string pool
        val data = ByteArrayOutputStream()
        val offsets = IntArray(strings.size)
        for (i in strings.indices) {
            offsets[i] = data.size()
            if (utf8) writeUtf8(data, strings[i]) else writeUtf16(data, strings[i])
        }
        while (data.size() % 4 != 0) data.write(0)
        val dataBytes = data.toByteArray()
        val newStringsStart = poolHeader + strings.size * 4 + styleCount * 4
        val newStylesStart = if (styleCount > 0) newStringsStart + dataBytes.size else 0
        val newPoolSize = newStringsStart + dataBytes.size + styleData.size
        val pool = ByteBuffer.allocate(newPoolSize).order(ByteOrder.LITTLE_ENDIAN)
        pool.putShort(RES_STRING_POOL.toShort()); pool.putShort(poolHeader.toShort()); pool.putInt(newPoolSize)
        pool.putInt(strings.size); pool.putInt(styleCount); pool.putInt(flags); pool.putInt(newStringsStart); pool.putInt(newStylesStart)
        while (pool.position() < poolHeader) pool.put(0)
        offsets.forEach { pool.putInt(it) }
        styleOffsets.forEach { pool.putInt(it) }
        pool.put(dataBytes); pool.put(styleData)

        // rest of the document, with attribute patches
        val rest = axml.copyOfRange(poolStart + poolSize, axml.size)
        val rb = ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN)
        var p = 0
        while (p + 8 <= rest.size) {
            val type = rb.getShort(p).toInt() and 0xFFFF
            val hsize = rb.getShort(p + 2).toInt() and 0xFFFF
            val size = rb.getInt(p + 4)
            if (size <= 0) break
            if (type == RES_XML_START_ELEMENT) {
                val ext = p + hsize
                val elName = strings.getOrNull(rb.getInt(ext + 4)) ?: ""
                val attrStart = rb.getShort(ext + 8).toInt() and 0xFFFF
                val attrSize = rb.getShort(ext + 10).toInt() and 0xFFFF
                val attrCount = rb.getShort(ext + 12).toInt() and 0xFFFF
                for (a in 0 until attrCount) {
                    val ap = ext + attrStart + a * attrSize
                    val attrName = strings.getOrNull(rb.getInt(ap + 4)) ?: ""
                    if (elName == "application" && attrName == "label" && labelIdx >= 0) setString(rb, ap, labelIdx)
                    if (elName == "manifest" && attrName == "versionName" && versionIdx >= 0) setString(rb, ap, versionIdx)
                    if (elName == "manifest" && attrName == "versionCode" && versionCode != null) {
                        rb.putInt(ap + 8, -1); rb.put(ap + 15, TYPE_INT_DEC.toByte()); rb.putInt(ap + 16, versionCode)
                    }
                }
            }
            p += size
        }

        val total = poolStart + newPoolSize + rest.size
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.put(axml, 0, poolStart)
        out.putInt(4, total)
        out.position(poolStart)
        out.put(pool.array()); out.put(rest)
        return Result(out.array(), strings)
    }

    private fun setString(rb: ByteBuffer, ap: Int, idx: Int) {
        rb.putInt(ap + 8, idx)
        rb.put(ap + 15, TYPE_STRING.toByte())
        rb.putInt(ap + 16, idx)
    }

    // ------------------------------------------------------------------ string codecs

    private fun readUtf8(b: ByteArray, off0: Int): String {
        var off = off0
        // char length (1 or 2 bytes)
        off += if ((b[off].toInt() and 0x80) != 0) 2 else 1
        var len = b[off].toInt() and 0xFF
        if ((len and 0x80) != 0) { len = ((len and 0x7F) shl 8) or (b[off + 1].toInt() and 0xFF); off += 2 } else off += 1
        return String(b, off, len, Charsets.UTF_8)
    }

    private fun readUtf16(bb: ByteBuffer, off0: Int): String {
        var off = off0
        var len = bb.getShort(off).toInt() and 0xFFFF
        if ((len and 0x8000) != 0) { len = ((len and 0x7FFF) shl 16) or (bb.getShort(off + 2).toInt() and 0xFFFF); off += 4 } else off += 2
        val chars = CharArray(len) { bb.getChar(off + it * 2) }
        return String(chars)
    }

    private fun writeLen8(o: ByteArrayOutputStream, n: Int) {
        if (n > 0x7F) { o.write(((n ushr 8) and 0x7F) or 0x80); o.write(n and 0xFF) } else o.write(n)
    }

    private fun writeUtf8(o: ByteArrayOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        writeLen8(o, s.length)
        writeLen8(o, bytes.size)
        o.write(bytes); o.write(0)
    }

    private fun writeUtf16(o: ByteArrayOutputStream, s: String) {
        val n = s.length
        if (n > 0x7FFF) { val hi = ((n ushr 16) and 0x7FFF) or 0x8000; o.write(hi and 0xFF); o.write(hi ushr 8); o.write(n and 0xFF); o.write((n ushr 8) and 0xFF) }
        else { o.write(n and 0xFF); o.write(n ushr 8) }
        for (c in s) { o.write(c.code and 0xFF); o.write(c.code ushr 8) }
        o.write(0); o.write(0)
    }
}
