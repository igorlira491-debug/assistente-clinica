package com.assistenteclinica.dominio

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class QuemPediu { FAMILIA, TERAPEUTA, CLINICA }

// "1 sessão" / "2 sessões"; criada = "1 sessão criada" / "2 sessões criadas".
fun sessoesEmTexto(n: Int, criada: Boolean = false): String =
    if (n == 1) "1 sessão" + (if (criada) " criada" else "") else "$n sessões" + (if (criada) " criadas" else "")

fun semanaDe(dia: LocalDate): List<LocalDate> {
    val segunda = dia.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return (0L..6L).map(segunda::plusDays)
}

// Do dia 20 em diante a pendência já pede o mês seguinte (spec §5.2).
fun mesParaLancar(hoje: LocalDate): YearMonth =
    YearMonth.from(hoje).let { if (hoje.dayOfMonth >= DIA_DE_LANCAR_O_MES) it.plusMonths(1) else it }

// Sessão fixa nunca se apaga: o próximo lançamento a recriaria.
fun podeApagar(s: Sessao): Boolean =
    s.origem != Origem.FIXA && s.familia == Resposta.AGUARDANDO && s.terapeuta == Resposta.AGUARDANDO && s.desfecho == null

// Ao editar ou desativar um horário, só sai da agenda o que ainda não aconteceu e ninguém respondeu.
fun sessoesDoHorarioParaTirar(h: HorarioFixo, sessoes: List<Sessao>, agora: LocalDateTime): List<Sessao> =
    sessoes.filter {
        it.origem == Origem.FIXA && it.criancaId == h.criancaId && it.terapiaId == h.terapiaId &&
            it.inicio.dayOfWeek == h.diaDaSemana && it.inicio.toLocalTime() == h.hora && it.inicio > agora &&
            it.familia == Resposta.AGUARDANDO && it.terapeuta == Resposta.AGUARDANDO && it.desfecho == null
    }

private val DATA_BONITA = DateTimeFormatter.ofPattern("EEE, dd/MM/yyyy", Locale.forLanguageTag("pt-BR"))

fun dataBonita(d: LocalDate): String = d.format(DATA_BONITA).replace(".", "")

// A original fica como registro de quem desmarcou; a nova herda tudo menos o horário, as respostas e as marcas.
fun remarcar(original: Sessao, novoInicio: LocalDateTime, quem: QuemPediu): Pair<Sessao, Sessao> {
    val antiga = when (quem) {
        QuemPediu.FAMILIA -> original.copy(familia = Resposta.NAO)
        QuemPediu.TERAPEUTA -> original.copy(terapeuta = Resposta.NAO)
        QuemPediu.CLINICA -> original.copy(desfecho = Desfecho.CANCELADA_PELA_CLINICA)
    }
    val nova = original.copy(
        id = 0, inicio = novoInicio, origem = Origem.REMARCACAO,
        familia = Resposta.AGUARDANDO, terapeuta = Resposta.AGUARDANDO, desfecho = null,
        familiaConfirmouEm = null, lancadaDepoisEm = null, lancadaPor = null,
    )
    return antiga to nova
}

// Horário editado: cada sessão que saiu volta no novo dia e hora da MESMA semana (não no mês inteiro),
// assim a semana que ficou com a sessão antiga respondida não ganha uma segunda.
fun sessoesMovidas(tiradas: List<Sessao>, novo: HorarioFixo, existentes: List<Sessao>, agora: LocalDateTime): List<Sessao> {
    val jaExistem = existentes.map { Triple(it.criancaId, it.terapiaId, it.inicio) }.toSet()
    return tiradas
        .map { semanaDe(it.inicio.toLocalDate())[novo.diaDaSemana.value - 1].atTime(novo.hora) }
        .distinct()
        .filter { it > agora && Triple(novo.criancaId, novo.terapiaId, it) !in jaExistem }
        .map { Sessao(0, novo.criancaId, novo.terapiaId, novo.terapeutaId, it, novo.duracaoMin, origem = Origem.FIXA) }
}
