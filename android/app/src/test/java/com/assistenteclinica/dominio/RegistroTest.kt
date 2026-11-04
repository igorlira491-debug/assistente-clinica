package com.assistenteclinica.dominio

import com.assistenteclinica.dados.LinhaRegistro
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistroTest {
    private val em = LocalDateTime.of(2026, 11, 5, 14, 5)

    @Test fun salvarCadastroViraCriarAlterarDesativarOuReativar() {
        assertEquals(Acao.CRIOU, acaoAoSalvar(novo = true, ativaAntes = null, ativaDepois = true))
        assertEquals(Acao.ALTEROU, acaoAoSalvar(novo = false, ativaAntes = true, ativaDepois = true))
        assertEquals(Acao.DESATIVOU, acaoAoSalvar(novo = false, ativaAntes = true, ativaDepois = false))
        assertEquals(Acao.REATIVOU, acaoAoSalvar(novo = false, ativaAntes = false, ativaDepois = true))
        assertEquals(Acao.ALTEROU, acaoAoSalvar(novo = false, ativaAntes = false, ativaDepois = false))
    }

    @Test fun respostasDesfechosEQuemPediuTemDetalheProprio() {
        assertEquals(Detalhe.FAMILIA_SIM, detalheDaResposta(daFamilia = true, sim = true))
        assertEquals(Detalhe.FAMILIA_NAO, detalheDaResposta(daFamilia = true, sim = false))
        assertEquals(Detalhe.TERAPEUTA_SIM, detalheDaResposta(daFamilia = false, sim = true))
        assertEquals(Detalhe.TERAPEUTA_NAO, detalheDaResposta(daFamilia = false, sim = false))
        assertEquals(Desfecho.entries.size, Desfecho.entries.map(::detalheDoDesfecho).toSet().size)
        assertEquals(QuemPediu.entries.size, QuemPediu.entries.map(::detalheDeQuemPediu).toSet().size)
    }

    @Test fun textoDaLinhaSoComNumeros() {
        assertEquals("entrou", textoDoRegistro(Registro(em, 1, Acao.ENTROU)))
        assertEquals(
            "marcou a resposta de a sessão 381 (família: sim)",
            textoDoRegistro(Registro(em, 2, Acao.MARCOU_RESPOSTA, Alvo.SESSAO, 381, 12, Detalhe.FAMILIA_SIM)),
        )
        assertEquals("errou o PIN a usuária 2 (3 vezes)", textoDoRegistro(Registro(em, 2, Acao.ERROS_DE_PIN, Alvo.USUARIA, 2, quantidade = 3)))
        assertEquals("lançou o mês (1 sessão)", textoDoRegistro(Registro(em, 1, Acao.LANCOU_O_MES, quantidade = 1)))
        assertEquals("lançou o mês (40 sessões)", textoDoRegistro(Registro(em, 1, Acao.LANCOU_O_MES, quantidade = 40)))
        assertEquals(
            "deixou para lembrar depois a pendência 7 (15 dias)",
            textoDoRegistro(Registro(em, 1, Acao.LEMBRAR_DEPOIS, Alvo.PENDENCIA, 7, quantidade = 15)),
        )
        assertEquals("saiu (por tempo sem uso)", textoDoRegistro(Registro(em, 1, Acao.SAIU, detalhe = Detalhe.POR_TEMPO)))
    }

    // A garantia de "nenhum texto livre" (spec §6.3): a linha do banco não tem campo String. Ação, alvo e detalhe
    // são enums, então só cabem os valores das listas fechadas.
    @Test fun linhaDoBancoNaoTemCampoDeTexto() {
        val campos = LinhaRegistro::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
        assertTrue(campos.isNotEmpty())
        assertTrue(campos.joinToString { "${it.name}: ${it.type.simpleName}" }, campos.none { it.type == String::class.java })
    }

    // ---- Tela da dona (etapa 6) ----

    private val nomes = NomesDoRegistro(
        usuarias = mapOf(1L to "Ana"), criancas = mapOf(12L to "João"),
        terapeutas = mapOf(3L to "Carla"), responsaveis = mapOf(7L to "Maria"),
    )

    @Test fun linhaComNomesNaHoraDeMostrar() {
        assertEquals("05/11 14h05 · Ana · remarcou a sessão de João (pedido da família)",
            textoComNomes(Registro(em, 1, Acao.REMARCOU, Alvo.SESSAO, 381, 12, Detalhe.FAMILIA), nomes))
        assertEquals("05/11 14h05 · Ana · desativou a terapeuta Carla", textoComNomes(Registro(em, 1, Acao.DESATIVOU, Alvo.TERAPEUTA, 3), nomes))
        assertEquals("05/11 14h05 · Ana · registrou o consentimento do responsável Maria",
            textoComNomes(Registro(em, 1, Acao.REGISTROU_CONSENTIMENTO, Alvo.RESPONSAVEL, 7, 12), nomes))
        assertEquals("05/11 14h05 · Ana · redefiniu o PIN da usuária Ana", textoComNomes(Registro(em, 1, Acao.REDEFINIU_PIN, Alvo.USUARIA, 1), nomes))
        assertEquals("05/11 14h05 · o app · preparou o backup", textoComNomes(Registro(em, null, Acao.PREPAROU_O_BACKUP), nomes))
    }

    @Test fun apagadoAPedidoViraRegistroRemovido() {
        assertEquals("05/11 14h05 · Ana · alterou a criança (registro removido)", textoComNomes(Registro(em, 1, Acao.ALTEROU, Alvo.CRIANCA, 99, 99), nomes))
        assertEquals("05/11 14h05 · Ana · registrou como foi a sessão (registro removido) (realizada)",
            textoComNomes(Registro(em, 1, Acao.REGISTROU_COMO_FOI, Alvo.SESSAO, 5, 99, Detalhe.REALIZADA), nomes))
        assertEquals("05/11 14h05 · usuária 9 · entrou", textoComNomes(Registro(em, 9, Acao.ENTROU), nomes))
    }

    @Test fun inativasDaMaisAntigaParaAMaisNova() {
        val lista = listOf("B" to em, "sem data" to null, "A" to em.minusDays(30))
        assertEquals(listOf("sem data", "A", "B"), maisAntigasPrimeiro(lista).map { it.first })
        assertEquals("inativa desde 05/11/2026", inativaDesde(em))
        assertEquals("inativa desde antes do registro de ações", inativaDesde(null))
    }
}
