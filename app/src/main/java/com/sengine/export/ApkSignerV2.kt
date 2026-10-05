package com.sengine.export

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate

/**
 * APK Signature Scheme v2 signer (supported by Android 7.0+; S Engine games need 8.0+).
 * Layout: [zip entries][APK Signing Block][central directory][EOCD].
 */
object ApkSignerV2 {
    private const val CHUNK = 1024 * 1024
    private const val V2_BLOCK_ID = 0x7109871aL
    private const val ALGO_RSA_PKCS1_SHA256 = 0x0103
    private const val ALGO_ECDSA_SHA256 = 0x0201

    private fun u32(o: ByteArrayOutputStream, v: Long) { for (i in 0 until 4) o.write(((v ushr (8 * i)) and 0xFF).toInt()) }
    private fun u64(o: ByteArrayOutputStream, v: Long) { for (i in 0 until 8) o.write(((v ushr (8 * i)) and 0xFF).toInt()) }
    private fun lp(b: ByteArray): ByteArray = ByteArrayOutputStream(b.size + 4).also { u32(it, b.size.toLong()); it.write(b) }.toByteArray()
    private fun cat(vararg parts: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> parts.forEach { o.write(it) } }.toByteArray()

    /** Writes the signed APK to [out]. [entriesFile] holds the local file entries only. */
    fun sign(entriesFile: File, centralDir: ByteArray, entryCount: Int, key: PrivateKey, certs: List<X509Certificate>, out: File) {
        val entriesLen = entriesFile.length()
        val isEc = key.algorithm.equals("EC", true)
        val algo = if (isEc) ALGO_ECDSA_SHA256 else ALGO_RSA_PKCS1_SHA256
        val sigName = if (isEc) "SHA256withECDSA" else "SHA256withRSA"

        // 1. content digest (EOCD's CD offset points to the start of the signing block = entriesLen)
        val eocdForDigest = ZipWriter.eocd(entryCount, centralDir.size.toLong(), entriesLen)
        val digest = contentDigest(entriesFile, centralDir, eocdForDigest)

        // 2. signed data
        val digests = lp(lp(cat(ByteArrayOutputStream().also { u32(it, algo.toLong()) }.toByteArray(), lp(digest))))
        val certSeq = lp(certs.map { lp(it.encoded) }.fold(ByteArray(0)) { a, b -> cat(a, b) })
        val attrs = lp(ByteArray(0))
        val signedData = cat(digests, certSeq, attrs)

        val sig = Signature.getInstance(sigName).run { initSign(key); update(signedData); sign() }
        val signatures = lp(lp(cat(ByteArrayOutputStream().also { u32(it, algo.toLong()) }.toByteArray(), lp(sig))))
        val publicKey = lp(certs[0].publicKey.encoded)
        val signer = cat(lp(signedData), signatures, publicKey)
        val v2Value = lp(lp(signer))

        // 3. signing block
        val pairs = ByteArrayOutputStream()
        u64(pairs, (4 + v2Value.size).toLong()); u32(pairs, V2_BLOCK_ID); pairs.write(v2Value)
        val pairBytes = pairs.toByteArray()
        val blockSize = pairBytes.size + 8L + 16L
        val block = ByteArrayOutputStream()
        u64(block, blockSize); block.write(pairBytes); u64(block, blockSize); block.write("APK Sig Block 42".toByteArray(Charsets.US_ASCII))
        val blockBytes = block.toByteArray()

        // 4. final file
        val eocd = ZipWriter.eocd(entryCount, centralDir.size.toLong(), entriesLen + blockBytes.size)
        out.outputStream().buffered().use { o ->
            entriesFile.inputStream().use { it.copyTo(o, 65536) }
            o.write(blockBytes); o.write(centralDir); o.write(eocd)
        }
    }

    private fun contentDigest(entries: File, cd: ByteArray, eocd: ByteArray): ByteArray {
        val chunkDigests = ByteArrayOutputStream()
        var count = 0L
        val md = MessageDigest.getInstance("SHA-256")
        fun chunk(buf: ByteArray, off: Int, len: Int) {
            md.reset()
            md.update(0xa5.toByte())
            md.update(byteArrayOf((len and 0xFF).toByte(), ((len ushr 8) and 0xFF).toByte(), ((len ushr 16) and 0xFF).toByte(), ((len ushr 24) and 0xFF).toByte()))
            md.update(buf, off, len)
            chunkDigests.write(md.digest()); count++
        }
        RandomAccessFile(entries, "r").use { raf ->
            val buf = ByteArray(CHUNK)
            var remaining = raf.length()
            while (remaining > 0) {
                val n = minOf(CHUNK.toLong(), remaining).toInt()
                raf.readFully(buf, 0, n)
                chunk(buf, 0, n)
                remaining -= n
            }
        }
        for (section in listOf(cd, eocd)) {
            var off = 0
            while (off < section.size) {
                val n = minOf(CHUNK, section.size - off)
                chunk(section, off, n); off += n
            }
        }
        md.reset()
        md.update(0x5a.toByte())
        val c = count.toInt()
        md.update(byteArrayOf((c and 0xFF).toByte(), ((c ushr 8) and 0xFF).toByte(), ((c ushr 16) and 0xFF).toByte(), ((c ushr 24) and 0xFF).toByte()))
        md.update(chunkDigests.toByteArray())
        return md.digest()
    }
}
