package com.assistenteclinica.dominio

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class CicloSessaoTest {
    private val base = Sessao(1, 10, 20, 30, LocalDateTime.of(2026, 11, 5, 14, 0), 30, origem = Origem.AVULSA)

    @Test fun `sem respostas fica aguardando`() = assertEquals(Situacao.AGUARDANDO, situacao(base))

    @Test fun `so a familia sim continua aguardando`() =
        assertEquals(Situacao.AGUARDANDO, situacao(base.copy(familia = Resposta.SIM)))

    @Test fun `as duas sim confirma`() =
        assertEquals(Situacao.CONFIRMADA, situacao(base.copy(familia = Resposta.SIM, terapeuta = Resposta.SIM)))

    @Test fun `qualquer nao vira nao vai acontecer`() {
        assertEquals(Situacao.NAO_VAI_ACONTECER, situacao(base.copy(familia = Resposta.NAO, terapeuta = Resposta.SIM)))
        assertEquals(Situacao.NAO_VAI_ACONTECER, situacao(base.copy(terapeuta = Resposta.NAO)))
    }

    @Test fun `desfecho registrado encerra mesmo sem confirmacao`() =
        assertEquals(Situacao.ENCERRADA, situacao(base.copy(desfecho = Desfecho.REALIZADA)))

    @Test fun `fim e inicio mais a duracao`() =
        assertEquals(LocalDateTime.of(2026, 11, 5, 14, 30), base.fim)

    @Test fun `tipo padrao e comum`() = assertEquals(TipoSessao.COMUM, base.tipo)
}
