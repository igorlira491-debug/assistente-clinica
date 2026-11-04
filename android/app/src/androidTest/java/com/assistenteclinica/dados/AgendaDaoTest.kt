package com.assistenteclinica.dados

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.assistenteclinica.dominio.Origem
import com.assistenteclinica.dominio.QuemPediu
import com.assistenteclinica.dominio.Resposta
import com.assistenteclinica.dominio.TipoCliente
import com.assistenteclinica.dominio.TipoSessao
import com.assistenteclinica.dominio.remarcar
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgendaDaoTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val nome = "teste_${Random.nextLong().toULong()}.db"
    private val banco = abrirBanco(context, Random.nextBytes(32), nome)
    private val cadastros = banco.cadastros()
    private val agenda = banco.agenda()

    private val novembro = YearMonth.of(2026, 11) // 01/11/2026 é domingo
    private val antes = LocalDate.of(2026, 10, 20)
    private fun dia(d: Int) = LocalDate.of(2026, 11, d)

    @After fun apagar() { banco.close(); context.deleteDatabase(nome) }

    private data class Base(val crianca: Long, val fono: Long, val terapeuta: Long)

    private suspend fun base(): Base {
        val fono = cadastros.salvarTerapia(Terapia(nome = "Fono", duracaoMin = 40))
        val t = cadastros.salvarTerapeuta(Terapeuta(nome = "Terapeuta Teste", telefone = "5581999990002"), setOf(fono))
        val a = cadastros.salvarCrianca(Crianca(nome = "Criança Teste A", tipo = TipoCliente.FIXO))
        cadastros.definirTerapia(CriancaTerapia(a, fono, t))
        return Base(a, fono, t)
    }

    private suspend fun terca14(b: Base) =
        agenda.salvarHorario(Horario(criancaId = b.crianca, terapiaId = b.fono, diaDaSemana = DayOfWeek.TUESDAY, hora = LocalTime.of(14, 0)))

    private suspend fun lancarNovembro(hoje: LocalDate = antes) = agenda.lancar(agenda.previaDoMes(novembro, hoje).map { it.paraLinha() })

    private suspend fun doMes() = agenda.sessoesEntre(dia(1).atStartOfDay(), dia(1).plusMonths(1).atStartOfDay())

    @Test fun salvaELeSessaoComNomes() = runBlocking {
        val b = base()
        val s = LinhaSessao(0, b.crianca, b.fono, b.terapeuta, dia(4).atTime(10, 0), 40, Resposta.AGUARDANDO, Resposta.AGUARDANDO,
            Origem.AVULSA, TipoSessao.ANAMNESE, null)
        val id = agenda.salvarSessao(s)
        val lida = agenda.sessoesEntre(dia(4).atStartOfDay(), dia(5).atStartOfDay()).single()
        assertEquals(s.copy(id = id), lida.sessao)
        assertEquals(listOf("Criança Teste A", "Fono", "Terapeuta Teste"), listOf(lida.crianca, lida.terapia, lida.nomeTerapeuta))
    }

    @Test fun lancarDuasVezesNaoDuplica() = runBlocking {
        terca14(base())
        lancarNovembro()
        assertEquals(listOf(3, 10, 17, 24), doMes().map { it.sessao.inicio.dayOfMonth })
        assertEquals(emptyList<Any>(), agenda.previaDoMes(novembro, antes))
    }

    @Test fun horarioSemTerapiaOuDeCriancaInativaNaoLanca() = runBlocking {
        val b = base()
        terca14(b)
        cadastros.removerTerapia(b.crianca, b.fono)
        assertEquals(emptyList<Any>(), agenda.horariosParaLancar())
        assertTrue(agenda.horariosDaCrianca(b.crianca).single().semTerapeuta)

        cadastros.definirTerapia(CriancaTerapia(b.crianca, b.fono, b.terapeuta))
        cadastros.salvarCrianca(cadastros.crianca(b.crianca)!!.copy(tipo = TipoCliente.AVULSO))
        assertEquals(emptyList<Any>(), agenda.horariosParaLancar()) // virou avulsa: não lança mais

        cadastros.salvarCrianca(cadastros.crianca(b.crianca)!!.copy(tipo = TipoCliente.FIXO, ativa = false))
        assertEquals(emptyList<Any>(), agenda.horariosParaLancar())
    }

    @Test fun trocaDeTerapeutaValeNoProximoLancamento() = runBlocking {
        val b = base()
        terca14(b)
        lancarNovembro()
        val outra = cadastros.salvarTerapeuta(Terapeuta(nome = "Outra Teste", telefone = "5581999990004"), setOf(b.fono))
        cadastros.definirTerapia(CriancaTerapia(b.crianca, b.fono, outra))
        assertEquals(outra, agenda.horariosParaLancar().single().terapeutaId)
        assertTrue(doMes().all { it.sessao.terapeutaId == b.terapeuta })
    }

    @Test fun desativarHorarioTiraSoAsFuturasSemResposta() = runBlocking {
        val b = base()
        val h = terca14(b)
        lancarNovembro()
        val respondida = doMes().first { it.sessao.inicio.dayOfMonth == 17 }.sessao
        agenda.salvarSessao(respondida.copy(familia = Resposta.SIM))
        val agora = dia(3).atTime(15, 0)

        assertEquals(2, agenda.contarParaTirar(h, agora))
        assertEquals(2, agenda.desativarHorario(h, agora))
        assertEquals(listOf(3, 17), doMes().map { it.sessao.inicio.dayOfMonth })
        assertEquals(emptyList<Any>(), agenda.horariosDaCrianca(b.crianca))
    }

    @Test fun editarHorarioMoveAsFuturas() = runBlocking {
        val b = base()
        val h = terca14(b)
        lancarNovembro()
        val respondida = doMes().first { it.sessao.inicio.dayOfMonth == 17 }.sessao
        agenda.salvarSessao(respondida.copy(familia = Resposta.SIM))
        val agora = dia(3).atTime(15, 0)

        val quinta10 = Horario(h, b.crianca, b.fono, DayOfWeek.THURSDAY, LocalTime.of(10, 0))
        assertEquals(2, agenda.editarHorario(quinta10, agora))
        // Ficam a terça passada (3) e a respondida (17); só as semanas que perderam a terça ganham a quinta.
        assertEquals(listOf(3, 12, 17, 26), doMes().map { it.sessao.inicio.dayOfMonth })
        assertTrue(doMes().filter { it.sessao.inicio.dayOfWeek == DayOfWeek.THURSDAY }.all { it.sessao.inicio.hour == 10 })
    }

    @Test fun editarHorarioSemTerapeutaNaoMexeEmNada() = runBlocking {
        val b = base()
        val h = terca14(b)
        lancarNovembro()
        cadastros.removerTerapia(b.crianca, b.fono)
        val quinta10 = Horario(h, b.crianca, b.fono, DayOfWeek.THURSDAY, LocalTime.of(10, 0))
        assertEquals(-1, agenda.editarHorario(quinta10, dia(3).atTime(15, 0)))
        assertEquals(listOf(3, 10, 17, 24), doMes().map { it.sessao.inicio.dayOfMonth })
    }

    @Test fun remarcarGuardaAOriginal() = runBlocking {
        terca14(base())
        lancarNovembro()
        val original = doMes().first().sessao
        val (antiga, nova) = remarcar(original.paraDominio(), dia(4).atTime(10, 0), QuemPediu.FAMILIA)
        agenda.remarcarSessao(antiga.paraLinha(), nova.paraLinha(), QuemPediu.FAMILIA)

        val semana = agenda.sessoesEntre(dia(3).atStartOfDay(), dia(5).atStartOfDay()).map { it.sessao }
        assertEquals(listOf(Resposta.NAO, Resposta.AGUARDANDO), semana.map { it.familia })
        assertEquals(Origem.REMARCACAO, semana.last().origem)
        assertEquals(emptyList<Any>(), agenda.previaDoMes(novembro, antes)) // a terça desmarcada não volta
    }
}
