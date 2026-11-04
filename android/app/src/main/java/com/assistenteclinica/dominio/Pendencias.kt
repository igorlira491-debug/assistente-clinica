package com.assistenteclinica.dominio

import java.time.LocalDateTime
import java.time.YearMonth

enum class TipoPendencia {
    ENVIAR_CONFIRMACAO, AGUARDANDO_RESPOSTA, AVISAR_TERAPEUTA, REMARCAR,
    DEFINIR_PROXIMO_PASSO, LANCAR_AGENDA_DO_MES, ENTRAR_EM_CONTATO,
    REGISTRAR_COMO_FOI, SALVAR_BACKUP,
}

data class NovaPendencia(val tipo: TipoPendencia, val criancaId: Long?, val sessaoId: Long?, val prazo: LocalDateTime)

private const val PRAZO_HORAS = 24L

// "Avisar terapeuta" não sai daqui: é agrupada por terapeuta e por dia em avisosParaTerapeutas.
fun pendenciasAoResponder(antes: Sessao, depois: Sessao, agora: LocalDateTime): List<NovaPendencia> {
    // Só a passagem para "não vai acontecer" gera Remarcar; um segundo "não" na mesma sessão não duplica.
    val virouNao = situacao(antes) != Situacao.NAO_VAI_ACONTECER && situacao(depois) == Situacao.NAO_VAI_ACONTECER
    return if (virouNao) listOf(remarcar(depois, agora)) else emptyList()
}

fun pendenciasAoEncerrar(s: Sessao, desfecho: Desfecho, tipoCliente: TipoCliente, agora: LocalDateTime): List<NovaPendencia> =
    when (desfecho) {
        Desfecho.FALTOU, Desfecho.NAO_ACONTECEU, Desfecho.CANCELADA_PELA_CLINICA -> listOf(remarcar(s, agora))
        Desfecho.REALIZADA ->
            if (tipoCliente == TipoCliente.AVULSO)
                listOf(NovaPendencia(TipoPendencia.DEFINIR_PROXIMO_PASSO, s.criancaId, s.id, agora.plusHours(PRAZO_HORAS)))
            else emptyList()
    }

private fun remarcar(s: Sessao, agora: LocalDateTime) =
    NovaPendencia(TipoPendencia.REMARCAR, s.criancaId, s.id, agora.plusHours(PRAZO_HORAS))

enum class Grupo { ATRASADA, HOJE, PROXIMA }

// jaDisponivel: a tarefa já pode ser feita agora (fica em "Para hoje" até vencer). As deixadas para uma data
// (entrar em contato daqui a N dias, adiadas) só saem de "Próximas" no dia delas.
fun grupo(prazo: LocalDateTime, agora: LocalDateTime, jaDisponivel: Boolean): Grupo = when {
    prazo < agora -> Grupo.ATRASADA
    jaDisponivel || prazo.toLocalDate() == agora.toLocalDate() -> Grupo.HOJE
    else -> Grupo.PROXIMA
}

// Calculadas a partir das sessões a cada vez: não ficam guardadas, então somem sozinhas quando a sessão muda.
data class PendenciaCalculada(val tipo: TipoPendencia, val prazo: LocalDateTime, val sessoes: List<Sessao>, val terapeutaId: Long? = null)

private const val DIA_PRAZO_LANCAR = 25

fun pendenciasCalculadas(sessoes: List<Sessao>, agora: LocalDateTime, haMesParaLancar: Boolean): List<PendenciaCalculada> {
    val confirmar = sessoes
        .filter {
            situacao(it) == Situacao.AGUARDANDO && it.familia == Resposta.AGUARDANDO && it.inicio > agora &&
                momentoConfirmacaoFamilia(it.inicio, agora) <= agora
        }
        // MIN: o prazo é o horário da escalada (16h da véspera), sem ser puxado para "agora" quando já passou.
        .map { PendenciaCalculada(TipoPendencia.ENVIAR_CONFIRMACAO, momentoEscalada(it.inicio, LocalDateTime.MIN), listOf(it)) }

    val avisar = sessoes.filter { it.inicio > agora }.map { it.inicio.toLocalDate() }.distinct().flatMap { dia ->
        avisosParaTerapeutas(sessoes, dia).map { (terapeutaId, doDia) ->
            PendenciaCalculada(TipoPendencia.AVISAR_TERAPEUTA, prazoAvisarTerapeuta(doDia), doDia, terapeutaId)
        }
    }

    val comoFoi = sessoes
        .filter { it.desfecho == null && situacao(it) != Situacao.NAO_VAI_ACONTECER && momentoComoFoi(it) <= agora }
        .map { PendenciaCalculada(TipoPendencia.REGISTRAR_COMO_FOI, momentoComoFoi(it).plusHours(PRAZO_HORAS), listOf(it)) }

    val proximoMes = YearMonth.from(agora).plusMonths(1)
    val lancar = if (haMesParaLancar && momentoLancarAgenda(proximoMes) <= agora)
        listOf(PendenciaCalculada(TipoPendencia.LANCAR_AGENDA_DO_MES, YearMonth.from(agora).atDay(DIA_PRAZO_LANCAR).atTime(9, 0), emptyList()))
    else emptyList()

    return confirmar + avisar + comoFoi + lancar
}
