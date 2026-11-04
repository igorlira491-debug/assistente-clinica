package com.assistenteclinica.dominio

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

private val HORA_CONFIRMACAO = LocalTime.of(9, 0)
private val HORA_ESCALADA = LocalTime.of(16, 0)

// Véspera da sessão; sessão de segunda confirma na sexta anterior.
fun diaDaConfirmacao(inicio: LocalDateTime): LocalDate {
    val dia = inicio.toLocalDate()
    return if (dia.dayOfWeek == DayOfWeek.MONDAY) dia.minusDays(3) else dia.minusDays(1)
}

// Os momentos nunca ficam no passado: sessão marcada depois do horário é confirmada agora.
fun momentoConfirmacaoFamilia(inicio: LocalDateTime, agora: LocalDateTime): LocalDateTime =
    maxOf(diaDaConfirmacao(inicio).atTime(HORA_CONFIRMACAO), agora)

fun momentoEscalada(inicio: LocalDateTime, agora: LocalDateTime): LocalDateTime =
    maxOf(diaDaConfirmacao(inicio).atTime(HORA_ESCALADA), agora)

fun momentoComoFoi(s: Sessao): LocalDateTime = s.fim

private val HORA_AVISAR_TERAPEUTA = LocalTime.of(18, 0)
private const val HORAS_DEPOIS_DA_FAMILIA = 2L

// 18h da véspera ou 2 h depois da última confirmação da família no grupo, o que for mais tarde:
// uma confirmação que chega tarde dá tempo de mandar a mensagem antes de a tarefa ficar vermelha.
fun prazoAvisarTerapeuta(doDia: List<Sessao>): LocalDateTime {
    val vespera = diaDaConfirmacao(doDia.first().inicio).atTime(HORA_AVISAR_TERAPEUTA)
    val ultima = doDia.mapNotNull { it.familiaConfirmouEm }.maxOrNull() ?: return vespera
    return maxOf(vespera, ultima.plusHours(HORAS_DEPOIS_DA_FAMILIA))
}

// Sessão nova com horário que já passou (encaixe esquecido) ganha a marca de lançada depois.
fun marcarSeLancadaDepois(s: Sessao, agora: LocalDateTime, usuariaId: Long): Sessao =
    if (s.inicio < agora) s.copy(lancadaDepoisEm = agora, lancadaPor = usuariaId) else s

// Uma mensagem por terapeuta por dia, só com o que a família já confirmou e a terapeuta ainda não respondeu.
fun avisosParaTerapeutas(sessoes: List<Sessao>, dia: LocalDate): Map<Long, List<Sessao>> =
    sessoes
        .filter {
            it.inicio.toLocalDate() == dia && it.familia == Resposta.SIM &&
                it.terapeuta == Resposta.AGUARDANDO && it.desfecho == null
        }
        .sortedBy { it.inicio }
        .groupBy { it.terapeutaId }
