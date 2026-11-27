package com.Bilibili_Innocent_Lab.xposedmodule.plugin.container

import com.bilab.plugin.api.model.PluginQuarantineReason
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.NoSuchProviderException
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale

/**
 * 受信任的 `.bilp` 插件签名者公钥描述符。
 */
internal data class TrustedSigner(
    val keyId: String,
    val algorithm: String,
    val publicKeyBase64: String,
    val publicKeySha256: String
)

/**
 * `.bilp` 签名验证结果；仅允许 [Verified] 进入后续加载链。
 */
internal sealed interface SignatureVerifyResult {
    data class Verified(
        val signerKeyId: String,
        val publicKeySha256: String
    ) : SignatureVerifyResult

    data class Rejected(
        val reason: PluginQuarantineReason,
        val detail: String
    ) : SignatureVerifyResult
}

/**
 * `.bilp` 签名者信任注册表。
 *
 * 默认构造携带内建根公钥锚；传入自定义列表（含空列表）时仅信任显式传入的集合，确保测试与策略严格隔离。
 */
internal class TrustedSignerRegistry @JvmOverloads constructor(
    signers: List<TrustedSigner> = DEFAULT_TRUSTED_SIGNERS,
    revokedKeyIds: Set<String> = DEFAULT_REVOKED_KEY_IDS
) {
    private val revokedKeyIdSet: Set<String> = revokedKeyIds
        .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
        .toSet()

    private val signersByKeyId: Map<String, TrustedSigner> = buildMap {
        for (signer in signers) {
            if (signer.keyId.isNotBlank() &&
                signer.algorithm in SUPPORTED_ALGORITHMS &&
                SHA256_HEX_REGEX.matches(signer.publicKeySha256)
            ) {
                put(signer.keyId, signer)
            }
        }
    }

    fun isKeyRevoked(keyId: String): Boolean = keyId.trim() in revokedKeyIdSet

    fun findSigner(keyId: String): TrustedSigner? =
        if (isKeyRevoked(keyId)) null else signersByKeyId[keyId]

    fun registeredKeyIds(): Set<String> =
        signersByKeyId.keys.filterNot { it in revokedKeyIdSet }.toSet()

    fun verifySignature(
        payloadSha256: String,
        signatureEnvelopeJson: String
    ): SignatureVerifyResult = PluginSignatureVerifier.verifySignature(
        payloadSha256 = payloadSha256,
        signatureEnvelopeJson = signatureEnvelopeJson,
        registry = this
    )

    companion object {
        const val ALGORITHM_ECDSA_SHA256 = "SHA256withECDSA"
        const val ALGORITHM_ED25519 = "Ed25519"

        val SUPPORTED_ALGORITHMS: Set<String> = linkedSetOf(
            ALGORITHM_ECDSA_SHA256,
            ALGORITHM_ED25519
        )

        val DEFAULT_REVOKED_KEY_IDS: Set<String> = emptySet()

        private val SHA256_HEX_REGEX = Regex("[0-9a-f]{64}")

        /**
         * 默认内建根公钥锚（仅含 X.509 SubjectPublicKeyInfo 公开公钥与摘要，不含任何私钥）。
         */
        val DEFAULT_TRUSTED_SIGNERS: List<TrustedSigner> = listOf(
            TrustedSigner(
                keyId = "bilab-root-ecdsa-p256-v1",
                algorithm = ALGORITHM_ECDSA_SHA256,
                publicKeyBase64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEFP0MXkkbENVTKnmgO0FV2XN2XvWavlJowPDhPTta2Ekf+4zNvyTMV4c6En7uVbeHHjSnppe8YMnIe9oFu+x4pA==",
                publicKeySha256 = "e0093a71d3a76f1ba7d9c484dcca48fbf6e222f8df16ef325b732b78d84c4af5"
            ),
            TrustedSigner(
                keyId = "bilab-root-ed25519-v1",
                algorithm = ALGORITHM_ED25519,
                publicKeyBase64 = "MCowBQYDK2VwAyEAVi7PK4H/DHt0olNmamI+jpNVz0GTgUurgT7OtU9biVY=",
                publicKeySha256 = "27e1686107f5baab148dc1aa2820870d869d93646f96308098f0916fa9843185"
            )
        )
    }
}

/**
 * `.bilp` 容器签名验证器：支持 `Ed25519` 与 `SHA256withECDSA`，
 * 校验信封格式、算法匹配、公钥 SHA-256 常量时间比对与 JCA 签名验证，任何异常均 fail-closed 拒绝。
 */
internal object PluginSignatureVerifier {
    const val ENVELOPE_SCHEMA_VERSION = 1
    const val MAX_ENVELOPE_BYTES = 16 * 1024

