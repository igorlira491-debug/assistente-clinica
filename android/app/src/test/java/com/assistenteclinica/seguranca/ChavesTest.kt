package com.assistenteclinica.seguranca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// Faz o papel do Keystore: XOR com um segredo e uma marca na frente; outro aparelho lança exceção, como o real.
private class ProtecaoFalsa(private val segredo: Byte) : ProtecaoDoAparelho {
    override fun proteger(dados: ByteArray) = byteArrayOf(segredo) + dados.map { (it.toInt() xor segredo.toInt()).toByte() }
    override fun abrir(dados: ByteArray): ByteArray {
        check(dados.first() == segredo) { "protegido por outro aparelho" }
        return dados.drop(1).map { (it.toInt() xor segredo.toInt()).toByte() }.toByteArray()
    }
}

class ChavesTest {
    private val rapido = 1_000
    private val aparelho = ProtecaoFalsa(1)
    private val chave = Chaves.novaChavePrincipal()

    @Test fun `pin certo devolve a mesma chave`() {
        val e = Chaves.embrulharComPin(chave, "482913", aparelho, rapido)
        assertTrue(chave.contentEquals(Chaves.abrirComPin(e, "482913", aparelho)))
    }

    @Test fun `pin errado devolve null`() {
        val e = Chaves.embrulharComPin(chave, "482913", aparelho, rapido)
        assertNull(Chaves.abrirComPin(e, "482914", aparelho))
    }

    @Test fun `outro aparelho nao abre e avisa que o problema e o aparelho`() {
        val e = Chaves.embrulharComPin(chave, "482913", aparelho, rapido)
        assertThrows(FalhaDoAparelho::class.java) { Chaves.abrirComPin(e, "482913", ProtecaoFalsa(2)) }
    }

    @Test fun `senha de backup abre sem o aparelho`() {
        val e = Chaves.embrulharComSenha(chave, "cavalo-azul-77", rapido)
        assertTrue(chave.contentEquals(Chaves.abrirComSenha(e, "cavalo-azul-77")))
    }

    @Test fun `senha errada devolve null`() {
        val e = Chaves.embrulharComSenha(chave, "cavalo-azul-77", rapido)
        assertNull(Chaves.abrirComSenha(e, "cavalo-azul-78"))
    }

    @Test fun `dois embrulhos do mesmo pin sao diferentes`() {
        val a = Chaves.embrulharComPin(chave, "482913", aparelho, rapido)
        val b = Chaves.embrulharComPin(chave, "482913", aparelho, rapido)
        assertFalse(a.sal.contentEquals(b.sal)); assertFalse(a.cifrado.contentEquals(b.cifrado))
    }

    @Test fun `chave principal tem 32 bytes e nao se repete`() {
        val outra = Chaves.novaChavePrincipal()
        assertEquals(32, chave.size); assertNotNull(outra); assertFalse(chave.contentEquals(outra))
    }
}
