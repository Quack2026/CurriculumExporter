package com.quack.curriculumexporter

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 学校前端 `xC.encrypt` 的等价实现。
 *
 * 密钥是写死在学校前端 JS 里的公开值，所以这套「加密」实质上等价于明文 ——
 * 这是学校系统的设计问题，不是本 App 引入的。这里照抄一份，只为让服务器认账。
 */
object EduCrypto {

    /** 学校前端硬编码的 AES-128 密钥（正好 16 字节）。 */
    const val AES_KEY = "qzkj1kjghd=876&*"

    /**
     * AES-128-ECB + PKCS7，明文是 `JSON.stringify(密码)`，即把密码用双引号包起来。
     *
     * 只做算法本身、不套 base64，单测可以拿它直接验往返。
     */
    fun aesEcb(password: String): ByteArray {
        // AES 下 PKCS5Padding 就是 PKCS7，与 .NET 的 PaddingMode.PKCS7 等价
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(AES_KEY.toByteArray(Charsets.UTF_8), "AES")
        )
        val plain = jsonQuote(password).toByteArray(Charsets.UTF_8)
        return cipher.doFinal(plain)
    }

    /**
     * 完整的两层编码，与前端 `btoa(base64(aes(...)))` 一致：
     * 密文 → base64 字符串 → 这个字符串的 ASCII 再 base64 一次。
     */
    fun encryptPassword(password: String): String {
        val inner = Base64.getEncoder().encodeToString(aesEcb(password))
        return Base64.getEncoder().encodeToString(inner.toByteArray(Charsets.US_ASCII))
    }

    /**
     * JSON.stringify 一个字符串。
     *
     * 只处理学号密码这种没有特殊字符的情形（和 C# 版 `"\"" + pwd + "\""` 一样）：
     * 真出现引号或反斜杠，加密结果会与浏览器不同，但那本来就是非法口令。
     */
    private fun jsonQuote(s: String): String = "\"" + s + "\""
}
