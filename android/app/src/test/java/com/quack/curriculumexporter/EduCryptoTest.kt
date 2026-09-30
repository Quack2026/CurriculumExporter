package com.quack.curriculumexporter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 密码加密要是错了，表现是「服务器说登录失败」，极难排查，
 * 所以这里把两层编码的每一层都拆开验一遍。
 */
class EduCryptoTest {

    private fun decrypt(cipherBytes: ByteArray): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(EduCrypto.AES_KEY.toByteArray(Charsets.UTF_8), "AES"),
        )
        return String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    }

    @Test
    fun `AES 的明文是加了双引号的口令（等价 JSON stringify）`() {
        assertEquals("\"123456\"", decrypt(EduCrypto.aesEcb("123456")))
    }

    @Test
    fun `同一个口令每次加密结果都一样（ECB 没有随机 IV）`() {
        assertEquals(EduCrypto.encryptPassword("abc123"), EduCrypto.encryptPassword("abc123"))
    }

    @Test
    fun `两层 base64 都能原样解回密文`() {
        val encoded = EduCrypto.encryptPassword("p@ssw0rd")
        // 外层：btoa 的那个 base64
        val inner = String(Base64.getDecoder().decode(encoded), Charsets.US_ASCII)
        // 内层：密文的 base64
        val cipherBytes = Base64.getDecoder().decode(inner)
        assertEquals("\"p@ssw0rd\"", decrypt(cipherBytes))
    }

    @Test
    fun `不同口令得到不同密文`() {
        assertNotEquals(EduCrypto.encryptPassword("a"), EduCrypto.encryptPassword("b"))
    }
}
