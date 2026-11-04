package com.assistenteclinica.dominio

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class ResumosTest {
    private fun dia(d: Int) = LocalDate.of(2026, 11, d)
    private fun s(id: Long, inicio: LocalDateTime, terapeutaId: Long = 30) = Sessao(id, 10, 20, terapeutaId, inicio, 40, origem = Origem.FIXA)

    private val aguardando = s(1, dia(3).atTime(9, 0))
    private val confirmada = s(2, dia(3).atTime(10, 0)).copy(familia = Resposta.SIM, terapeuta = Resposta.SIM)
    private val encerrada = s(3, dia(3).atTime(11, 0)).copy(desfecho = Desfecho.REALIZADA)
    private val jaNao = s(4, dia(3).atTime(12, 0)).copy(familia = Resposta.NAO)
    private val outraTerapeuta = s(5, dia(3).atTime(9, 0), terapeutaId = 31)
    private val outroDia = s(6, dia(4).atTime(9, 0))
    private val antes = dia(2).atTime(18, 0)
    private val todas = listOf(aguardando, confirmada, encerrada, jaNao, outraTerapeuta, outroDia)

    @Test fun `terapeuta indisponivel so mexe no que vai acontecer dela naquele dia`() =
        assertEquals(
            listOf(aguardando.copy(terapeuta = Resposta.NAO), confirmada.copy(terapeuta = Resposta.NAO)),
            indisponivel(todas, 30, dia(3), antes),
        )

    @Test fun `sessao que ja comecou nao entra no dia dificil`() =
        assertEquals(listOf(confirmada.copy(terapeuta = Resposta.NAO)), indisponivel(todas, 30, dia(3), dia(3).atTime(9, 30)))

    @Test fun `clinica fechada cancela o que vai acontecer no dia`() =
        assertEquals(
            listOf(aguardando, confirmada, outraTerapeuta).map { it.copy(desfecho = Desfecho.CANCELADA_PELA_CLINICA) },
            clinicaFechada(todas, dia(3), antes),
        )

    @Test fun `quem faltou`() {
        assertEquals(QuemFaltou.FAMILIA, quemFaltou(aguardando.copy(familia = Resposta.NAO)))
        assertEquals(QuemFaltou.FAMILIA, quemFaltou(aguardando.copy(desfecho = Desfecho.FALTOU)))
        assertEquals(QuemFaltou.TERAPEUTA, quemFaltou(aguardando.copy(terapeuta = Resposta.NAO)))
        assertEquals(QuemFaltou.CLINICA, quemFaltou(aguardando.copy(desfecho = Desfecho.CANCELADA_PELA_CLINICA)))
        assertEquals(QuemFaltou.NAO_ACONTECEU, quemFaltou(aguardando.copy(desfecho = Desfecho.NAO_ACONTECEU)))
        assertEquals(null, quemFaltou(encerrada))
        assertEquals(null, quemFaltou(aguardando))
    }

    @Test fun `faltas do mes em ordem de data`() =
        assertEquals(listOf(jaNao to QuemFaltou.FAMILIA), faltasDoMes(todas))

    @Test fun `atendimentos por terapeuta so com realizadas`() {
        val outraRealizada = s(7, dia(2).atTime(9, 0)).copy(desfecho = Desfecho.REALIZADA)
        assertEquals(mapOf(30L to listOf(outraRealizada, encerrada)), atendimentosPorTerapeuta(todas + outraRealizada))
    }
}
