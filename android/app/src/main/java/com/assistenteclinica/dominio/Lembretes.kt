package com.assistenteclinica.dominio

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

// Bloco 7, parte 1: o que avisar e quando, calculado antes de travar (com o banco aberto) para os próximos dias.
// O alarme toca com o app travado, então o lembrete leva só horário, tipo e um texto sem nomes (spec §5.7, §6.2).

enum class TipoLembrete { RESUMO, CONFIRMAR, ESCALADA, COMO_FOI }

// Sem campo de nome de propósito: nada de criança, responsável, terapeuta ou terapia chega à notificação.
// texto: com números, igual à tela "O que fazer" naquele momento. generico: sem números, para quando a lista
// estiver desatualizada (ver listaAtualizada).
data class Lembrete(val momento: LocalDateTime, val tipo: TipoLembrete, val texto: String, val generico: String)

// Pendência guardada (Remarcar, Próximo passo, Entrar em contato) reduzida ao que o resumo precisa.
data class PrazoGuardado(val prazo: LocalDateTime, val jaDisponivel: Boolean)

const val DIAS_DE_LEMBRETES = 14

const val GENERICO_RESUMO = "Bom dia! Há tarefas para hoje no Assistente."
const val GENERICO_CONFIRMAR = "Assistente: há confirmações para enviar."
const val GENERICO_ESCALADA = "Assistente: há famílias sem resposta. Hora de ligar."
const val GENERICO_COMO_FOI = "Assistente: registrar como foi a sessão."
// Avisos que chegaram atrasados (celular desligado, alarme adiado): um só, sem números e sem "Bom dia".
const val AVISO_ATRASADO = "Assistente: há tarefas para hoje."

fun textoAMostrar(l: Lembrete, listaAtualizada: Boolean): String = if (listaAtualizada) l.texto else l.generico

// Os números só valem se a lista foi calculada hoje (decidido pelo usuário em 2026-10-28). Mudanças feitas
// com o app aberto entram porque a lista é recalculada toda vez que o app sai da tela (SessaoDeAcesso).
fun listaAtualizada(calculadaEm: LocalDateTime, agora: LocalDateTime): Boolean =
    calculadaEm.toLocalDate() == agora.toLocalDate()

private val HORA_RESUMO = LocalTime.of(8, 0)
private val HORA_CONFIRMAR = LocalTime.of(9, 0)
private val HORA_ESCALADA = LocalTime.of(16, 0)

// A mesma regra da tela: "Entrar em contato" e as adiadas só saem de "Próximas" no dia delas.
fun jaDisponivel(tipo: TipoPendencia, adiada: Boolean): Boolean = tipo != TipoPendencia.ENTRAR_EM_CONTATO && !adiada

fun tarefasEmTexto(n: Int) = if (n == 1) "1 tarefa" else "$n tarefas"
fun atrasadasEmTexto(n: Int) = if (n == 1) "1 atrasada" else "$n atrasadas"
fun confirmacoesEmTexto(n: Int) = if (n == 1) "1 confirmação" else "$n confirmações"
fun familiasEmTexto(n: Int) = if (n == 1) "1 família" else "$n famílias"

fun lembretes(
    sessoes: List<Sessao>, guardadas: List<PrazoGuardado>, haMesEm: (LocalDate) -> Boolean,
    agora: LocalDateTime, dias: Int = DIAS_DE_LEMBRETES,
): List<Lembrete> {
    val primeiro = agora.toLocalDate()
    val fimDoHorizonte = primeiro.plusDays(dias.toLong()).atStartOfDay()
    val porDia = (0 until dias).flatMap { i ->
        val d = primeiro.plusDays(i.toLong())
        // A confirmação das sessões do dia d vale desde as 9h de d: a lista é a mesma às 9h e às 16h, calculada uma vez.
        val aConfirmar = aConfirmarNoDia(sessoes, d, d.atTime(HORA_CONFIRMAR))
        listOfNotNull(resumo(sessoes, guardadas, haMesEm(d), d.atTime(HORA_RESUMO)), confirmar(aConfirmar, d), escalada(aConfirmar, d))
    }
    val comoFoi = sessoes
        .filter { it.desfecho == null && situacao(it) != Situacao.NAO_VAI_ACONTECER && it.fim < fimDoHorizonte }
        .groupBy { it.fim }
        .map { (fim, doHorario) -> Lembrete(fim, TipoLembrete.COMO_FOI, textoComoFoi(doHorario, fim), GENERICO_COMO_FOI) }
    return (porDia + comoFoi).filter { it.momento > agora }.sortedBy { it.momento }
}

