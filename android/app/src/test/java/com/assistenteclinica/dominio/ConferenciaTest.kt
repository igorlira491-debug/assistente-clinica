package com.assistenteclinica.dominio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenciaTest {
    private val criancas = TabelaLida("Crianca", listOf(listOf(1L, "Criança Teste A", "FIXO", "", 1L), listOf(2L, "Criança Teste B", "AVULSO", "", 1L)))
    private val sessoes = TabelaLida("Sessao", listOf(listOf(1L, 1L, 1793710800L, null)))

    @Test fun mesmoConteudoMesmoCodigoEmQualquerOrdemDeTabela() {
        assertEquals(conferencia(listOf(criancas, sessoes)), conferencia(listOf(sessoes, criancas)))
    }

    @Test fun codigoTemOitoLetrasSemLetrasQueConfundem() {
        val c = conferencia(listOf(criancas, sessoes)).codigo
        assertTrue(c, Regex("[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}").matches(c))
    }

    @Test fun qualquerMudancaMudaOCodigo() {
        val base = conferencia(listOf(criancas, sessoes)).codigo
        val outroNome = TabelaLida("Crianca", listOf(listOf(1L, "Criança Teste A", "FIXO", "", 1L), listOf(2L, "Criança Teste C", "AVULSO", "", 1L)))
        val desativada = TabelaLida("Crianca", listOf(listOf(1L, "Criança Teste A", "FIXO", "", 1L), listOf(2L, "Criança Teste B", "AVULSO", "", 0L)))
        val semUma = TabelaLida("Crianca", listOf(listOf(1L, "Criança Teste A", "FIXO", "", 1L)))
        listOf(outroNome, desativada, semUma).forEach { assertNotEquals(base, conferencia(listOf(it, sessoes)).codigo) }
    }

    // Tipo e tamanho entram no código: vazio, nulo e o texto "null" não se confundem, nem valores que, colados, dariam o mesmo texto.
    @Test fun nuloVazioETextoNaoSeConfundem() {
        val codigos = listOf<Any?>(null, "", "null", 0L, "0").map { conferencia(listOf(TabelaLida("Configuracao", listOf(listOf("chave", it))))).codigo }
        assertEquals(codigos.size, codigos.toSet().size)
        val juntos = conferencia(listOf(TabelaLida("Configuracao", listOf(listOf("ab", "c"))))).codigo
        val separados = conferencia(listOf(TabelaLida("Configuracao", listOf(listOf("a", "bc"))))).codigo
        assertNotEquals(juntos, separados)
    }

    @Test fun quantidadesPorTabelaEmTextoSoAsQueATelaMostra() {
        val c = conferencia(listOf(criancas, sessoes, TabelaLida("CriancaTerapia", listOf(listOf(1L, 1L, 1L)))))
        assertEquals(mapOf("Crianca" to 2, "CriancaTerapia" to 1, "Sessao" to 1), c.quantidades)
        assertEquals("2 crianças · 1 sessão", quantidadesEmTexto(c))
    }
}
