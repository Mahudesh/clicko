package com.mahudesh.tvremote.tv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/** How to shake hands. Some TV firmwares mishandle TLS 1.3 client certificates, so we can fall back. */
enum class TlsMode { AUTO, TLS12 }

/**
 * The phone's identity towards every TV: one RSA key + self-signed certificate, created once.
 *
 * The certificate mirrors what the reference implementation (androidtvremote2, used by Home
 * Assistant against thousands of TVs) presents: CN=atvremote, CA basic constraint, a DNS SAN,
 * serial 1000, SHA256withRSA. Real TVs drop the connection right after the TLS handshake if the
 * client certificate isn't acceptable to them.
 *
 * The private key is stored in app-private storage, encrypted with an AES key that lives in
 * the hardware-backed Android Keystore.
 */
object TlsIdentity {
    private const val NAME = "atvremote"
    private const val WRAP_ALIAS = "tvremote_identity_wrap"

    private lateinit var dir: File
    @Volatile private var cached: Pair<X509Certificate, PrivateKey>? = null

    fun init(context: Context) {
        dir = File(context.filesDir, "identity").apply { mkdirs() }
    }

    val certificate: X509Certificate get() = identity().first
    private val privateKey: PrivateKey get() = identity().second

    @Synchronized
    private fun identity(): Pair<X509Certificate, PrivateKey> {
        cached?.let { return it }
        val certFile = File(dir, "client.crt")
        val keyFile = File(dir, "client.key.enc")
        val loaded = runCatching {
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(certFile.inputStream()) as X509Certificate
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(unwrap(keyFile.readBytes())))
            cert to key
        }.getOrNull()
        val id = loaded ?: create().also { (cert, key) ->
            certFile.writeBytes(cert.encoded)
            keyFile.writeBytes(wrap(key.encoded))
        }
        cached = id
        return id
    }

    private fun create(): Pair<X509Certificate, PrivateKey> {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=$NAME")
        // Start a day early so a TV with a slightly wrong clock doesn't see a "future" certificate.
        val notBefore = Date(System.currentTimeMillis() - 24L * 3600_000)
        val notAfter = Date(System.currentTimeMillis() + 3650L * 24 * 3600_000)
        val holder = JcaX509v3CertificateBuilder(name, BigInteger.valueOf(1000), notBefore, notAfter, name, pair.public)
            .addExtension(Extension.basicConstraints, false, BasicConstraints(0))
            .addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.dNSName, NAME)))
            .build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(holder.encoded.inputStream()) as X509Certificate
        return cert to pair.private
    }

    // ---- private key at rest: AES-GCM with a Keystore key ----

    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private fun wrap(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrapKey()) }
        return byteArrayOf(c.iv.size.toByte()) + c.iv + c.doFinal(plain)
    }

    private fun unwrap(blob: ByteArray): ByteArray {
        val ivLen = blob[0].toInt()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, blob, 1, ivLen))
        return c.doFinal(blob, 1 + ivLen, blob.size - 1 - ivLen)
    }

    // ---- TLS ----

    /** Opens a TLS socket that presents our certificate and accepts the TV's self-signed one. */
    fun connect(host: String, port: Int, timeoutMs: Int = 4000, mode: TlsMode = TlsMode.AUTO): SSLSocket {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(arrayOf(KeyManager()), arrayOf(AcceptAllTrust), null)
        val raw = Net.connect(host, port, timeoutMs)
        val socket = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        if (mode == TlsMode.TLS12) socket.enabledProtocols = arrayOf("TLSv1.2")
        socket.soTimeout = timeoutMs * 4
        socket.startHandshake()
        return socket
    }

    private class KeyManager : X509ExtendedKeyManager() {
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = NAME
        override fun getCertificateChain(alias: String?) = arrayOf(certificate)
        override fun getPrivateKey(alias: String?) = privateKey
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(NAME)
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = null
    }

    // TVs use self-signed certificates; trust is established by pairing + fingerprint instead.
    @Suppress("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object AcceptAllTrust : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}

fun X509Certificate.fingerprint(): String =
    MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }

fun SSLSocket.peerCertificate(): X509Certificate = session.peerCertificates.first() as X509Certificate

/** RSA modulus/exponent as unsigned big-endian bytes, as the pairing hash expects. */
internal fun X509Certificate.rsaParts(): Pair<ByteArray, ByteArray> {
    val key = publicKey as RSAPublicKey
    return key.modulus.unsignedBytes() to key.publicExponent.unsignedBytes()
}

private fun BigInteger.unsignedBytes(): ByteArray {
    val b = toByteArray()
    return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
}
