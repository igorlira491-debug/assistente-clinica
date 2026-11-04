package com.assistenteclinica.seguranca

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// A 2ª tranca: uma chave AES que nasce e fica dentro do Android Keystore e nunca sai do aparelho.
// Sem exigir autenticação do usuário, porque o app não usa digital (§6.1); quem identifica a pessoa é o PIN.
// Só funciona com o celular desbloqueado (revisão de segurança, item E): com o celular roubado e travado, nem
// rodando código nele dá para usar a chave para testar PINs. Vale para chaves novas (instalação nova).
class ProtecaoKeystore(private val alias: String = "assistente_protecao") : ProtecaoDoAparelho {

    // Saída: iv (12 bytes) + cifrado. O próprio Keystore sorteia o iv a cada proteção.
    override fun proteger(dados: ByteArray): ByteArray {
        val cifra = Cipher.getInstance(TRANSFORMACAO)
        cifra.init(Cipher.ENCRYPT_MODE, chave())
        return cifra.iv + cifra.doFinal(dados)
    }

    override fun abrir(dados: ByteArray): ByteArray {
        val cifra = Cipher.getInstance(TRANSFORMACAO)
        cifra.init(Cipher.DECRYPT_MODE, chave(), GCMParameterSpec(BITS_TAG, dados, 0, BYTES_IV))
        return cifra.doFinal(dados, BYTES_IV, dados.size - BYTES_IV)
    }

    override fun recriar() = synchronized(ProtecaoKeystore::class) {
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(alias)
    }

    private fun chave(): SecretKey = synchronized(ProtecaoKeystore::class) {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(alias, null) as SecretKey?) ?: criarChave()
    }

    private fun criarChave(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUnlockedDeviceRequired(true)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMACAO = "AES/GCM/NoPadding"
        const val BYTES_IV = 12
        const val BITS_TAG = 128
    }
}
