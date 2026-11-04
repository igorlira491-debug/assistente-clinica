package com.assistenteclinica.dominio

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class PendenciasTest {
    private val agora = LocalDateTime.of(2026, 11, 4, 10, 0)
    private val s = Sessao(1, 10, 20, 30, LocalDateTime.of(2026, 11, 5, 14, 0), 30, origem = Origem.FIXA)

    @Test fun `primeiro nao gera um remarcar`() {
        val r = pendenciasAoResponder(s, s.copy(familia = Resposta.NAO), agora)
        assertEquals(listOf(NovaPendencia(TipoPendencia.REMARCAR, 10, 1, agora.plusHours(24))), r)
    }

    @Test fun `segundo nao nao duplica o remarcar`() {
        val umNao = s.copy(familia = Resposta.NAO)
        assertEquals(emptyList<NovaPendencia>(), pendenciasAoResponder(umNao, umNao.copy(terapeuta = Resposta.NAO), agora))
    }

    @Test fun `sim nao gera pendencia`() =
        assertEquals(emptyList<NovaPendencia>(), pendenciasAoResponder(s, s.copy(familia = Resposta.SIM), agora))

    @Test fun `falta nao aconteceu e cancelada geram remarcar`() {
        for (d in listOf(Desfecho.FALTOU, Desfecho.NAO_ACONTECEU, Desfecho.CANCELADA_PELA_CLINICA))
            assertEquals(TipoPendencia.REMARCAR, pendenciasAoEncerrar(s, d, TipoCliente.FIXO, agora).single().tipo)
    }

    @Test fun `realizada de avulso gera definir proximo passo`() =
        assertEquals(TipoPendencia.DEFINIR_PROXIMO_PASSO,
            pendenciasAoEncerrar(s, Desfecho.REALIZADA, TipoCliente.AVULSO, agora).single().tipo)

    @Test fun `realizada de fixo nao gera pendencia`() =
        assertEquals(emptyList<NovaPendencia>(), pendenciasAoEncerrar(s, Desfecho.REALIZADA, TipoCliente.FIXO, agora))
}
