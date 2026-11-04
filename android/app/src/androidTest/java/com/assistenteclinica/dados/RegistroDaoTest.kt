package com.assistenteclinica.dados

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.assistenteclinica.dominio.Acao
import com.assistenteclinica.dominio.Alvo
import com.assistenteclinica.dominio.Desfecho
import com.assistenteclinica.dominio.Detalhe
import com.assistenteclinica.dominio.Origem
import com.assistenteclinica.dominio.Resposta
import com.assistenteclinica.dominio.TipoCliente
import com.assistenteclinica.dominio.TipoSessao
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

// Bloco 8, etapa 1: registro de ações. Só bancos de teste com nome sorteado e dados inventados.
@RunWith(AndroidJUnit4::class)
class RegistroDaoTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val nome = "teste_${Random.nextLong().toULong()}.db"
    private val banco = abrirBanco(context, Random.nextBytes(32), nome).apply {
        usuariaAtual = 2
        relogio = Clock.fixed(Instant.parse("2026-11-05T14:05:00Z"), ZoneOffset.UTC)
    }
    private val agora = LocalDateTime.of(2026, 11, 5, 14, 5)
    private val cadastros = banco.cadastros()

    @After fun apagar() { banco.close(); context.deleteDatabase(nome) }

    private suspend fun linhas() = banco.registro().ultimos(100).reversed()

    @Test fun oBancoRecusaAlterarEApagarORegistro() = runBlocking {
        cadastros.salvarTerapia(Terapia(nome = "Fono Teste", duracaoMin = 40))
        val db = banco.openHelper.writableDatabase
        assertThrows(Exception::class.java) { db.execSQL("UPDATE Registro SET usuariaId = 9") }
        assertThrows(Exception::class.java) { db.execSQL("DELETE FROM Registro") }
        assertEquals(listOf(2L), linhas().map { it.usuariaId })
    }

    @Test fun cadastrosGravamQuemOQueEQuando() = runBlocking {
        val fono = cadastros.salvarTerapia(Terapia(nome = "Fono Teste", duracaoMin = 40))
        cadastros.salvarTerapia(Terapia(id = fono, nome = "Fono Teste", duracaoMin = 40, ativa = false))
        val crianca = cadastros.salvarCrianca(Crianca(nome = "Criança Teste A", tipo = TipoCliente.FIXO))
        val mae = cadastros.vincularResponsavel(crianca, Responsavel(nome = "Mãe Teste", telefone = "5511988880050"), principal = true)
        cadastros.desvincularResponsavel(crianca, mae)
        assertEquals(
            listOf(
                Triple(Acao.CRIOU, Alvo.TERAPIA, fono), Triple(Acao.DESATIVOU, Alvo.TERAPIA, fono),
                Triple(Acao.CRIOU, Alvo.CRIANCA, crianca),
                Triple(Acao.CRIOU, Alvo.RESPONSAVEL, mae), Triple(Acao.VINCULOU, Alvo.RESPONSAVEL, mae),
                Triple(Acao.DESVINCULOU, Alvo.RESPONSAVEL, mae), Triple(Acao.REMOVEU, Alvo.RESPONSAVEL, mae),
            ),
            linhas().map { Triple(it.acao, it.alvo, it.alvoId) },
        )
        linhas().forEach { assertEquals(2L, it.usuariaId); assertEquals(agora, it.momento) }
        assertEquals(crianca, linhas().last().criancaId)
    }

    @Test fun acaoQueFalhaNaoDeixaLinha() = runBlocking {
        val fono = cadastros.salvarTerapia(Terapia(nome = "Fono Teste", duracaoMin = 40))
        cadastros.salvarTerapeuta(Terapeuta(nome = "Terapeuta Teste", telefone = "5511988880051"), setOf(fono))
        // Mesmo telefone: o índice único recusa, e a linha do registro vai junto (mesma transação).
        assertThrows(Exception::class.java) {
            runBlocking { cadastros.salvarTerapeuta(Terapeuta(nome = "Outra Teste", telefone = "5511988880051"), setOf(fono)) }
        }
        assertEquals(listOf(Acao.CRIOU, Acao.CRIOU), linhas().map { it.acao })
    }

    @Test fun agendaEPendenciasGravamComDetalhe() = runBlocking {
        val fono = cadastros.salvarTerapia(Terapia(nome = "Fono Teste", duracaoMin = 40))
        val t = cadastros.salvarTerapeuta(Terapeuta(nome = "Terapeuta Teste", telefone = "5511988880052"), setOf(fono))
        val crianca = cadastros.salvarCrianca(Crianca(nome = "Criança Teste A", tipo = TipoCliente.AVULSO))
        val ontem = agora.minusDays(1).withHour(10)
        val s = banco.agenda().salvarSessao(
            LinhaSessao(0, crianca, fono, t, ontem, 40, Resposta.AGUARDANDO, Resposta.AGUARDANDO, Origem.AVULSA, TipoSessao.COMUM, null, lancadaDepoisEm = agora, lancadaPor = 2),
        )
        banco.pendencias().responder(s, daFamilia = true, sim = true, agora = agora, usuariaId = 2)
        banco.pendencias().encerrar(s, Desfecho.REALIZADA, agora)
        val ultimas = linhas().takeLast(3)
        assertEquals(
            listOf(
                Triple(Acao.CRIOU, s, Detalhe.LANCADA_DEPOIS_DO_DIA),
                Triple(Acao.MARCOU_RESPOSTA, s, Detalhe.FAMILIA_SIM),
                Triple(Acao.REGISTROU_COMO_FOI, s, Detalhe.REALIZADA),
            ),
            ultimas.map { Triple(it.acao, it.alvoId, it.detalhe) },
        )
        ultimas.forEach { assertEquals(crianca, it.criancaId) }
    }

    // LGPD: depois de ações com nomes e telefones, nada disso aparece em nenhuma coluna do registro.
    @Test fun registroNaoGuardaNomeNemTelefone() = runBlocking {
        val crianca = cadastros.salvarCrianca(Crianca(nome = "Criança Teste A", tipo = TipoCliente.FIXO, observacao = "Mãe só responde à noite"))
        cadastros.vincularResponsavel(crianca, Responsavel(nome = "Mãe Teste", telefone = "5511988880053"), principal = true)
        val tudo = banco.openHelper.readableDatabase.query("SELECT * FROM Registro").use { c ->
            buildString { while (c.moveToNext()) for (i in 0 until c.columnCount) append(c.getString(i)).append('|') }
        }
        listOf("Criança", "Teste", "Mãe", "noite", "5511988880053").forEach { assertFalse("o registro tem \"$it\"", it in tudo) }
    }

    // Bloco 8, etapa 4: a pendência "Salvar backup" é uma só, e salvar registra o destino.
    @Test fun salvarBackupUmaPendenciaSoESalvarRegistraODestino() = runBlocking {
        val p = banco.pendencias()
        p.backupPreparado(agora.withHour(18), agora)
        p.backupPreparado(agora.plusDays(1).withHour(18), agora.plusDays(1))
        assertEquals(1, p.abertas().count { it.tipo == com.assistenteclinica.dominio.TipoPendencia.SALVAR_BACKUP })
        p.backupSalvo(Detalhe.DRIVE, agora, usuariaId = 2)
        assertEquals(0, p.abertas().size)
        assertEquals(
            listOf(Acao.PREPAROU_O_BACKUP to null, Acao.PREPAROU_O_BACKUP to null, Acao.SALVOU_O_BACKUP to Detalhe.DRIVE),
            linhas().map { it.acao to it.detalhe },
        )
    }

    // ---- Tela da dona (etapa 6) ----

    @Test fun filtraPorDiaEPorUsuaria() = runBlocking {
        val reg = banco.registro()
        fun linha(momento: LocalDateTime, usuaria: Long?) = LinhaRegistro(0, momento, usuaria, Acao.ENTROU, null, null, null, null, null)
        reg.inserir(linha(LocalDateTime.of(2026, 11, 4, 23, 59), 1))
        reg.inserir(linha(LocalDateTime.of(2026, 11, 5, 0, 0), 1))
        reg.inserir(linha(LocalDateTime.of(2026, 11, 5, 9, 0), 2))
        reg.inserir(linha(LocalDateTime.of(2026, 11, 6, 0, 0), 1))
        val de = LocalDateTime.of(2026, 11, 5, 0, 0)
        val ate = de.plusDays(1)
        assertEquals(listOf(9, 0), reg.doPeriodo(de, ate, null, 100).map { it.momento.hour }) // mais novo primeiro
        assertEquals(listOf(0), reg.doPeriodo(de, ate, 1, 100).map { it.momento.hour })
        assertEquals(1, reg.doPeriodo(de, ate, null, 1).size)
    }

    @Test fun dataDaUltimaDesativacaoDeCadaCrianca() = runBlocking {
        val a = cadastros.salvarCrianca(Crianca(nome = "Criança Teste A", tipo = TipoCliente.FIXO))
        val b = cadastros.salvarCrianca(Crianca(nome = "Criança Teste B", tipo = TipoCliente.FIXO))
        cadastros.salvarCrianca(Crianca(id = a, nome = "Criança Teste A", tipo = TipoCliente.FIXO, ativa = false))
        assertEquals(listOf(DesativadaEm(a, agora)), banco.registro().criancasDesativadasEm())
        assertEquals(setOf(a, b), banco.nomesDoRegistro(emptyList()).criancas.keys)
    }
}
