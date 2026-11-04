package com.assistenteclinica.seguranca

import com.assistenteclinica.dominio.Conferencia
import com.assistenteclinica.dominio.Papel
import com.assistenteclinica.dominio.Usuaria
import java.time.LocalDateTime
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArquivoDeBackupTest {
    private val senha = "cavalo-azul-77-teste"
    private val chaveDeBackup = Chaves.novaChavePrincipal()
    private val embrulho = Chaves.embrulharComSenha(chaveDeBackup, senha, iteracoes = 1_000)
    private val bancoFalso = "SQLCipher de mentira".toByteArray() + Random.nextBytes(4096)
    private val conteudo = ConteudoDoBackup(
        versaoDoBanco = 6,
        criadoEm = LocalDateTime.of(2026, 10, 29, 8, 12),
        usuarias = listOf(Usuaria(1, "Dona Teste", Papel.DONA), Usuaria(2, "Secretária Teste", Papel.SECRETARIA, ativa = false)),
        conferencia = Conferencia(mapOf("Crianca" to 12, "Sessao" to 340), "K7QF-2M9D"),
        chaveDoBanco = Random.nextBytes(32),
        banco = bancoFalso,
    )
    private val arquivo = ArquivoDeBackup.montar(conteudo, chaveDeBackup, embrulho)

    private fun abrir(a: ByteArray = arquivo, s: String = senha, versaoDoApp: Int = 6) = ArquivoDeBackup.abrir(a, s, versaoDoApp)

    @Test fun abreComASenhaETrazTudoDeVolta() {
        val r = abrir() as AberturaDoBackup.Ok
        assertEquals(6, r.conteudo.versaoDoBanco)
        assertEquals(conteudo.criadoEm, r.conteudo.criadoEm)
        assertEquals(conteudo.usuarias, r.conteudo.usuarias)
        assertEquals(conteudo.conferencia, r.conteudo.conferencia)
        assertArrayEquals(conteudo.chaveDoBanco, r.conteudo.chaveDoBanco)
        assertArrayEquals(bancoFalso, r.conteudo.banco)
    }

    @Test fun senhaErradaNaoAbre() {
        assertEquals(AberturaDoBackup.SenhaErrada, abrir(s = "outra-senha-qualquer"))
    }

    // Nada do conteúdo em texto claro: nem nomes, nem o código de conferência, nem a data, nem o banco.
    @Test fun nadaDoConteudoFicaEmClaro() {
        val texto = String(arquivo, Charsets.ISO_8859_1)
        listOf("Dona Teste", "Secretária", "Secret", "K7QF", "2M9D", "Crianca", "Sessao", "2026-10-29", "SQLCipher de mentira")
            .forEach { assertFalse("o arquivo tem \"$it\" em claro", it in texto) }
    }

    // A integridade vem do AES-GCM: um byte trocado em qualquer ponto do conteúdo cifrado e o arquivo não abre.
    @Test fun qualquerByteTrocadoNoConteudoDaDanificado() {
        listOf(arquivo.size - 1, arquivo.size - 100, arquivo.size / 2, arquivo.size - bancoFalso.size).forEach { i ->
            val mexido = arquivo.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertEquals("byte $i", AberturaDoBackup.Danificado, abrir(mexido))
        }
    }

    // O cabeçalho entra na etiqueta: mexer nele também impede de abrir (senha errada ou danificado, nunca "Ok").
    @Test fun cabecalhoMexidoNaoAbre() {
        (15 until 120).forEach { i ->
            val mexido = arquivo.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertFalse("byte $i", abrir(mexido) is AberturaDoBackup.Ok)
        }
    }

    @Test fun arquivoCortadoDaDanificado() {
        listOf(arquivo.size - 1, arquivo.size / 2, 30).forEach { n ->
            val r = abrir(arquivo.copyOf(n))
            assertTrue("cortado em $n: $r", r == AberturaDoBackup.Danificado || r == AberturaDoBackup.SenhaErrada)
        }
    }

    @Test fun outroArquivoNaoEBackup() {
        assertEquals(AberturaDoBackup.NaoEBackup, abrir(Random.nextBytes(500)))
        assertEquals(AberturaDoBackup.NaoEBackup, abrir(ByteArray(0)))
        assertEquals(AberturaDoBackup.NaoEBackup, abrir("SQLite format 3".toByteArray()))
    }

    @Test fun backupDeBancoMaisNovoQueOAppNaoAbre() {
        assertEquals(AberturaDoBackup.VersaoMaisNova(6), abrir(versaoDoApp = 5))
        assertTrue(abrir(versaoDoApp = 7) is AberturaDoBackup.Ok) // mais antigo que o app: abre (o app migra depois)
    }

    @Test fun formatoMaisNovoNaoAbre() {
        val marca = "ASSISTENTE-BACKUP".length
        val futuro = arquivo.copyOf().also { it[marca + 3] = (ArquivoDeBackup.FORMATO + 1).toByte() }
        assertEquals(AberturaDoBackup.VersaoMaisNova(ArquivoDeBackup.FORMATO + 1), abrir(futuro))
    }

    @Test fun cadaBackupTemChaveENonceProprios() {
        val outro = ArquivoDeBackup.montar(conteudo, chaveDeBackup, embrulho)
        assertFalse(outro.contentEquals(arquivo))
        assertTrue(abrir(outro) is AberturaDoBackup.Ok)
    }
}
