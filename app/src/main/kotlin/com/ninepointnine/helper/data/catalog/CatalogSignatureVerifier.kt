package com.ninepointnine.helper.data.catalog

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

class JcaCatalogSignatureVerifier(
    private val keyResolver: TrustedCatalogKeyResolver,
) : CatalogSignatureVerifier {
    override fun verify(
        keyId: String,
        algorithm: String,
        payload: ByteArray,
        signature: ByteArray,
    ): Boolean {
        val keyBytes = keyResolver.resolve(keyId) ?: return false
        val keyFactoryAlgorithm = when (algorithm) {
            "SHA256withECDSA" -> "EC"
            "Ed25519" -> "Ed25519"
            else -> return false
        }
        return try {
            val publicKey = KeyFactory.getInstance(keyFactoryAlgorithm)
                .generatePublic(X509EncodedKeySpec(keyBytes))
            Signature.getInstance(algorithm).run {
                initVerify(publicKey)
                update(payload)
                verify(signature)
            }
        } catch (_: Exception) {
            false
        }
    }
}
