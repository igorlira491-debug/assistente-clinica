package com.assistenteclinica.dominio

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class PendenciasCalculadasTest {
    private fun dia(d: Int) = LocalDate.of(2026, 11, d) // 01/11/2026 é domingo
    private fun s(inicio: LocalDateTime, id: Long = 1, terapeutaId: Long = 30) =
        Sessao(id, 10, 20, terapeutaId, inicio, 40, origem = Origem.AVULSA)
    private fun tipos(sessoes: List<Sessao>, agora: LocalDateTime, lancar: Boolean = false) =
        pendenciasCalculadas(sessoes, agora, lancar).map { it.tipo }

    private val terca14 = s(dia(3).atTime(14, 0))

    @Test fun `confirmar aparece na vespera as 9h com prazo as 16h`() {
        assertEquals(emptyList<TipoPendencia>(), tipos(listOf(terca14), dia(2).atTime(8, 59)))
        val p = pendenciasCalculadas(listOf(terca14), dia(2).atTime(9, 0), false).single()
        assertEquals(TipoPendencia.ENVIAR_CONFIRMACAO, p.tipo)
        assertEquals(dia(2).atTime(16, 0), p.prazo)
        assertEquals(listOf(terca14), p.sessoes)
    }

    @Test fun `sessao de segunda confirma na sexta`() {
        val segunda = s(dia(9).atTime(14, 0))
        assertEquals(emptyList<TipoPendencia>(), tipos(listOf(segunda), dia(6).atTime(8, 59)))
        assertEquals(listOf(TipoPendencia.ENVIAR_CONFIRMACAO), tipos(listOf(segunda), dia(6).atTime(9, 0)))
    }

    @Test fun `familia ja respondeu ou sessao remarcada nao pede confirmar nem como foi`() {
        val agora = dia(2).atTime(10, 0)
        assertEquals(listOf(TipoPendencia.AVISAR_TERAPEUTA), tipos(listOf(terca14.copy(familia = Resposta.SIM)), agora))
        val remarcada = terca14.copy(familia = Resposta.NAO) // original de uma remarcação (Bloco 4)
        assertEquals(emptyList<TipoPendencia>(), tipos(listOf(remarcada), agora))
        assertEquals(emptyList<TipoPendencia>(), tipos(listOf(remarcada), dia(3).atTime(15, 0)))
    }

    @Test fun `avisar terapeuta junta as sessoes do dia dela`() {
        val a = terca14.copy(familia = Resposta.SIM)
        val b = s(dia(3).atTime(15, 0), id = 2).copy(familia = Resposta.SIM)
        val outra = s(dia(3).atTime(15, 0), id = 3, terapeutaId = 31).copy(familia = Resposta.SIM)
        val r = pendenciasCalculadas(listOf(b, a, outra), dia(2).atTime(10, 0), false).filter { it.terapeutaId == 30L }.single()
        assertEquals(TipoPendencia.AVISAR_TERAPEUTA, r.tipo)
        assertEquals(listOf(a, b), r.sessoes)
        assertEquals(dia(2).atTime(18, 0), r.prazo)
    }

    @Test fun `avisar terapeuta vence as 18h da vespera se a familia confirmou cedo`() {
        val a = terca14.copy(familia = Resposta.SIM, familiaConfirmouEm = dia(2).atTime(10, 0))
        assertEquals(dia(2).atTime(18, 0), prazoAvisarTerapeuta(listOf(a)))
    }

    @Test fun `avisar terapeuta vence 2h depois de uma confirmacao tardia`() {
        val a = terca14.copy(familia = Resposta.SIM, familiaConfirmouEm = dia(2).atTime(17, 0))
        assertEquals(dia(2).atTime(19, 0), prazoAvisarTerapeuta(listOf(a)))
        // Exatamente às 16h: 18h dos dois lados.
        assertEquals(dia(2).atTime(18, 0), prazoAvisarTerapeuta(listOf(a.copy(familiaConfirmouEm = dia(2).atTime(16, 0)))))
    }

    @Test fun `avisar terapeuta usa a ultima confirmacao do grupo`() {
        val a = terca14.copy(familia = Resposta.SIM, familiaConfirmouEm = dia(2).atTime(10, 0))
        val b = s(dia(3).atTime(15, 0), id = 2).copy(familia = Resposta.SIM, familiaConfirmouEm = dia(2).atTime(17, 30))
        val r = pendenciasCalculadas(listOf(a, b), dia(2).atTime(17, 40), false).single { it.tipo == TipoPendencia.AVISAR_TERAPEUTA }
        assertEquals(dia(2).atTime(19, 30), r.prazo)
    }

    @Test fun `avisar terapeuta de segunda vence na sexta as 18h`() {
        val segunda = s(dia(9).atTime(14, 0)).copy(familia = Resposta.SIM, familiaConfirmouEm = dia(6).atTime(9, 30))
        assertEquals(dia(6).atTime(18, 0), prazoAvisarTerapeuta(listOf(segunda)))
    }

    @Test fun `como foi aparece quando a sessao termina`() {
        assertEquals(emptyList<TipoPendencia>(), tipos(listOf(terca14.copy(familia = Resposta.SIM, terapeuta = Resposta.SIM)), dia(3).atTime(14, 39)))
        val p = pendenciasCalculadas(listOf(terca14), dia(3).atTime(14, 40), false).single()
        assertEquals(TipoPendencia.REGISTRAR_COMO_FOI, p.tipo)
        assertEquals(dia(4).atTime(14, 40), p.prazo)
        assertEquals(emptyList<TipoPendencia>(), tipos(listOf(terca14.copy(desfecho = Desfecho.REALIZADA)), dia(3).atTime(15, 0)))
        assertEquals(emptyList<TipoPendencia>(), tipos(listOf(terca14.copy(terapeuta = Resposta.NAO)), dia(3).atTime(15, 0)))
    }

    @Test fun `lancar agenda a partir do dia 20 se houver o que lancar`() {
        assertEquals(emptyList<TipoPendencia>(), tipos(emptyList(), dia(19).atTime(10, 0), lancar = true))
        val p = pendenciasCalculadas(emptyList(), dia(20).atTime(9, 0), true).single()
        assertEquals(TipoPendencia.LANCAR_AGENDA_DO_MES, p.tipo)
        assertEquals(dia(25).atTime(9, 0), p.prazo)
        assertEquals(emptyList<TipoPendencia>(), tipos(emptyList(), dia(20).atTime(9, 0), lancar = false))
    }

    @Test fun `grupos por prazo`() {
        val agora = dia(3).atTime(10, 0)
        assertEquals(Grupo.ATRASADA, grupo(dia(2).atTime(10, 0), agora, jaDisponivel = true))
        assertEquals(Grupo.ATRASADA, grupo(dia(3).atTime(9, 0), agora, jaDisponivel = false))
        assertEquals(Grupo.HOJE, grupo(dia(3).atTime(23, 0), agora, jaDisponivel = false))
        assertEquals(Grupo.PROXIMA, grupo(dia(4).atTime(9, 0), agora, jaDisponivel = false))
        // "Como foi?" de hoje cedo vence amanhã, mas já pode ser feito: fica em "Para hoje".
        assertEquals(Grupo.HOJE, grupo(dia(4).atTime(9, 0), agora, jaDisponivel = true))
    }
}
