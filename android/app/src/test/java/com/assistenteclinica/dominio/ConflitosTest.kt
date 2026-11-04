package com.assistenteclinica.dominio

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class ConflitosTest {
    private fun s(id: Long, crianca: Long, terapeuta: Long, h: Int, m: Int = 0) =
        Sessao(id, crianca, 20, terapeuta, LocalDateTime.of(2026, 11, 5, h, m), 30, origem = Origem.AVULSA)

    @Test fun `mesma terapeuta sobreposta avisa`() =
        assertEquals(listOf(TipoConflito.MESMA_TERAPEUTA), conflitos(s(0, 1, 30, 14, 15), listOf(s(5, 2, 30, 14))).map { it.tipo })

    @Test fun `mesma crianca sobreposta avisa`() =
        assertEquals(listOf(TipoConflito.MESMA_CRIANCA), conflitos(s(0, 1, 31, 14, 15), listOf(s(5, 1, 30, 14))).map { it.tipo })

    @Test fun `sessoes encostadas nao conflitam`() =
        assertEquals(emptyList<Conflito>(), conflitos(s(0, 1, 30, 14, 30), listOf(s(5, 2, 30, 14))))

    @Test fun `sessao com nao ou encerrada nao ocupa horario`() {
        val recusada = s(5, 2, 30, 14).copy(terapeuta = Resposta.NAO)
        val encerrada = s(6, 3, 30, 14).copy(desfecho = Desfecho.FALTOU)
        assertEquals(emptyList<Conflito>(), conflitos(s(0, 1, 30, 14), listOf(recusada, encerrada)))
    }

    @Test fun `editar a propria sessao nao conflita consigo`() =
        assertEquals(emptyList<Conflito>(), conflitos(s(5, 1, 30, 14, 10), listOf(s(5, 1, 30, 14))))
}
