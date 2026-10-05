package com.sengine.export

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Minimal deterministic ZIP writer with zipalign-style alignment of STORED entries
 * (4 bytes, or 4096 for native libraries) — required for installable APKs.
 */
class ZipWriter(private val out: OutputStream) {
    private var offset = 0L
    private val cd = ByteArrayOutputStream()
    var count = 0; private set
    val bytesWritten get() = offset

    private fun u16(o: OutputStream, v: Int) { o.write(v and 0xFF); o.write((v ushr 8) and 0xFF) }
    private fun u32(o: OutputStream, v: Long) { for (i in 0 until 4) o.write(((v ushr (8 * i)) and 0xFF).toInt()) }

    fun add(name: String, data: ByteArray, compress: Boolean, align: Int = 4) {
        val crc = CRC32().apply { update(data) }.value
        val payload: ByteArray
        val method: Int
        if (compress) {
            val d = Deflater(Deflater.DEFAULT_COMPRESSION, true)
            d.setInput(data); d.finish()
            val bo = ByteArrayOutputStream(maxOf(64, data.size / 2))
            val buf = ByteArray(65536)
            while (!d.finished()) { val n = d.deflate(buf); bo.write(buf, 0, n) }
            d.end()
            payload = bo.toByteArray(); method = 8
        } else { payload = data; method = 0 }
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        var extra = ByteArray(0)
        if (method == 0 && align > 1) {
            val base = offset + 30 + nameBytes.size
            var k = 0
            if (base % align != 0L) {
                k = 6
                while ((base + k) % align != 0L) k++
            }
            if (k > 0) {
                extra = ByteArray(k)
                extra[0] = 0x35; extra[1] = 0xD9.toByte()
                extra[2] = ((k - 4) and 0xFF).toByte(); extra[3] = (((k - 4) ushr 8) and 0xFF).toByte()
                extra[4] = (align and 0xFF).toByte(); extra[5] = ((align ushr 8) and 0xFF).toByte()
            }
        }
        val version = if (method == 8) 20 else 10
        val flags = 0x0800 // UTF-8 names
        val time = 0; val date = (1 shl 5) or 1 // 1980-01-01
        val localOffset = offset
        val h = ByteArrayOutputStream(30 + nameBytes.size + extra.size)
        u32(h, 0x04034b50); u16(h, version); u16(h, flags); u16(h, method); u16(h, time); u16(h, date)
        u32(h, crc); u32(h, payload.size.toLong()); u32(h, data.size.toLong()); u16(h, nameBytes.size); u16(h, extra.size)
        h.write(nameBytes); h.write(extra)
        val hb = h.toByteArray()
        out.write(hb); out.write(payload)
        offset += hb.size + payload.size

        u32(cd, 0x02014b50); u16(cd, 20); u16(cd, version); u16(cd, flags); u16(cd, method); u16(cd, time); u16(cd, date)
        u32(cd, crc); u32(cd, payload.size.toLong()); u32(cd, data.size.toLong()); u16(cd, nameBytes.size); u16(cd, 0); u16(cd, 0)
        u16(cd, 0); u16(cd, 0); u32(cd, 0); u32(cd, localOffset); cd.write(nameBytes)
        count++
    }

    fun centralDirectory(): ByteArray = cd.toByteArray()

    companion object {
        fun eocd(entries: Int, cdSize: Long, cdOffset: Long): ByteArray {
            val o = ByteArrayOutputStream(22)
            fun u16(v: Int) { o.write(v and 0xFF); o.write((v ushr 8) and 0xFF) }
            fun u32(v: Long) { for (i in 0 until 4) o.write(((v ushr (8 * i)) and 0xFF).toInt()) }
            u32(0x06054b50); u16(0); u16(0); u16(entries); u16(entries); u32(cdSize); u32(cdOffset); u16(0)
            return o.toByteArray()
        }
    }
}
