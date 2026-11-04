package com.assistenteclinica.seguranca

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProtecaoKeystoreTest {
    private val alias = "teste_${Random.nextLong().toULong()}"
    private val outroAlias = "${alias}_outro"
    private val dados = "chave principal de mentira".toByteArray()

    @After fun apagar() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.deleteEntry(alias); ks.deleteEntry(outroAlias)
    }

    @Test fun protegeEAbreDeVolta() {
        val p = ProtecaoKeystore(alias)
        assertTrue(dados.contentEquals(p.abrir(p.proteger(dados))))
    }

    @Test fun aliasDiferenteNaoAbre() {
        val protegido = ProtecaoKeystore(alias).proteger(dados)
        assertThrows(Exception::class.java) { ProtecaoKeystore(outroAlias).abrir(protegido) }
    }

    @Test fun aChaveNaoSaiDoKeystore() {
        ProtecaoKeystore(alias).proteger(dados)
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertNull(ks.getKey(alias, null).encoded)
    }

    // Revisão de segurança, item E: a chave com defeito é jogada fora; o que ela protegia não abre mais, e a nova funciona.
    @Test fun recriarTrocaAChave() {
        val p = ProtecaoKeystore(alias)
        val antigo = p.proteger(dados)
        p.recriar()
        assertThrows(Exception::class.java) { p.abrir(antigo) }
        assertTrue(dados.contentEquals(p.abrir(p.proteger(dados))))
    }
}
