package com.assistenteclinica.dominio

import java.time.LocalDate
import java.time.LocalDateTime

private val VAI_ACONTECER = setOf(Situacao.AGUARDANDO, Situacao.CONFIRMADA)

// Só o que ainda não começou: a sessão da manhã que já aconteceu não vira falta quando a terapeuta sai à tarde.
private fun vaiAcontecerNoDia(s: Sessao, dia: LocalDate, agora: LocalDateTime) =
    s.inicio.toLocalDate() == dia && s.inicio > agora && situacao(s) in VAI_ACONTECER

// Devolvem só as sessões afetadas, já mudadas; quem grava cria um "Remarcar" para cada uma.
fun indisponivel(sessoes: List<Sessao>, terapeutaId: Long, dia: LocalDate, agora: LocalDateTime): List<Sessao> =
    sessoes.filter { it.terapeutaId == terapeutaId && vaiAcontecerNoDia(it, dia, agora) }.map { it.copy(terapeuta = Resposta.NAO) }

fun clinicaFechada(sessoes: List<Sessao>, dia: LocalDate, agora: LocalDateTime): List<Sessao> =
    sessoes.filter { vaiAcontecerNoDia(it, dia, agora) }.map { it.copy(desfecho = Desfecho.CANCELADA_PELA_CLINICA) }

enum class QuemFaltou { FAMILIA, TERAPEUTA, CLINICA, NAO_ACONTECEU }

// O desfecho registrado vale mais que as respostas; null = não é falta.
fun quemFaltou(s: Sessao): QuemFaltou? = when (s.desfecho) {
    Desfecho.CANCELADA_PELA_CLINICA -> QuemFaltou.CLINICA
    Desfecho.NAO_ACONTECEU -> QuemFaltou.NAO_ACONTECEU
    Desfecho.FALTOU -> QuemFaltou.FAMILIA
    Desfecho.REALIZADA -> null
    null -> when {
        s.terapeuta == Resposta.NAO -> QuemFaltou.TERAPEUTA
        s.familia == Resposta.NAO -> QuemFaltou.FAMILIA
        else -> null
    }
}

fun faltasDoMes(sessoes: List<Sessao>): List<Pair<Sessao, QuemFaltou>> =
    sessoes.sortedBy { it.inicio }.mapNotNull { s -> quemFaltou(s)?.let { s to it } }

fun atendimentosPorTerapeuta(sessoes: List<Sessao>): Map<Long, List<Sessao>> =
    sessoes.filter { it.desfecho == Desfecho.REALIZADA }.sortedBy { it.inicio }.groupBy { it.terapeutaId }
