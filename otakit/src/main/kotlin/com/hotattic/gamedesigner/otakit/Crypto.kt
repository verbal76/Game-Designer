package com.hotattic.gamedesigner.otakit

import java.io.File
import java.io.InputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** ECDSA P-256 / SHA-256 is available on every Android version we support (minSdk 28) and on the JVM. */
object Crypto {
    private const val ALG = "SHA256withECDSA"

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    fun sha256Hex(file: File): String = file.inputStream().use { sha256Hex(it) }

    fun sha256Hex(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        return md.digest().toHex()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    data class KeyPairB64(val publicX509: String, val privatePkcs8: String)

    fun generateKeyPair(): KeyPairB64 {
        val g = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val kp = g.generateKeyPair()
        return KeyPairB64(Base64.getEncoder().encodeToString(kp.public.encoded), Base64.getEncoder().encodeToString(kp.private.encoded))
    }

    fun publicKey(b64: String): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(b64.trim())))
    fun privateKey(b64: String): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64.trim())))

    fun sign(data: ByteArray, privatePkcs8B64: String): String {
        val s = Signature.getInstance(ALG).apply { initSign(privateKey(privatePkcs8B64)); update(data) }
        return Base64.getEncoder().encodeToString(s.sign())
    }

    /** True only if [signatureB64] is a valid signature of [data] by at least one trusted key. Never throws. */
    fun verifyAny(data: ByteArray, signatureB64: String, trustedPublicKeysB64: List<String>): Boolean {
        val sig = try { Base64.getDecoder().decode(signatureB64.trim()) } catch (e: IllegalArgumentException) { return false }
        return trustedPublicKeysB64.any { k ->
            try {
                Signature.getInstance(ALG).apply { initVerify(publicKey(k)); update(data) }.verify(sig)
            } catch (e: Exception) { false }
        }
    }
}
