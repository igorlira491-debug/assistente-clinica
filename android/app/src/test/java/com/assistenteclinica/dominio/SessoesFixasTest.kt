package com.assistenteclinica.dominio

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessoesFixasTest {
    private val novembro = YearMonth.of(2026, 11) // 01/11/2026 é domingo
    private val antes = LocalDate.of(2026, 10, 20)
    private val terca14 = HorarioFixo(1, 10, 20, 30, DayOfWeek.TUESDAY, LocalTime.of(14, 0), 30)

    @Test fun `gera as 4 tercas de novembro`() {
        val r = sessoesFixasDoMes(listOf(terca14), emptyList(), novembro, antes)
        assertEquals(listOf(3, 10, 17, 24), r.map { it.inicio.dayOfMonth })
        assertTrue(r.all { it.origem == Origem.FIXA && it.terapeutaId == 30L && it.id == 0L && it.duracaoMin == 30 })
    }

    @Test fun `dezembro tem 5 tercas`() =
        assertEquals(listOf(1, 8, 15, 22, 29),
            sessoesFixasDoMes(listOf(terca14), emptyList(), YearMonth.of(2026, 12), antes).map { it.inicio.dayOfMonth })

    @Test fun `rodar de novo nao duplica`() {
        val primeira = sessoesFixasDoMes(listOf(terca14), emptyList(), novembro, antes)
        assertEquals(emptyList<Sessao>(), sessoesFixasDoMes(listOf(terca14), primeira, novembro, antes))
    }

    @Test fun `sessao cancelada nao e recriada`() {
        val cancelada = sessoesFixasDoMes(listOf(terca14), emptyList(), novembro, antes).first().copy(familia = Resposta.NAO)
        val r = sessoesFixasDoMes(listOf(terca14), listOf(cancelada), novembro, antes)
        assertFalse(r.any { it.inicio == cancelada.inicio })
        assertEquals(3, r.size)
    }

    @Test fun `horario inativo nao gera`() =
        assertEquals(emptyList<Sessao>(),
            sessoesFixasDoMes(listOf(terca14.copy(ativo = false)), emptyList(), novembro, antes))

    @Test fun `mes ja comecado nao cria dias passados e hoje entra`() =
        assertEquals(listOf(10, 17, 24),
            sessoesFixasDoMes(listOf(terca14), emptyList(), novembro, LocalDate.of(2026, 11, 10)).map { it.inicio.dayOfMonth })

    @Test fun `lancar dezembro no dia 20 de novembro as 9h`() =
        assertEquals(LocalDateTime.of(2026, 11, 20, 9, 0), momentoLancarAgenda(YearMonth.of(2026, 12)))
}
