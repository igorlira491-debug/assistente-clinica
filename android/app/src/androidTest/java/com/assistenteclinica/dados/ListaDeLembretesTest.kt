package com.assistenteclinica.dados

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.assistenteclinica.dominio.GENERICO_CONFIRMAR
import com.assistenteclinica.dominio.Lembrete
import com.assistenteclinica.dominio.Origem
import com.assistenteclinica.dominio.Resposta
import com.assistenteclinica.dominio.TipoCliente
import com.assistenteclinica.dominio.TipoLembrete
import com.assistenteclinica.dominio.TipoSessao
import com.assistenteclinica.dominio.disparar
import com.assistenteclinica.dominio.listaAtualizada
import java.io.File
import java.time.LocalDateTime
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ListaDeLembretesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val arquivo = "lembretes_teste_${Random.nextLong().toULong()}" // nunca a lista de verdade
    private val nomeBanco = "teste_${Random.nextLong().toULong()}.db"
    private val lista = ListaDeLembretes(context, arquivo)
    private val protegido = context.createDeviceProtectedStorageContext()

    @After fun apagar() {
        protegido.deleteSharedPreferences(arquivo)
        context.deleteDatabase(nomeBanco)
    }

    private val agora = LocalDateTime.of(2026, 11, 3, 7, 30)
    private fun lem(h: Int) = Lembrete(agora.withHour(h), TipoLembrete.CONFIRMAR, "Assistente: 2 confirmações para enviar.", GENERICO_CONFIRMAR)

    @Test fun salvaELeAMesmaLista() {
        lista.salvar(agora, listOf(lem(9), lem(16)))
        val lida = lista.ler()!!
        assertEquals(agora, lida.calculadaEm)
        assertEquals(listOf(lem(9), lem(16)), lida.lembretes)
    }

    @Test fun ficaNoArmazenamentoProtegidoPorDispositivo() {
        lista.salvar(agora, listOf(lem(9)))
        assertTrue(File(protegido.dataDir, "shared_prefs/$arquivo.xml").exists())
        assertFalse(File(context.dataDir, "shared_prefs/$arquivo.xml").exists())
    }

    @Test fun dispararTiraOVencidoEMantemADataDoCalculo() {
        lista.salvar(agora, listOf(lem(9), lem(16)))
        val (lida, d) = lista.disparar { disparar(it.lembretes, agora.withHour(9)) }!!
        assertEquals(agora, lida.calculadaEm)
        assertEquals(listOf(lem(9)), d.mostrar)
        assertEquals(agora, lista.ler()!!.calculadaEm)
        assertEquals(listOf(lem(16)), lista.ler()!!.lembretes)
    }

    // Recálculo que passou dos 2 s e termina depois do seguinte: a lista mais nova fica.
    @Test fun calculoMaisAntigoNaoSobrescreveOMaisNovo() {
        lista.salvar(agora.plusMinutes(5), listOf(lem(16)), agora = agora.plusMinutes(6))
        lista.salvar(agora, listOf(lem(9)), agora = agora.plusMinutes(6))
        assertEquals(agora.plusMinutes(5), lista.ler()!!.calculadaEm)
        assertEquals(listOf(lem(16)), lista.ler()!!.lembretes)
    }

    @Test fun listaGravadaNoFuturoNaoSeguraONovoCalculo() {
        lista.salvar(agora.plusYears(1), listOf(lem(16)), agora = agora.plusYears(1)) // relógio adiantado
        lista.salvar(agora, listOf(lem(9)), agora = agora) // relógio corrigido
        assertEquals(listOf(lem(9)), lista.ler()!!.lembretes)
    }

    @Test fun listaMarcadaDesatualizadaAceitaONovoCalculo() {
        lista.salvar(agora, listOf(lem(9)), agora = agora)
        lista.marcarDesatualizada()
        lista.salvar(agora, listOf(lem(16)), agora = agora)
        assertEquals(listOf(lem(16)), lista.ler()!!.lembretes)
        assertTrue(listaAtualizada(lista.ler()!!.calculadaEm, agora))
    }

    @Test fun marcarDesatualizadaFazAListaValerComoDeOutroDia() {
        lista.salvar(agora, listOf(lem(9)))
        lista.marcarDesatualizada()
        assertFalse(listaAtualizada(lista.ler()!!.calculadaEm, agora))
        assertEquals(listOf(lem(9)), lista.ler()!!.lembretes)
    }

    @Test fun calculaDoBancoEArquivoNaoGuardaNomes() = runBlocking {
        val banco = abrirBanco(context, Random.nextBytes(32), nomeBanco)
        val cadastros = banco.cadastros()
        val fono = cadastros.salvarTerapia(Terapia(nome = "Fono", duracaoMin = 40))
        val terapeuta = cadastros.salvarTerapeuta(Terapeuta(nome = "Terapeuta Teste", telefone = "5591988880030"), setOf(fono))
        val crianca = cadastros.salvarCrianca(Crianca(nome = "Criança Teste A", tipo = TipoCliente.FIXO))
        val amanha = agora.toLocalDate().plusDays(1).atTime(14, 0)
        banco.agenda().salvarSessao(
            LinhaSessao(0, crianca, fono, terapeuta, amanha, 40, Resposta.AGUARDANDO, Resposta.AGUARDANDO, Origem.AVULSA, TipoSessao.COMUM, null),
        )
        val calculada = calcularLembretes(banco, agora)
        banco.close()
        // Hoje 9h e 16h (a sessão é amanhã), amanhã 8h (a confirmação já venceu) e o "Como foi?" no fim dela.
        assertEquals(setOf(TipoLembrete.CONFIRMAR, TipoLembrete.ESCALADA, TipoLembrete.RESUMO, TipoLembrete.COMO_FOI), calculada.map { it.tipo }.toSet())
        lista.salvar(agora, calculada)
        val xml = File(protegido.dataDir, "shared_prefs/$arquivo.xml").readText()
        listOf("Criança", "Teste", "Fono", "5591988880030").forEach { assertFalse("o arquivo tem \"$it\"", it in xml) }
    }
}
