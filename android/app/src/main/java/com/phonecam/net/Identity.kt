package com.phonecam.net

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/** A PC that was allowed to use the camera. */
data class TrustedPc(val fingerprint: String, val name: String, val pairedAt: Long)

/**
 * The phone's TLS identity and the list of PCs it trusts.
 *
 * The private key lives in the Android Keystore and never leaves it. PCs are
 * recognised by the SHA-256 fingerprint of their certificate, which they
 * create once on first run, so there are no codes to type after pairing.
 */
object Identity {
    private const val ALIAS = "phonecam-identity"
    private const val PREFS = "trust"

    fun sslContext(): SSLContext {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) generate()
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(store, null)
        return SSLContext.getInstance("TLSv1.3").apply { init(kmf.keyManagers, arrayOf(AcceptAnyClient), null) }
    }

    fun fingerprint(): String {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) generate()
        return fingerprint(store.getCertificate(ALIAS))
    }

    fun fingerprint(cert: Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

    /**
     * Six digits both screens show during pairing. Derived from both
     * fingerprints, so a device in the middle would produce different digits.
     */
    fun verificationCode(a: String, b: String): String {
        val (x, y) = listOf(a, b).sorted()
        val digest = MessageDigest.getInstance("SHA-256").digest("$x:$y".toByteArray())
        val n = ((digest[0].toInt() and 0xff) shl 16) or ((digest[1].toInt() and 0xff) shl 8) or (digest[2].toInt() and 0xff)
        return "%06d".format(n % 1_000_000)
    }

    fun trusted(context: Context): List<TrustedPc> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("pcs", "[]")
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                array.getJSONObject(i).let { TrustedPc(it.getString("fp"), it.optString("name", "PC"), it.optLong("at")) }
            }
        }.getOrDefault(emptyList())
    }

    fun isTrusted(context: Context, fingerprint: String) = trusted(context).any { it.fingerprint == fingerprint }

    fun trust(context: Context, fingerprint: String, name: String) {
        val list = trusted(context).filter { it.fingerprint != fingerprint } + TrustedPc(fingerprint, name, System.currentTimeMillis())
        save(context, list)
    }

    fun forget(context: Context, fingerprint: String) = save(context, trusted(context).filter { it.fingerprint != fingerprint })

    private fun save(context: Context, list: List<TrustedPc>) {
        val array = JSONArray(list.map { JSONObject().put("fp", it.fingerprint).put("name", it.name).put("at", it.pairedAt) })
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("pcs", array.toString()).apply()
    }

    private fun generate() {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .setCertificateSubject(X500Principal("CN=PhoneCam ${Build.MODEL.replace(",", " ")}"))
                .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                .setCertificateNotBefore(Date(System.currentTimeMillis() - 86_400_000L))
                .setCertificateNotAfter(Date(System.currentTimeMillis() + 30L * 365 * 86_400_000L))
                .build()
        )
        generator.generateKeyPair()
    }

    /**
     * Every client certificate is accepted at the TLS layer; whether that PC may
     * see the camera is decided right after the handshake, by fingerprint,
     * because unknown PCs must still be able to ask for pairing.
     */
    private object AcceptAnyClient : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
            require(chain.isNotEmpty())
        }
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) =
            throw UnsupportedOperationException("PhoneCam only acts as a server")
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
