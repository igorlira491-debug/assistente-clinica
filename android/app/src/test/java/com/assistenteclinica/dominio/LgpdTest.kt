package com.assistenteclinica.dominio

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LgpdTest {
    private val em = LocalDateTime.of(2026, 11, 5, 14, 5)

    @Test fun textoDaExportacaoComTudoDaCrianca() {
        val d = DadosDaCrianca(
            "João", TipoCliente.FIXO, "", ativa = false,
            listOf(TerapiaExportada("Fono", "Carla")),
            listOf(ResponsavelExportado("Maria", "5511988880050", true, LocalDate.of(2026, 11, 1), "v1")),
            listOf(HorarioExportado(DayOfWeek.MONDAY, LocalTime.of(14, 0), "Fono", ativo = false)),
            listOf(SessaoExportada(em, "Fono", "Carla", TipoSessao.COMUM, Desfecho.FALTOU)),
            emptyList(),
        )
        val t = textoDaExportacao(d, em)
        listOf(
            "Dados de João guardados no app Assistente", "Gerado em 05/11/2026 14:05", "- Situação: inativa", "- Observação: (nenhuma)",
            "- Fono, com Carla", "- Maria, 5511988880050 (principal); consentimento em 01/11/2026, termo v1",
            "- segunda-feira às 14:00, Fono (desativado)", "- 05/11/2026 14:05, Fono com Carla, comum: faltou",
            "PENDÊNCIAS (0)\n- (nenhum)",
        ).forEach { assertTrue("faltou \"$it\" em:\n$t", t.contains(it)) }
    }

    @Test fun apagarSoComAPalavraCerta() {
        assertTrue(confirmouApagar("APAGAR"))
        assertTrue(confirmouApagar(" APAGAR "))
        assertFalse(confirmouApagar("apagar"))
        assertFalse(confirmouApagar(""))
    }

    @Test fun apagarFicaNoRegistroSemNome() {
        assertEquals("apagou a pedido a criança 12 (3 sessões)", textoDoRegistro(Registro(em, 1, Acao.APAGOU_A_PEDIDO, Alvo.CRIANCA, 12, 12, quantidade = 3)))
        assertEquals("exportou os dados da criança João",
            textoComNomes(Registro(em, 1, Acao.EXPORTOU_OS_DADOS, Alvo.CRIANCA, 12, 12), NomesDoRegistro(criancas = mapOf(12L to "João"))).substringAfterLast(" · "))
    }
}
