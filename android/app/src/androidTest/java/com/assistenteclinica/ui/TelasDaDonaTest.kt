package com.assistenteclinica.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.assistenteclinica.acesso.SessaoDeAcesso
import com.assistenteclinica.dados.CadastroDeAcesso
import com.assistenteclinica.dados.Crianca
import com.assistenteclinica.dominio.TipoCliente
import com.assistenteclinica.seguranca.ProtecaoKeystore
import com.assistenteclinica.ui.admin.TelaCriancasInativas
import com.assistenteclinica.ui.admin.TelaRegistro
import java.security.KeyStore
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Bloco 8, etapa 6: telas da dona com uma sessão de teste (cadastro, banco e chave sorteados; dados inventados).
@RunWith(AndroidJUnit4::class)
class TelasDaDonaTest {
    @get:Rule val regra = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sufixo = Random.nextLong().toULong().toString()
    private val arquivo = "acesso_tela_$sufixo"
    private val banco = "tela_$sufixo.db"
    private val alias = "tela_$sufixo"
    private val sessao = SessaoDeAcesso(
        context, CadastroDeAcesso(context, arquivo), ProtecaoKeystore(alias),
        Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.UTC), banco, iteracoes = 1_000,
    ).apply { configurarPrimeiroUso("Dona Teste", "482913", "cavalo-azul-dourado-77") }

    @After fun apagar() {
        sessao.travar()
        runBlocking { sessao.esperarFechar() }
        context.deleteSharedPreferences(arquivo)
        context.deleteDatabase(banco)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    private fun esperarTexto(texto: String) =
        regra.waitUntil(5_000) { regra.onAllNodesWithText(texto).fetchSemanticsNodes().isNotEmpty() }

    @Test fun registroMostraOsNomes() {
        regra.setContent { TelaRegistro(sessao, {}, hoje = LocalDate.of(2026, 11, 5)) }
        esperarTexto("05/11 12h00 · Dona Teste · configurou o app")
        regra.onNodeWithText("Todas").assertExists()
    }

    @Test fun criancaInativaComADataDaDesativacao() {
        runBlocking {
            val c = sessao.banco.cadastros()
            val id = c.salvarCrianca(Crianca(nome = "Criança Teste Inativa", tipo = TipoCliente.FIXO))
            c.salvarCrianca(Crianca(id = id, nome = "Criança Teste Inativa", tipo = TipoCliente.FIXO, ativa = false))
            c.salvarCrianca(Crianca(nome = "Criança Teste Ativa", tipo = TipoCliente.FIXO))
        }
        regra.setContent { TelaCriancasInativas(sessao, {}) }
        esperarTexto("Criança Teste Inativa")
        regra.onNodeWithText("inativa desde 05/11/2026").assertExists()
        regra.onNodeWithText("Criança Teste Ativa").assertDoesNotExist()
    }
}
