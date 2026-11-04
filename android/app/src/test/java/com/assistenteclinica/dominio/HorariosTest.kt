package com.assistenteclinica.dominio

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class HorariosTest {
    private val cedo = LocalDateTime.of(2026, 10, 1, 8, 0) // bem antes de tudo
    private fun dt(d: Int, m: Int, h: Int, min: Int = 0) = LocalDateTime.of(2026, m, d, h, min)

    @Test fun `quinta confirma na quarta as 9h`() =
        assertEquals(dt(4, 11, 9), momentoConfirmacaoFamilia(dt(5, 11, 14), cedo)) // 05/11/2026 é quinta

    @Test fun `segunda confirma na sexta anterior as 9h`() =
        assertEquals(dt(6, 11, 9), momentoConfirmacaoFamilia(dt(9, 11, 10), cedo)) // 09/11 segunda → 06/11 sexta

    @Test fun `marcada depois do horario confirma agora`() {
        val agora = dt(4, 11, 17)
        assertEquals(agora, momentoConfirmacaoFamilia(dt(5, 11, 10), agora))
    }

    @Test fun `escalada as 16h do dia da confirmacao`() =
        assertEquals(dt(6, 11, 16), momentoEscalada(dt(9, 11, 10), cedo))

    @Test fun `escalada passada vira agora`() {
        val agora = dt(4, 11, 18)
        assertEquals(agora, momentoEscalada(dt(5, 11, 10), agora))
    }

    @Test fun `como foi e no fim da sessao`() =
        assertEquals(dt(5, 11, 14, 30),
            momentoComoFoi(Sessao(1, 1, 1, 1, dt(5, 11, 14), 30, origem = Origem.AVULSA)))

    private fun sess(id: Long, terapeuta: Long, d: Int, h: Int) =
        Sessao(id, id, 20, terapeuta, dt(d, 11, h), 30, origem = Origem.FIXA)
    private val quinta = LocalDate.of(2026, 11, 5)

    @Test fun `aviso junta por terapeuta so o que a familia confirmou`() {
        val r = avisosParaTerapeutas(listOf(
            sess(1, 30, 5, 15).copy(familia = Resposta.SIM),
            sess(2, 30, 5, 14).copy(familia = Resposta.SIM),
            sess(3, 31, 5, 14).copy(familia = Resposta.SIM),
            sess(4, 30, 5, 16), // família ainda não respondeu
        ), quinta)
        assertEquals(mapOf(30L to listOf(2L, 1L), 31L to listOf(3L)), r.mapValues { (_, v) -> v.map { it.id } })
    }

    @Test fun `aviso ignora terapeuta ja respondida outro dia e encerrada`() {
        val r = avisosParaTerapeutas(listOf(
            sess(1, 30, 5, 14).copy(familia = Resposta.SIM, terapeuta = Resposta.SIM),
            sess(2, 30, 6, 14).copy(familia = Resposta.SIM),
            sess(3, 30, 5, 15).copy(familia = Resposta.SIM, desfecho = Desfecho.FALTOU),
        ), quinta)
        assertEquals(emptyMap<Long, List<Sessao>>(), r)
    }

    @Test fun `sem sessoes nao ha aviso`() =
        assertEquals(emptyMap<Long, List<Sessao>>(), avisosParaTerapeutas(emptyList(), quinta))
}