// Os mesmos números da tela "O que fazer" às 8h: 🔴 Atrasadas + 🟠 Para hoje (as ⚪ Próximas não contam).
private fun resumo(sessoes: List<Sessao>, guardadas: List<PrazoGuardado>, haMes: Boolean, m: LocalDateTime): Lembrete? {
    val itens = pendenciasCalculadas(sessoes, m, haMes).map { PrazoGuardado(it.prazo, jaDisponivel = true) } + guardadas
    val grupos = itens.map { grupo(it.prazo, m, it.jaDisponivel) }
    val hoje = grupos.count { it != Grupo.PROXIMA }
    val atrasadas = grupos.count { it == Grupo.ATRASADA }
    if (hoje == 0) return null
    val texto = "Bom dia! Você tem ${tarefasEmTexto(hoje)} hoje" + (if (atrasadas > 0) " (${atrasadasEmTexto(atrasadas)})." else ".")
    return Lembrete(m, TipoLembrete.RESUMO, texto, GENERICO_RESUMO)
}

// Sessões cuja confirmação é feita no dia d (a véspera: as de amanhã; na sexta, também as de segunda) e que
// a família ainda não respondeu. Sessões de hoje nunca entram: a confirmação delas era ontem e já está no resumo.
private fun aConfirmarNoDia(sessoes: List<Sessao>, d: LocalDate, m: LocalDateTime): List<Sessao> =
    pendenciasCalculadas(sessoes, m, false)
        .filter { it.tipo == TipoPendencia.ENVIAR_CONFIRMACAO }
        .map { it.sessoes.first() }
        .filter { it.inicio.toLocalDate() > d && diaDaConfirmacao(it.inicio) == d }

private fun confirmar(aConfirmar: List<Sessao>, d: LocalDate): Lembrete? {
    val m = d.atTime(HORA_CONFIRMAR)
    val n = aConfirmar.size
    return if (n > 0) Lembrete(m, TipoLembrete.CONFIRMAR, "Assistente: ${confirmacoesEmTexto(n)} para enviar.", GENERICO_CONFIRMAR) else null
}

// Às 16h: as mesmas sessões (a escalada delas é sempre 16h do dia da confirmação), contadas por família:
// a mesma criança com duas sessões é uma ligação só.
private fun escalada(aConfirmar: List<Sessao>, d: LocalDate): Lembrete? {
    val m = d.atTime(HORA_ESCALADA)
    val n = aConfirmar.distinctBy { it.criancaId }.size
    return if (n > 0) Lembrete(m, TipoLembrete.ESCALADA, "Assistente: ${familiasEmTexto(n)} sem resposta. Hora de ligar.", GENERICO_ESCALADA) else null
}

// Pelo horário de início ("a sessão das 14h"); se as que terminam juntas começaram em horários diferentes, pelo fim.
private fun textoComoFoi(doHorario: List<Sessao>, fim: LocalDateTime): String {
    val inicios = doHorario.map { it.inicio }.distinct()
    return when {
        doHorario.size == 1 -> "Registrar como foi a sessão das ${horaCurta(inicios.single().toLocalTime())}"
        inicios.size == 1 -> "Registrar como foi ${doHorario.size} sessões das ${horaCurta(inicios.single().toLocalTime())}"
        else -> "Registrar como foi ${doHorario.size} sessões que terminaram às ${horaCurta(fim.toLocalTime())}"
    }
}

// O que mostrar quando o alarme toca (ou o celular liga): o vencido de hoje na hora (até TOLERANCIA_DO_ALARME),
// um de cada tipo (o mais recente), e todos os "Como foi?", porque cada um é de um horário. O que venceu hoje e
// passou da tolerância vira um aviso só (AVISO_ATRASADO): ligar às 21h não mostra "Bom dia!" com números velhos.
// O vencido de dias anteriores é descartado. Com o app na frente, nada vira notificação (ela já está vendo a
// tela); o vencido sai da lista do mesmo jeito.
data class Disparo(val mostrar: List<Lembrete>, val restante: List<Lembrete>)

val TOLERANCIA_DO_ALARME: Duration = Duration.ofMinutes(30)

