package com.sengine.export

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date
import javax.security.auth.x500.X500Principal

/** Signing identities for exported games. */
object SigningKeys {
    const val ALIAS = "sengine_game_signing_v1"

    class Identity(val key: PrivateKey, val certs: List<X509Certificate>, val description: String)

    /** A per-device RSA-2048 key generated inside the Android KeyStore (never leaves the device). */
    fun deviceKey(): Identity {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(ALIAS)) {
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }.time
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(X500Principal("CN=S Engine Game Developer, O=S Engine"))
                .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                .setCertificateNotBefore(Date(System.currentTimeMillis() - 86_400_000L))
                .setCertificateNotAfter(end)
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
        }
        val key = ks.getKey(ALIAS, null) as PrivateKey
        val cert = ks.getCertificate(ALIAS) as X509Certificate
        return Identity(key, listOf(cert), "Device key (${cert.subjectX500Principal.name})")
    }

    /** Loads a PKCS#12 (.p12/.pfx) or BKS keystore file. Uses the first key entry if [alias] is blank. */
    fun fromFile(file: File, password: CharArray, alias: String, keyPassword: CharArray? = null): Identity {
        val type = if (file.name.lowercase().endsWith(".bks")) "BKS" else "PKCS12"
        val ks = KeyStore.getInstance(type)
        file.inputStream().use { ks.load(it, password) }
        val a = alias.ifBlank { ks.aliases().toList().firstOrNull { ks.isKeyEntry(it) } ?: error("No key entry in keystore") }
        val key = ks.getKey(a, keyPassword ?: password) as? PrivateKey ?: error("Alias '$a' has no private key")
        val chain = ks.getCertificateChain(a)?.map { it as X509Certificate } ?: error("Alias '$a' has no certificate")
        return Identity(key, chain, "Keystore ${file.name} ($a)")
    }
}