    private val SHA256_HEX_REGEX = Regex("[0-9a-f]{64}")
    private val ED25519_SPKI_PREFIX = byteArrayOf(
        0x30, 0x2A, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x70, 0x03, 0x21, 0x00
    )
    private const val ED25519_SPKI_BYTES = 44
    private val HEX_DIGITS = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    )

    fun computePublicKeySha256(publicKeyEncoded: ByteArray): String =
        sha256Hex(MessageDigest.getInstance("SHA-256").digest(publicKeyEncoded))

    fun canonicalSigningPayload(payloadSha256: String): ByteArray {
        val normalized = payloadSha256.trim().lowercase(Locale.ROOT)
        require(SHA256_HEX_REGEX.matches(normalized)) { "payloadSha256 must be 64-char hex" }
        return "bilp.sig.v1:$normalized".toByteArray(Charsets.UTF_8)
    }

    fun buildSignatureEnvelopeJson(
        keyId: String,
        algorithm: String,
        payloadSha256: String,
        signatureBase64: String,
        publicKeySha256: String? = null
    ): String {
        val obj = JSONObject()
            .put("schemaVersion", ENVELOPE_SCHEMA_VERSION)
            .put("keyId", keyId)
            .put("algorithm", algorithm)
            .put("payloadSha256", payloadSha256.lowercase(Locale.ROOT))
            .put("signature", signatureBase64)
        if (publicKeySha256 != null) {
            obj.put("publicKeySha256", publicKeySha256.lowercase(Locale.ROOT))
        }
        return obj.toString()
    }

    fun signPayloadSha256(
        payloadSha256: String,
        keyId: String,
        algorithm: String,
        privateKey: PrivateKey,
        publicKeySha256: String? = null
    ): String {
        require(keyId.isNotBlank()) { "keyId must not be blank" }
        require(algorithm in TrustedSignerRegistry.SUPPORTED_ALGORITHMS) {
            "Unsupported signature algorithm: $algorithm"
        }
        val signingInput = canonicalSigningPayload(payloadSha256)
        val signer = Signature.getInstance(algorithm)
        signer.initSign(privateKey)
        signer.update(signingInput)
        val sigBytes = signer.sign()
        val sigBase64 = Base64.getEncoder().encodeToString(sigBytes)
        return buildSignatureEnvelopeJson(
            keyId = keyId,
            algorithm = algorithm,
            payloadSha256 = payloadSha256,
            signatureBase64 = sigBase64,
            publicKeySha256 = publicKeySha256
        )
    }

    fun verifySignature(
        payloadSha256: String,
        signatureEnvelopeJson: String,
        registry: TrustedSignerRegistry
    ): SignatureVerifyResult {
        if (!SHA256_HEX_REGEX.matches(payloadSha256)) {
            return rejected("invalid_payload_sha256")
        }
        if (signatureEnvelopeJson.isBlank() ||
            signatureEnvelopeJson.toByteArray(Charsets.UTF_8).size > MAX_ENVELOPE_BYTES
        ) {
            return rejected("invalid_envelope_size")
        }

        val envelope = runCatching { JSONObject(signatureEnvelopeJson) }.getOrNull()
            ?: return rejected("malformed_envelope_json")

        val schemaVersion = when (val v = envelope.opt("schemaVersion")) {
            is Int -> v
            is Long -> if (v in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) v.toInt() else null
            null -> null
            else -> null
        } ?: return rejected("invalid_envelope_schema")
        if (schemaVersion != ENVELOPE_SCHEMA_VERSION) {
            return rejected("unsupported_envelope_schema")
        }

        val keyId = (envelope.opt("keyId") as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return rejected("missing_key_id")
        val algorithm = (envelope.opt("algorithm") as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return rejected("missing_algorithm")
        val signatureBase64 = (envelope.opt("signature") as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return rejected("missing_signature")

        if (envelope.has("payloadSha256")) {
            val declaredPayloadSha = envelope.opt("payloadSha256") as? String
                ?: return rejected("invalid_declared_payload_sha256")
            if (!SHA256_HEX_REGEX.matches(declaredPayloadSha) ||
                !constantTimeAsciiEquals(declaredPayloadSha, payloadSha256)
            ) {
                return rejected("payload_sha256_mismatch")
            }
        }

        if (registry.isKeyRevoked(keyId)) {
            return rejected("revoked_signer")
        }
        val trusted = registry.findSigner(keyId)
            ?: return rejected("untrusted_signer")
        if (trusted.algorithm != algorithm || algorithm !in TrustedSignerRegistry.SUPPORTED_ALGORITHMS) {
            return rejected("algorithm_mismatch")
        }

        val publicKeyBytes = runCatching { Base64.getDecoder().decode(trusted.publicKeyBase64) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: return rejected("invalid_trusted_public_key")

        val actualKeyDigest = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes)
        val expectedKeyDigest = hexToBytes(trusted.publicKeySha256)
            ?: return rejected("invalid_trusted_key_sha256")
        if (!MessageDigest.isEqual(actualKeyDigest, expectedKeyDigest)) {
            return rejected("trusted_key_sha256_mismatch")
        }

        if (envelope.has("publicKeySha256")) {
            val declaredKeySha = envelope.opt("publicKeySha256") as? String
                ?: return rejected("invalid_declared_key_sha256")
            if (!SHA256_HEX_REGEX.matches(declaredKeySha) ||
                !constantTimeAsciiEquals(declaredKeySha, trusted.publicKeySha256)
            ) {
                return rejected("declared_key_sha256_mismatch")
            }
        }

        val signatureBytes = runCatching { Base64.getDecoder().decode(signatureBase64) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: return rejected("invalid_signature_base64")

        val signingInput = canonicalSigningPayload(payloadSha256)
        val verified = when (algorithm) {
            TrustedSignerRegistry.ALGORITHM_ECDSA_SHA256 -> {
                val publicKey = decodePublicKey(algorithm, publicKeyBytes)
                    ?: return rejected("public_key_parse_failed")
                runCatching {
                    val verifier = Signature.getInstance(algorithm)
                    verifier.initVerify(publicKey)
                    verifier.update(signingInput)
                    verifier.verify(signatureBytes)
                }.getOrDefault(false)
            }
            TrustedSignerRegistry.ALGORITHM_ED25519 -> {
                val rawPublicKey = extractRawEd25519PublicKey(publicKeyBytes)
                    ?: return rejected("public_key_parse_failed")
                verifyEd25519WithJcaOrPureFallback(
                    spkiBytes = publicKeyBytes,
                    rawPublicKey32 = rawPublicKey,
                    message = signingInput,
                    signatureBytes = signatureBytes
                )
            }
            else -> return rejected("algorithm_mismatch")
        }

        return if (verified) {
            SignatureVerifyResult.Verified(
                signerKeyId = trusted.keyId,
                publicKeySha256 = trusted.publicKeySha256
            )
        } else {
            rejected("signature_verification_failed")
        }
    }

    private fun verifyEd25519WithJcaOrPureFallback(
        spkiBytes: ByteArray,
        rawPublicKey32: ByteArray,
        message: ByteArray,
        signatureBytes: ByteArray
    ): Boolean {
        val publicKey = decodePublicKey(TrustedSignerRegistry.ALGORITHM_ED25519, spkiBytes)
        if (publicKey != null) {
            for (sigAlg in listOf("Ed25519", "EdDSA")) {
                val jcaVerifier = try {
                    Signature.getInstance(sigAlg)
                } catch (_: NoSuchAlgorithmException) {
                    null
                } catch (_: NoSuchProviderException) {
                    null
                }
                if (jcaVerifier != null) {
                    try {
                        jcaVerifier.initVerify(publicKey)
                    } catch (_: NoSuchAlgorithmException) {
                        continue
                    } catch (_: java.security.InvalidKeyException) {
                        continue
                    } catch (_: java.security.ProviderException) {
                        continue
                    }
                    return try {
                        jcaVerifier.update(message)
                        jcaVerifier.verify(signatureBytes)
                    } catch (_: java.security.SignatureException) {
                        false
                    } catch (_: Throwable) {
                        Ed25519PureVerifier.verify(rawPublicKey32, message, signatureBytes)
                    }
                }
            }
        }
        return Ed25519PureVerifier.verify(rawPublicKey32, message, signatureBytes)
    }

    private fun extractRawEd25519PublicKey(spkiBytes: ByteArray): ByteArray? {
        if (spkiBytes.size != ED25519_SPKI_BYTES) return null
        for (i in ED25519_SPKI_PREFIX.indices) {
            if (spkiBytes[i] != ED25519_SPKI_PREFIX[i]) return null
        }
        return spkiBytes.copyOfRange(ED25519_SPKI_PREFIX.size, spkiBytes.size)
    }

    private fun decodePublicKey(algorithm: String, encoded: ByteArray): PublicKey? {
        val keySpec = X509EncodedKeySpec(encoded)
        val factoryCandidates = when (algorithm) {
            TrustedSignerRegistry.ALGORITHM_ED25519 -> listOf("Ed25519", "EdDSA")
            TrustedSignerRegistry.ALGORITHM_ECDSA_SHA256 -> listOf("EC")
            else -> return null
        }
        for (factoryAlg in factoryCandidates) {
            val key = runCatching {
                KeyFactory.getInstance(factoryAlg).generatePublic(keySpec)
            }.getOrNull()
            if (key != null) return key
        }
        return null
    }

    private fun rejected(detail: String): SignatureVerifyResult.Rejected =
        SignatureVerifyResult.Rejected(PluginQuarantineReason.SIGNATURE_MISMATCH, detail)

    private fun constantTimeAsciiEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.US_ASCII), b.toByteArray(Charsets.US_ASCII))

    private fun sha256Hex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            chars[i * 2] = HEX_DIGITS[v ushr 4]
            chars[i * 2 + 1] = HEX_DIGITS[v and 0x0F]
        }
        return String(chars)
    }

    private fun hexToBytes(hex: String): ByteArray? {
        if (!SHA256_HEX_REGEX.matches(hex)) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