fun disparar(lista: List<Lembrete>, agora: LocalDateTime, emPrimeiroPlano: Boolean = false): Disparo {
    val (vencidos, futuros) = lista.partition { it.momento <= agora }
    if (emPrimeiroPlano) return Disparo(emptyList(), futuros.sortedBy { it.momento })
    val deHoje = vencidos.filter { it.momento.toLocalDate() == agora.toLocalDate() }
    val (atrasados, naHora) = deHoje.partition { it.momento < agora.minus(TOLERANCIA_DO_ALARME) }
    val comoFoi = naHora.filter { it.tipo == TipoLembrete.COMO_FOI }
    val outros = naHora.filter { it.tipo != TipoLembrete.COMO_FOI }.groupBy { it.tipo }.map { (_, l) -> l.maxBy { it.momento } }
    val atrasado = if (atrasados.isEmpty()) emptyList() else listOf(Lembrete(agora, TipoLembrete.RESUMO, AVISO_ATRASADO, AVISO_ATRASADO))
    return Disparo((atrasado + outros + comoFoi).sortedBy { it.momento }, futuros.sortedBy { it.momento })
}

// ---- Bloco 7, parte 2: o que o Android usa sem abrir o banco ----

// A lista fica fora do banco, no armazenamento que o Android libera antes do desbloqueio (spec §6.2): só texto
// simples, uma linha por lembrete. Os textos são os fixos deste arquivo (sem tabulação nem quebra de linha).
fun listaParaTexto(calculadaEm: LocalDateTime, lista: List<Lembrete>): String =
    (listOf(calculadaEm.toString()) + lista.map { "${it.momento}\t${it.tipo.name}\t${limpo(it.texto)}\t${limpo(it.generico)}" })
        .joinToString("\n")

private fun limpo(t: String) = t.replace('\t', ' ').replace('\n', ' ')

data class ListaGravada(val calculadaEm: LocalDateTime, val lembretes: List<Lembrete>)

// null se o texto estiver vazio ou estragado: o app trata como "sem lista" (nenhum aviso até o próximo cálculo).
fun textoParaLista(texto: String?): ListaGravada? = runCatching {
    val linhas = texto!!.split("\n")
    ListaGravada(LocalDateTime.parse(linhas.first()), linhas.drop(1).filter { it.isNotBlank() }.map { l ->
        val (m, t, x, g) = l.split("\t")
        Lembrete(LocalDateTime.parse(m), TipoLembrete.valueOf(t), x, g)
    })
}.getOrNull()

// Quando o próximo alarme deve tocar (o primeiro lembrete depois de agora); null = nada a agendar.
fun proximoAlarme(lista: List<Lembrete>, agora: LocalDateTime): LocalDateTime? =
    lista.map { it.momento }.filter { it > agora }.minOrNull()

enum class ModoDoAlarme { EXATO, APROXIMADO }

// Sem a permissão de alarme exato (desligada por padrão no Android 14+), o aviso ainda sai, mas pode atrasar.
fun modoDoAlarme(podeExato: Boolean): ModoDoAlarme = if (podeExato) ModoDoAlarme.EXATO else ModoDoAlarme.APROXIMADO

// Só o que o Android informa de forma confiável. A bateria fica de fora: é instrução, não checagem (spec §5.7).
data class EstadoDosAvisos(val notificacoes: Boolean, val alarmeExato: Boolean) {
    val tudoPronto: Boolean get() = notificacoes && alarmeExato
}

// Versão pública da notificação, para a tela bloqueada: sem número, horário ou nome.
const val AVISO_PUBLICO = "Aviso do Assistente"

// Passos de bateria em texto, pela marca do celular. Os nomes dos menus de cada marca são [A CONFIRMAR no aparelho].
fun guiaDeBateria(fabricante: String): List<String> {
    val padrao = "Em Configurações → Apps → Assistente → Bateria, escolha \"Sem restrição\"."
    return when (fabricante.lowercase().trim()) {
        "samsung" -> listOf(padrao, "Em Configurações → Bateria → Limites de uso em segundo plano, coloque o Assistente em \"Apps nunca em suspensão\".")
        "xiaomi", "poco", "redmi" -> listOf(
            "Em Configurações → Apps → Gerenciar apps → Assistente → Economia de bateria, escolha \"Sem restrições\".",
            "Na mesma tela do Assistente, ligue \"Início automático\" (sem ele, os avisos podem não voltar depois de reiniciar o celular).",
        )
        "motorola" -> listOf(padrao, "Em Configurações → Bateria → Otimização adaptativa, deixe o Assistente fora da otimização.")
        else -> listOf(padrao)
    }
}
