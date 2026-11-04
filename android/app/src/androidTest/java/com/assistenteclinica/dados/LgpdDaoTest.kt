package com.assistenteclinica.dados

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.assistenteclinica.dominio.Acao
import com.assistenteclinica.dominio.Alvo
import com.assistenteclinica.dominio.Desfecho
import com.assistenteclinica.dominio.Origem
import com.assistenteclinica.dominio.Resposta
import com.assistenteclinica.dominio.TipoCliente
import com.assistenteclinica.dominio.TipoSessao
import com.assistenteclinica.dominio.textoComNomes
import com.assistenteclinica.dominio.textoDaExportacao
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// Bloco 8, etapa 7: exportar e apagar a pedido. Só bancos de teste com nome sorteado e dados inventados.
@RunWith(AndroidJUnit4::class)
class LgpdDaoTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val nome = "teste_${Random.nextLong().toULong()}.db"
    private val banco = abrirBanco(context, Random.nextBytes(32), nome).apply {
        usuariaAtual = 1
        relogio = Clock.fixed(Instant.parse("2026-11-05T14:05:00Z"), ZoneOffset.UTC)
    }
    private val agora = LocalDateTime.of(2026, 11, 5, 14, 5)
    private val cadastros = banco.cadastros()

    @After fun apagar() { banco.close(); context.deleteDatabase(nome) }

    private class Familia(val joao: Long, val irma: Long, val outra: Long, val mae: Long, val pai: Long, val sessaoJoao: Long)

    // João e a irmã têm a mesma mãe; só João tem o pai. Outra criança, de outra família, não pode ser tocada.
    private suspend fun familia(): Familia {
        val fono = cadastros.salvarTerapia(Terapia(nome = "Fono Teste", duracaoMin = 40))
        val t = cadastros.salvarTerapeuta(Terapeuta(nome = "Terapeuta Teste", telefone = "5511988880060"), setOf(fono))
        val joao = cadastros.salvarCrianca(Crianca(nome = "Criança Teste João", tipo = TipoCliente.FIXO, observacao = "Prefere tarde"))
        val irma = cadastros.salvarCrianca(Crianca(nome = "Criança Teste Irmã", tipo = TipoCliente.FIXO))
        val outra = cadastros.salvarCrianca(Crianca(nome = "Criança Teste Outra", tipo = TipoCliente.FIXO))
        cadastros.definirTerapia(CriancaTerapia(joao, fono, t))
        cadastros.definirTerapia(CriancaTerapia(outra, fono, t))
        val mae = cadastros.vincularResponsavel(joao, Responsavel(nome = "Mãe Teste", telefone = "5511988880061"), principal = true)
        cadastros.vincularResponsavel(irma, Responsavel(nome = "Mãe Teste", telefone = "5511988880061"), principal = true)
        val pai = cadastros.vincularResponsavel(joao, Responsavel(nome = "Pai Teste", telefone = "5511988880062"), principal = false)
        cadastros.vincularResponsavel(outra, Responsavel(nome = "Outro Teste", telefone = "5511988880063"), principal = true)
        banco.agenda().salvarHorario(Horario(criancaId = joao, terapiaId = fono, diaDaSemana = DayOfWeek.MONDAY, hora = LocalTime.of(14, 0)))
        banco.agenda().salvarHorario(Horario(criancaId = outra, terapiaId = fono, diaDaSemana = DayOfWeek.TUESDAY, hora = LocalTime.of(9, 0)))
        fun sessao(c: Long, quando: LocalDateTime) =
            LinhaSessao(0, c, fono, t, quando, 40, Resposta.SIM, Resposta.SIM, Origem.FIXA, TipoSessao.COMUM, Desfecho.REALIZADA)
        val sessaoJoao = banco.agenda().salvarSessao(sessao(joao, agora.minusDays(3)))
        banco.agenda().salvarSessao(sessao(outra, agora.minusDays(2)))
        // Uma pendência ligada só pela sessão (sem criancaId), com motivo digitado.
        banco.openHelper.writableDatabase.execSQL(
            "INSERT INTO Pendencia (tipo, criancaId, sessaoId, prazo, criadaEm, motivoAdiamento) VALUES ('REMARCAR', NULL, ?, ?, ?, 'família viajando')",
            arrayOf<Any>(sessaoJoao, agora.toEpochSecond(ZoneOffset.UTC), agora.toEpochSecond(ZoneOffset.UTC)),
        )
        return Familia(joao, irma, outra, mae, pai, sessaoJoao)
    }

    private fun contar(sql: String): Int = banco.openHelper.readableDatabase.query(sql).use { it.moveToFirst(); it.getInt(0) }

    @Test fun exportarTrazSoOQueEDaCriancaEFicaRegistrado() = runBlocking {
        val f = familia()
        val d = banco.lgpd().exportar(f.joao)!!
        assertEquals(listOf("Mãe Teste", "Pai Teste"), d.responsaveis.map { it.nome })
        assertEquals(1, d.sessoes.size)
        assertEquals(1, d.horarios.size)
        assertEquals(listOf("família viajando"), d.pendencias.map { it.motivoAdiamento })
        val texto = textoDaExportacao(d, agora)
        assertTrue(texto.contains("Prefere tarde"))
        listOf("Outra", "Outro Teste", "Irmã", "5511988880063").forEach { assertFalse("a exportação tem \"$it\"", texto.contains(it)) }
        val ultima = banco.registro().ultimos(1).single()
        assertEquals(Triple(Acao.EXPORTOU_OS_DADOS, Alvo.CRIANCA, f.joao), Triple(ultima.acao, ultima.alvo, ultima.alvoId))
        assertNull(banco.lgpd().exportar(999))
    }

    @Test fun apagarAPedidoRemoveSoODaCrianca() = runBlocking {
        val f = familia()
        val linhasAntes = contar("SELECT COUNT(*) FROM Registro")
        assertEquals(1, banco.lgpd().apagarAPedido(f.joao))
        assertNull(cadastros.crianca(f.joao))
        assertEquals(0, contar("SELECT COUNT(*) FROM Sessao WHERE criancaId = ${f.joao}"))
        assertEquals(0, contar("SELECT COUNT(*) FROM Horario WHERE criancaId = ${f.joao}"))
        assertEquals(0, contar("SELECT COUNT(*) FROM Pendencia WHERE sessaoId = ${f.sessaoJoao}"))
        assertEquals(0, contar("SELECT COUNT(*) FROM CriancaTerapia WHERE criancaId = ${f.joao}"))
        // O pai era só do João: some. A mãe continua, porque é da irmã.
        assertEquals(0, contar("SELECT COUNT(*) FROM Responsavel WHERE id = ${f.pai}"))
        assertEquals(1, contar("SELECT COUNT(*) FROM Responsavel WHERE id = ${f.mae}"))
        // A outra família continua inteira.
        assertEquals(1, contar("SELECT COUNT(*) FROM Sessao WHERE criancaId = ${f.outra}"))
        assertEquals(1, contar("SELECT COUNT(*) FROM Horario WHERE criancaId = ${f.outra}"))
        // O registro só cresce: as linhas antigas ficam, com "registro removido" no lugar do nome.
        val linhas = banco.registro().ultimos(500).reversed()
        assertTrue(linhas.size > linhasAntes)
        assertEquals(Acao.APAGOU_A_PEDIDO, linhas.last().acao)
        assertTrue(linhas.any { it.acao == Acao.REMOVEU && it.alvo == Alvo.RESPONSAVEL && it.alvoId == f.pai })
        val nomes = banco.nomesDoRegistro(emptyList())
        val criou = linhas.first { it.acao == Acao.CRIOU && it.alvo == Alvo.CRIANCA && it.alvoId == f.joao }
        assertTrue(textoComNomes(criou.paraDominio(), nomes).endsWith("criou a criança (registro removido)"))
    }
}
