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

class AgendaTest {
    private fun dia(d: Int, m: Int = 11) = LocalDate.of(2026, m, d) // 01/11/2026 é domingo
    private fun sessao(inicio: LocalDateTime, origem: Origem = Origem.FIXA, familia: Resposta = Resposta.AGUARDANDO, desfecho: Desfecho? = null) =
        Sessao(1, 10, 20, 30, inicio, 40, familia = familia, origem = origem, desfecho = desfecho)

    @Test fun `plural de sessoes`() {
        assertEquals("1 sessão", sessoesEmTexto(1))
        assertEquals("2 sessões", sessoesEmTexto(2))
        assertEquals("0 sessões", sessoesEmTexto(0))
        assertEquals("1 sessão criada", sessoesEmTexto(1, criada = true))
        assertEquals("4 sessões criadas", sessoesEmTexto(4, criada = true))
    }

    @Test fun `semana vai de segunda a domingo`() {
        val semana = semanaDe(dia(5)) // quinta
        assertEquals(dia(2), semana.first())
        assertEquals(dia(8), semana.last())
        assertEquals(7, semana.size)
        assertEquals(semana, semanaDe(dia(8))) // domingo fica na mesma semana
    }

    @Test fun `mes para lancar vira no dia 20`() {
        assertEquals(YearMonth.of(2026, 10), mesParaLancar(dia(19, 10)))
        assertEquals(YearMonth.of(2026, 11), mesParaLancar(dia(20, 10)))
        assertEquals(YearMonth.of(2027, 1), mesParaLancar(dia(20, 12)))
    }

    @Test fun `so apaga sessao nao fixa sem resposta`() {
        val inicio = dia(3).atTime(14, 0)
        assertTrue(podeApagar(sessao(inicio, Origem.AVULSA)))
        assertFalse(podeApagar(sessao(inicio, Origem.AVULSA, familia = Resposta.SIM)))
        assertFalse(podeApagar(sessao(inicio, Origem.FIXA)))
        assertFalse(podeApagar(sessao(inicio, Origem.REMARCACAO, desfecho = Desfecho.REALIZADA)))
    }

    @Test fun `desativar horario tira so as futuras sem resposta dele`() {
        val terca14 = HorarioFixo(1, 10, 20, 30, DayOfWeek.TUESDAY, LocalTime.of(14, 0), 40)
        val tirar = sessao(dia(10).atTime(14, 0))
        val sessoes = listOf(
            sessao(dia(3).atTime(14, 0)), // já passou
            tirar,
            sessao(dia(17).atTime(14, 0), familia = Resposta.SIM), // respondida
            sessao(dia(10).atTime(15, 0)), // outra hora
            sessao(dia(10).atTime(14, 0), Origem.AVULSA),
        )
        assertEquals(listOf(tirar), sessoesDoHorarioParaTirar(terca14, sessoes, dia(3).atTime(15, 0)))
    }

    @Test fun `data bonita em portugues`() = assertEquals("ter, 03/11/2026", dataBonita(dia(3)))

    @Test fun `sessao lancada depois do horario ganha a marca`() {
        val agora = dia(5).atTime(19, 0)
        val passada = sessao(dia(5).atTime(14, 0), origem = Origem.AVULSA)
        val marcada = marcarSeLancadaDepois(passada, agora, usuariaId = 2)
        assertEquals(agora, marcada.lancadaDepoisEm)
        assertEquals(2L, marcada.lancadaPor)
        val futura = sessao(dia(5).atTime(20, 0), origem = Origem.AVULSA)
        assertEquals(futura, marcarSeLancadaDepois(futura, agora, usuariaId = 2))
    }

    @Test fun `remarcar nao leva as marcas para a nova sessao`() {
        val original = sessao(dia(5).atTime(14, 0), origem = Origem.AVULSA)
            .copy(familia = Resposta.SIM, familiaConfirmouEm = dia(4).atTime(10, 0), lancadaDepoisEm = dia(5).atTime(19, 0), lancadaPor = 2)
        val nova = remarcar(original, dia(6).atTime(10, 0), QuemPediu.FAMILIA).second
        assertEquals(null, nova.familiaConfirmouEm)
        assertEquals(null, nova.lancadaDepoisEm)
        assertEquals(null, nova.lancadaPor)
    }

    @Test fun `remarcar guarda a original e cria a nova`() {
        val original = Sessao(7, 10, 20, 30, dia(3).atTime(14, 0), 40, origem = Origem.FIXA, tipo = TipoSessao.ANAMNESE)
        val quarta10 = dia(4).atTime(10, 0)

        val (antiga, nova) = remarcar(original, quarta10, QuemPediu.FAMILIA)
        assertEquals(original.copy(familia = Resposta.NAO), antiga)
        assertEquals(Sessao(0, 10, 20, 30, quarta10, 40, origem = Origem.REMARCACAO, tipo = TipoSessao.ANAMNESE), nova)

        assertEquals(original.copy(terapeuta = Resposta.NAO), remarcar(original, quarta10, QuemPediu.TERAPEUTA).first)
        assertEquals(original.copy(desfecho = Desfecho.CANCELADA_PELA_CLINICA), remarcar(original, quarta10, QuemPediu.CLINICA).first)
    }

    @Test fun `horario editado volta so nas semanas que sairam`() {
        val quarta14 = HorarioFixo(1, 10, 20, 31, DayOfWeek.WEDNESDAY, LocalTime.of(14, 0), 50)
        val tiradas = listOf(sessao(dia(10).atTime(14, 0)), sessao(dia(24).atTime(14, 0))) // a terça 17 ficou (respondida)
        val jaNaQuarta = sessao(dia(25).atTime(14, 0)) // não duplica
        val r = sessoesMovidas(tiradas, quarta14, listOf(jaNaQuarta), dia(3).atTime(15, 0))
        assertEquals(listOf(dia(11).atTime(14, 0)), r.map { it.inicio })
        assertEquals(Sessao(0, 10, 20, 31, dia(11).atTime(14, 0), 50, origem = Origem.FIXA), r.single())
        // Semana em que o novo dia já passou não recria.
        assertEquals(emptyList<Sessao>(), sessoesMovidas(listOf(sessao(dia(10).atTime(14, 0))), quarta14.copy(diaDaSemana = DayOfWeek.MONDAY), emptyList(), dia(10).atTime(9, 0)))
    }
}
