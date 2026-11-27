package com.Bilibili_Innocent_Lab.xposedmodule.plugin.container

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Pure-Kotlin RFC 8032 Ed25519 signature verifier.
 *
 * Used as a fail-closed fallback on Android API 27-32 where the platform JCA provider does not
 * register `Ed25519` / `EdDSA`.
 */
internal object Ed25519PureVerifier {

    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19L))
    private val L: BigInteger = BigInteger.ONE.shiftLeft(252).add(
        BigInteger("27742317777372353535851937790883648493")
    )
    private val P_MINUS_5_DIV_8: BigInteger = P.subtract(BigInteger.valueOf(5L)).shiftRight(3)
    private val TWO: BigInteger = BigInteger.valueOf(2L)
    private val D: BigInteger = BigInteger.valueOf(-121665L)
        .multiply(BigInteger.valueOf(121666L).modInverse(P))
        .mod(P)
    private val TWO_D: BigInteger = TWO.multiply(D).mod(P)
    private val SQRT_MINUS_ONE: BigInteger = TWO.modPow(
        P.subtract(BigInteger.ONE).shiftRight(2),
        P
    )

    private data class ExtPoint(
        val x: BigInteger,
        val y: BigInteger,
        val z: BigInteger,
        val t: BigInteger
    )

    private val IDENTITY = ExtPoint(
        x = BigInteger.ZERO,
        y = BigInteger.ONE,
        z = BigInteger.ONE,
        t = BigInteger.ZERO
    )

    private val BASE_POINT: ExtPoint = run {
        val by = BigInteger.valueOf(4L)
            .multiply(BigInteger.valueOf(5L).modInverse(P))
            .mod(P)
        val bx = recoverX(by, xSignBit = 0)
            ?: error("Invalid Ed25519 base point")
        ExtPoint(
            x = bx,
            y = by,
            z = BigInteger.ONE,
            t = bx.multiply(by).mod(P)
        )
    }

    /**
     * Verifies a 64-byte Ed25519 [signature64] over [message] using a 32-byte little-endian
     * compressed Edwards public key [publicKey32] according to RFC 8032 Section 5.1.7.
     */
    fun verify(publicKey32: ByteArray, message: ByteArray, signature64: ByteArray): Boolean {
        if (publicKey32.size != 32 || signature64.size != 64) {
            return false
        }
        return runCatching {
            val rBytes = signature64.copyOfRange(0, 32)
            val sBytes = signature64.copyOfRange(32, 64)

            val s = decodeLittleEndian(sBytes)
            if (s >= L) {
                return@runCatching false
            }

            val publicKeyPoint = decodePoint(publicKey32) ?: return@runCatching false
            if (isSmallOrder(publicKeyPoint)) {
                return@runCatching false
            }
            val rPoint = decodePoint(rBytes) ?: return@runCatching false

            val digest = MessageDigest.getInstance("SHA-512").apply {
                update(rBytes)
                update(publicKey32)
                update(message)
            }.digest()
            val k = decodeLittleEndian(digest).mod(L)

            val lhs = scalarMult(BASE_POINT, s)
            val rhs = addPoints(rPoint, scalarMult(publicKeyPoint, k))
            pointsEqual(lhs, rhs)
        }.getOrDefault(false)
    }

    private fun decodeLittleEndian(bytes: ByteArray): BigInteger {
        val bigEndian = ByteArray(bytes.size)
        for (i in bytes.indices) {
            bigEndian[bytes.size - 1 - i] = bytes[i]
        }
        return BigInteger(1, bigEndian)
    }

    private fun decodePoint(encoded32: ByteArray): ExtPoint? {
        if (encoded32.size != 32) return null
        val xSign = (encoded32[31].toInt() ushr 7) and 1
        val yBytes = encoded32.copyOf()
        yBytes[31] = (yBytes[31].toInt() and 0x7F).toByte()
        val y = decodeLittleEndian(yBytes)
        if (y >= P) return null
        val x = recoverX(y, xSign) ?: return null
        return ExtPoint(
            x = x,
            y = y,
            z = BigInteger.ONE,
            t = x.multiply(y).mod(P)
        )
    }

    private fun recoverX(y: BigInteger, xSignBit: Int): BigInteger? {
        val y2 = y.multiply(y).mod(P)
        val u = y2.subtract(BigInteger.ONE).mod(P)
        val v = D.multiply(y2).add(BigInteger.ONE).mod(P)
        if (v.signum() == 0) return null

        val v2 = v.multiply(v).mod(P)
        val v3 = v.multiply(v2).mod(P)
        val v4 = v2.multiply(v2).mod(P)
        val v7 = v3.multiply(v4).mod(P)

        val uv3 = u.multiply(v3).mod(P)
        val uv7 = u.multiply(v7).mod(P)

        var x = uv3.multiply(uv7.modPow(P_MINUS_5_DIV_8, P)).mod(P)
        val vx2 = v.multiply(x).mod(P).multiply(x).mod(P)
        if (vx2 != u) {
            val negU = P.subtract(u).mod(P)
            if (vx2 != negU) {
                return null
            }
            x = x.multiply(SQRT_MINUS_ONE).mod(P)
        }
        if (x.signum() == 0) {
            return if (xSignBit == 1) null else BigInteger.ZERO
        }
        val currentParity = if (x.testBit(0)) 1 else 0
        if (currentParity != xSignBit) {
            x = P.subtract(x)
        }
        return x
    }

    private fun addPoints(p1: ExtPoint, p2: ExtPoint): ExtPoint {
        val a = p1.y.subtract(p1.x).mod(P).multiply(p2.y.subtract(p2.x).mod(P)).mod(P)
        val b = p1.y.add(p1.x).mod(P).multiply(p2.y.add(p2.x).mod(P)).mod(P)
        val c = p1.t.multiply(TWO_D).mod(P).multiply(p2.t).mod(P)
        val d = p1.z.multiply(TWO).mod(P).multiply(p2.z).mod(P)
        val e = b.subtract(a).mod(P)
        val f = d.subtract(c).mod(P)
        val g = d.add(c).mod(P)
        val h = b.add(a).mod(P)
        return ExtPoint(
            x = e.multiply(f).mod(P),
            y = g.multiply(h).mod(P),
            z = f.multiply(g).mod(P),
            t = e.multiply(h).mod(P)
        )
    }

    private fun doublePoint(p: ExtPoint): ExtPoint {
        val a = p.x.multiply(p.x).mod(P)
        val b = p.y.multiply(p.y).mod(P)
        val c = TWO.multiply(p.z.multiply(p.z).mod(P)).mod(P)
        val h = a.add(b).mod(P)
        val xy = p.x.add(p.y).mod(P)
        val e = h.subtract(xy.multiply(xy).mod(P)).mod(P)
        val g = a.subtract(b).mod(P)
        val f = c.add(g).mod(P)
        return ExtPoint(
            x = e.multiply(f).mod(P),
            y = g.multiply(h).mod(P),
            z = f.multiply(g).mod(P),
            t = e.multiply(h).mod(P)
        )
    }

    private fun scalarMult(point: ExtPoint, scalar: BigInteger): ExtPoint {
        var result = IDENTITY
        val bitLen = scalar.bitLength()
        for (bit in bitLen - 1 downTo 0) {
            result = doublePoint(result)
            if (scalar.testBit(bit)) {
                result = addPoints(result, point)
            }
        }
        return result
    }

    private fun pointsEqual(p1: ExtPoint, p2: ExtPoint): Boolean {
        val lhsX = p1.x.multiply(p2.z).mod(P)
        val rhsX = p2.x.multiply(p1.z).mod(P)
        if (lhsX != rhsX) return false
        val lhsY = p1.y.multiply(p2.z).mod(P)
        val rhsY = p2.y.multiply(p1.z).mod(P)
        return lhsY == rhsY
    }

    private fun isSmallOrder(point: ExtPoint): Boolean {
        val doubled2 = doublePoint(point)
        val doubled4 = doublePoint(doubled2)
        val doubled8 = doublePoint(doubled4)
        return pointsEqual(doubled8, IDENTITY)
    }
}
