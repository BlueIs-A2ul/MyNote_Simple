package com.mynote.app.data.settings

/** API Key 本地加解密抽象：生产走 Keystore，测试注入假实现（Robolectric 不支持 AndroidKeyStore 加解密）。 */
interface ApiKeyCipher {
    /** 加密失败返回 null。 */
    fun encrypt(plain: String): String?

    /** 密文损坏 / 密钥失效返回 null。 */
    fun decrypt(stored: String): String?
}
