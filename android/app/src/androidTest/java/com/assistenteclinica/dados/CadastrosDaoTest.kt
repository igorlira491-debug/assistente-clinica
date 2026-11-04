package com.assistenteclinica.dados

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.assistenteclinica.dominio.TipoCliente
import java.time.LocalDate
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CadastrosDaoTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val nome = "teste_${Random.nextLong().toULong()}.db"
    private val banco = abrirBanco(context, Random.nextBytes(32), nome)
    private val dao = banco.cadastros()

    @After fun apagar() { banco.close(); context.deleteDatabase(nome) }

    private suspend fun crianca(n: String, ativa: Boolean = true) =
        dao.salvarCrianca(Crianca(nome = n, tipo = TipoCliente.FIXO, ativa = ativa))

    private suspend fun fonoComTerapeuta(tel: String = "5581999990002"): Pair<Long, Long> {
        val fono = dao.salvarTerapia(Terapia(nome = "Fono", duracaoMin = 40))
        val t = dao.salvarTerapeuta(Terapeuta(nome = "Terapeuta Teste", telefone = tel), setOf(fono))
        return fono to t
    }

    private fun mae() = Responsavel(nome = "Mãe Teste", telefone = "5581999990001")
    private fun pai() = Responsavel(nome = "Pai Teste", telefone = "5581999990003")

    @Test fun salvaCriancaComTerapiaETerapeutaELeDeVolta() = runBlocking {
        val (fono, t) = fonoComTerapeuta()
        val a = crianca("Criança Teste A")
        dao.definirTerapia(CriancaTerapia(a, fono, t))
        assertEquals(t, dao.terapiasDaCrianca(a).single().terapeutaId)
        assertTrue(dao.terapeutasQueAtendem(fono).any { it.id == t })
    }

    @Test fun trocarTerapeutaDaMesmaTerapiaSubstitui() = runBlocking {
        val (fono, t1) = fonoComTerapeuta()
        val t2 = dao.salvarTerapeuta(Terapeuta(nome = "Outra Teste", telefone = "5581999990004"), setOf(fono))
        val a = crianca("Criança Teste A")
        dao.definirTerapia(CriancaTerapia(a, fono, t1))
        dao.definirTerapia(CriancaTerapia(a, fono, t2))
        assertEquals(t2, dao.terapiasDaCrianca(a).single().terapeutaId)
    }

    @Test fun irmaosCompartilhamResponsavelPeloTelefone() = runBlocking {
        val a = crianca("Criança Teste A"); val b = crianca("Criança Teste B")
        dao.vincularResponsavel(a, mae(), principal = true)
        dao.vincularResponsavel(b, mae(), principal = true)
        assertEquals(
            dao.responsaveisDaCrianca(a).single().responsavel.id,
            dao.responsaveisDaCrianca(b).single().responsavel.id,
        )
    }

    @Test fun principalEUnicoPorCriancaENaoMexeNoIrmao() = runBlocking {
        val a = crianca("Criança Teste A"); val b = crianca("Criança Teste B")
        dao.vincularResponsavel(a, mae(), principal = true)
        dao.vincularResponsavel(b, mae(), principal = true)
        dao.vincularResponsavel(a, pai(), principal = true)
        assertEquals(listOf("Pai Teste"), dao.responsaveisDaCrianca(a).filter { it.principal }.map { it.responsavel.nome })
        assertTrue(dao.responsaveisDaCrianca(b).single().principal)
    }

    @Test fun primeiroResponsavelViraPrincipalSozinho() = runBlocking {
        val a = crianca("Criança Teste A")
        dao.vincularResponsavel(a, mae(), principal = false)
        assertTrue(dao.responsaveisDaCrianca(a).single().principal)
    }

    @Test fun desvincularPrincipalPromoveOutro() = runBlocking {
        val a = crianca("Criança Teste A")
        val m = dao.vincularResponsavel(a, mae(), principal = true)
        dao.vincularResponsavel(a, pai(), principal = false)
        dao.desvincularResponsavel(a, m)
        val r = dao.responsaveisDaCrianca(a).single()
        assertEquals("Pai Teste", r.responsavel.nome)
        assertTrue(r.principal)
    }

    @Test fun contaCriancasAtivasDaTerapeuta() = runBlocking {
        val (fono, t) = fonoComTerapeuta()
        dao.definirTerapia(CriancaTerapia(crianca("Criança Teste A"), fono, t))
        dao.definirTerapia(CriancaTerapia(crianca("Criança Teste B", ativa = false), fono, t))
        assertEquals(1, dao.criancasAtendidasPor(t))
    }

    @Test fun consentimentoSobreviveIdaEVolta() = runBlocking {
        val a = crianca("Criança Teste A")
        dao.vincularResponsavel(a, mae().copy(consentiuEm = LocalDate.of(2026, 10, 1), versaoTermo = "v1"), principal = true)
        val r = dao.responsaveisDaCrianca(a).single().responsavel
        assertEquals(LocalDate.of(2026, 10, 1), r.consentiuEm)
        assertEquals("v1", r.versaoTermo)
    }

    @Test fun responsavelSemNenhumaCriancaEApagado() = runBlocking {
        val a = crianca("Criança Teste A"); val b = crianca("Criança Teste B")
        val m = dao.vincularResponsavel(a, mae(), principal = true)
        dao.vincularResponsavel(b, mae(), principal = true)
        dao.desvincularResponsavel(a, m)
        assertEquals(m, dao.responsavelPorTelefone(mae().telefone)?.id) // ainda é da B
        dao.desvincularResponsavel(b, m)
        assertEquals(null, dao.responsavelPorTelefone(mae().telefone))
    }

    @Test fun terapeutaQueDeixouDeAtenderATerapiaPedeRevisao() = runBlocking {
        val (fono, t) = fonoComTerapeuta()
        val a = crianca("Criança Teste A")
        dao.definirTerapia(CriancaTerapia(a, fono, t))
        assertEquals(emptyList<Long>(), dao.criancasParaRevisarTerapeuta())
        dao.salvarTerapeuta(dao.terapeutas().single(), emptySet()) // desmarcou Fono
        assertEquals(listOf(a), dao.criancasParaRevisarTerapeuta())
    }

    @Test fun contaAsCriancasDoResponsavel() = runBlocking {
        val a = crianca("Criança Teste A"); val b = crianca("Criança Teste B")
        val m = dao.vincularResponsavel(a, mae(), principal = true)
        dao.vincularResponsavel(b, mae(), principal = true)
        assertEquals(2, dao.responsaveisDaCrianca(a).single().criancas)
        dao.desvincularResponsavel(b, m)
        assertEquals(1, dao.responsaveisDaCrianca(a).single().criancas)
    }
}
