package com.assistenteclinica.dados

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.assistenteclinica.dominio.Desfecho
import com.assistenteclinica.dominio.Origem
import com.assistenteclinica.dominio.Resposta
import com.assistenteclinica.dominio.TipoCliente
import com.assistenteclinica.dominio.TipoPendencia
import com.assistenteclinica.dominio.TipoSessao
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendenciasDaoTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val nome = "teste_${Random.nextLong().toULong()}.db"
    private val banco = abrirBanco(context, Random.nextBytes(32), nome)
    private val cadastros = banco.cadastros()
    private val agenda = banco.agenda()
    private val dao = banco.pendencias()

    private val dia = LocalDate.of(2026, 11, 3)
    private val agora = LocalDateTime.of(2026, 11, 2, 10, 0)
    private val usuaria = 1L

    @After fun apagar() { banco.close(); context.deleteDatabase(nome) }

    private var crianca = 0L; private var fono = 0L; private var terapeuta = 0L

    private suspend fun base(tipo: TipoCliente = TipoCliente.FIXO) {
        fono = cadastros.salvarTerapia(Terapia(nome = "Fono", duracaoMin = 40))
        terapeuta = cadastros.salvarTerapeuta(Terapeuta(nome = "Terapeuta Teste", telefone = "5581999990002"), setOf(fono))
        crianca = cadastros.salvarCrianca(Crianca(nome = "Criança Teste A", tipo = tipo))
    }

    private suspend fun sessao(hora: Int, terapeutaId: Long = terapeuta, desfecho: Desfecho? = null) = agenda.salvarSessao(
        LinhaSessao(0, crianca, fono, terapeutaId, dia.atTime(hora, 0), 40, Resposta.AGUARDANDO, Resposta.AGUARDANDO, Origem.AVULSA, TipoSessao.COMUM, desfecho),
    )

    private suspend fun remarcar() = dao.abertas().filter { it.tipo == TipoPendencia.REMARCAR }

    @Test fun naoGeraUmRemarcar() = runBlocking {
        base(); val s = sessao(14)
        dao.responder(s, daFamilia = true, sim = false, agora, usuaria)
        dao.responder(s, daFamilia = true, sim = false, agora, usuaria)
        assertEquals(listOf(s), remarcar().map { it.sessaoId })
    }

    @Test fun naoEDepoisFaltouNaoDuplica() = runBlocking {
        base(); val s = sessao(14)
        dao.responder(s, daFamilia = false, sim = false, agora, usuaria)
        dao.encerrar(s, Desfecho.FALTOU, agora)
        assertEquals(1, remarcar().size)
    }

    @Test fun respostaCorrigidaResolveORemarcar() = runBlocking {
        base(); val s = sessao(14)
        dao.responder(s, daFamilia = true, sim = false, agora, usuaria)
        dao.responder(s, daFamilia = true, sim = true, agora, usuaria)
        assertEquals(emptyList<Pendencia>(), dao.abertas())
    }

    @Test fun confirmacaoDaFamiliaGuardaOHorario() = runBlocking {
        base(); val s = sessao(14)
        dao.responder(s, daFamilia = true, sim = true, agora, usuaria)
        assertEquals(agora, agenda.sessao(s)!!.sessao.familiaConfirmouEm)
        dao.responder(s, daFamilia = false, sim = true, agora.plusHours(1), usuaria) // a terapeuta não mexe no horário da família
        assertEquals(agora, agenda.sessao(s)!!.sessao.familiaConfirmouEm)
        dao.responder(s, daFamilia = true, sim = false, agora.plusHours(2), usuaria)
        assertEquals(null, agenda.sessao(s)!!.sessao.familiaConfirmouEm)
    }

    @Test fun naoDeVerdadeDepoisDeCorrigidoGeraRemarcar() = runBlocking {
        base(); val s = sessao(14)
        dao.responder(s, daFamilia = true, sim = false, agora, usuaria)
        dao.responder(s, daFamilia = true, sim = true, agora, usuaria) // era engano
        dao.encerrar(s, Desfecho.FALTOU, agora)
        assertEquals(listOf(s), remarcar().map { it.sessaoId })
    }

    @Test fun encerrarDuasVezesNaoDuplica() = runBlocking {
        base(TipoCliente.AVULSO); val s = sessao(14)
        dao.encerrar(s, Desfecho.REALIZADA, agora)
        dao.encerrar(s, Desfecho.REALIZADA, agora)
        dao.encerrar(s, Desfecho.FALTOU, agora)
        assertEquals(listOf(TipoPendencia.DEFINIR_PROXIMO_PASSO), dao.abertas().map { it.tipo })
        assertEquals(Desfecho.REALIZADA, agenda.sessao(s)!!.sessao.desfecho)
    }

    @Test fun realizadaDeAvulsoPedeProximoPasso() = runBlocking {
        base(TipoCliente.AVULSO); val s = sessao(14)
        dao.encerrar(s, Desfecho.REALIZADA, agora)
        assertEquals(listOf(TipoPendencia.DEFINIR_PROXIMO_PASSO), dao.abertas().map { it.tipo })
        cadastros.salvarCrianca(cadastros.crianca(crianca)!!.copy(tipo = TipoCliente.FIXO))
        dao.encerrar(sessao(16), Desfecho.REALIZADA, agora)
        assertEquals(1, dao.abertas().size) // fixo não ganha outra
    }

    @Test fun marcarNovaDataResolve() = runBlocking {
        base(); val s = sessao(14)
        dao.responder(s, daFamilia = true, sim = false, agora, usuaria)
        val p = remarcar().single()
        val original = agenda.sessao(s)!!.sessao
        val nova = original.copy(id = 0, inicio = dia.plusDays(1).atTime(10, 0), familia = Resposta.AGUARDANDO, origem = Origem.REMARCACAO)
        val novaId = dao.marcarNovaData(p.id, nova, agora, usuaria)
        assertEquals(emptyList<Pendencia>(), dao.abertas())
        assertEquals(Origem.REMARCACAO, agenda.sessao(novaId)!!.sessao.origem)
        val emAberto = dao.sessoesEmAberto(dia.plusDays(5).atStartOfDay()).map { it.id }
        assertTrue(novaId in emAberto)
        // Uma ação, uma linha: a nova data não vira também "resolveu a pendência" (revisão do Bloco 8).
        assertEquals(com.assistenteclinica.dominio.Acao.CRIOU, banco.registro().ultimos(1).single().acao)
    }

    @Test fun lembrarDepoisCriaEntrarEmContato() = runBlocking {
        base(TipoCliente.AVULSO); val s = sessao(14)
        dao.encerrar(s, Desfecho.REALIZADA, agora)
        dao.lembrarDepois(dao.abertas().single().id, 7, agora, usuaria)
        val nova = dao.abertas().single()
        assertEquals(TipoPendencia.ENTRAR_EM_CONTATO, nova.tipo)
        assertEquals(agora.plusDays(7), nova.prazo)
        assertEquals(crianca, nova.criancaId)
        val ultimas = banco.registro().ultimos(2).map { it.acao }
        assertEquals(com.assistenteclinica.dominio.Acao.LEMBRAR_DEPOIS, ultimas[0])
        assertTrue(com.assistenteclinica.dominio.Acao.RESOLVEU !in ultimas)
    }

    @Test fun adiarMudaPrazoEGuardaMotivo() = runBlocking {
        base(); val s = sessao(14)
        dao.responder(s, daFamilia = true, sim = false, agora, usuaria)
        dao.adiar(remarcar().single().id, agora.plusDays(3), "Mãe viajando")
        val p = remarcar().single()
        assertEquals(agora.plusDays(3), p.prazo)
        assertEquals("Mãe viajando", p.motivoAdiamento)
    }

    @Test fun indisponivelSoMexeNoQueVaiAcontecer() = runBlocking {
        base()
        val aguardando = sessao(9)
        val confirmada = sessao(10).also {
            dao.responder(it, true, true, agora, usuaria); dao.responder(it, false, true, agora, usuaria)
        }
        val encerrada = sessao(11, desfecho = Desfecho.REALIZADA)
        val outra = cadastros.salvarTerapeuta(Terapeuta(nome = "Outra Teste", telefone = "5581999990004"), setOf(fono))
        val daOutra = sessao(12, terapeutaId = outra)

        assertEquals(2, dao.marcarIndisponivel(terapeuta, dia, agora))
        assertEquals(setOf(aguardando, confirmada), remarcar().map { it.sessaoId }.toSet())
        assertEquals(Resposta.NAO, agenda.sessao(confirmada)!!.sessao.terapeuta)
        assertEquals(Resposta.AGUARDANDO, agenda.sessao(encerrada)!!.sessao.terapeuta)
        assertEquals(Resposta.AGUARDANDO, agenda.sessao(daOutra)!!.sessao.terapeuta)
    }

    @Test fun clinicaFechadaCancelaERemarca() = runBlocking {
        base()
        val a = sessao(9); sessao(11, desfecho = Desfecho.REALIZADA)
        assertEquals(1, dao.fecharClinica(dia, agora))
        assertEquals(Desfecho.CANCELADA_PELA_CLINICA, agenda.sessao(a)!!.sessao.desfecho)
        assertEquals(listOf(a), remarcar().map { it.sessaoId })
    }
}
