package com.assistenteclinica.dominio

// Precedência: desfecho registrado > algum "não" > os dois "sim" > aguardando.
fun situacao(s: Sessao): Situacao = when {
    s.desfecho != null -> Situacao.ENCERRADA
    s.familia == Resposta.NAO || s.terapeuta == Resposta.NAO -> Situacao.NAO_VAI_ACONTECER
    s.familia == Resposta.SIM && s.terapeuta == Resposta.SIM -> Situacao.CONFIRMADA
    else -> Situacao.AGUARDANDO
}
