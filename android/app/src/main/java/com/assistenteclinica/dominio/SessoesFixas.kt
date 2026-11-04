package com.assistenteclinica.dominio

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

const val DIA_DE_LANCAR_O_MES = 20

// Devolve só as sessões novas do mês (id = 0; o banco atribui depois).
// Uma sessão já existe se houver qualquer uma, em qualquer situação, com a mesma criança, terapia e início:
// assim uma sessão cancelada ou remarcada não é recriada.
fun sessoesFixasDoMes(horarios: List<HorarioFixo>, existentes: List<Sessao>, mes: YearMonth, hoje: LocalDate): List<Sessao> {
    val jaExistem = existentes.map { Triple(it.criancaId, it.terapiaId, it.inicio) }.toSet()
    // Primeiro uso no meio do mês: dias passados não entram; hoje entra (o "Como foi?" cuida de horário já passado).
    val dias = generateSequence(maxOf(mes.atDay(1), hoje)) { it.plusDays(1) }
        .takeWhile { it <= mes.atEndOfMonth() }
        .toList()
    return horarios.filter { it.ativo }.flatMap { h ->
        dias.filter { it.dayOfWeek == h.diaDaSemana }
            .map { it.atTime(h.hora) }
            .filter { Triple(h.criancaId, h.terapiaId, it) !in jaExistem }
            .map { inicio ->
                Sessao(0, h.criancaId, h.terapiaId, h.terapeutaId, inicio, h.duracaoMin, origem = Origem.FIXA)
            }
    }
}

fun momentoLancarAgenda(mes: YearMonth): LocalDateTime =
    mes.minusMonths(1).atDay(DIA_DE_LANCAR_O_MES).atTime(9, 0)
