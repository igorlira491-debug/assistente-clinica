package com.assistenteclinica.dominio

import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

// Bloco 6 (parte que não depende da secretária): textos e links prontos. O envio é sempre a secretária
// tocando em "enviar" no WhatsApp; o app nunca manda sozinho (spec §4.2).

// O mesmo link abre os dois; só muda o pacote para o Android escolher o app certo.
enum class AppWhatsApp(val pacote: String) { COMUM("com.whatsapp"), BUSINESS("com.whatsapp.w4b") }

// Marcadores dos modelos. Não existe marcador de terapia nem de diagnóstico: a notificação aparece
// na tela bloqueada dos pais (spec §5.3, texto mínimo LGPD).
const val MARCA_NOME = "{nome}"
const val MARCA_SESSOES = "{sessoes}"
const val MARCA_QUANDO = "{quando}"

// Padrões tirados do exemplo do desenho (§5.3); a dona pode trocar o texto, mantendo os marcadores.
const val MODELO_FAMILIA_PADRAO = "Olá, {nome}! Passando para confirmar {sessoes} {quando} aqui na clínica. Pode confirmar pra gente? 😊"
const val MODELO_TERAPEUTA_PADRAO = "Olá, {nome}! Sessões confirmadas para {quando}: {sessoes}. Equipe da clínica"

// Um modelo só vale se tiver os três marcadores; senão o app volta ao padrão.
fun modeloValido(modelo: String): Boolean = listOf(MARCA_NOME, MARCA_SESSOES, MARCA_QUANDO).all { it in modelo }

data class Contato(val id: Long, val nome: String, val telefone: String, val principal: Boolean = false)

// Padrão: o responsável principal; a secretária pode escolher outro na hora de enviar (spec §5.3).
fun destinatario(responsaveis: List<Contato>, escolhidoId: Long? = null): Contato? =
    responsaveis.firstOrNull { it.id == escolhidoId } ?: responsaveis.firstOrNull { it.principal } ?: responsaveis.firstOrNull()

fun primeiroNome(nome: String): String = nome.trim().substringBefore(' ')

// "14h" ou "14h30".
fun horaCurta(h: LocalTime): String = if (h.minute == 0) "${h.hour}h" else "${h.hour}h%02d".format(h.minute)

private val PT_BR = Locale.forLanguageTag("pt-BR")

// Relativo ao dia do envio: "amanhã (quinta)", "hoje (quinta)" ou "segunda (09/11)" (sessão de segunda confirmada na sexta).
fun quandoEmTexto(dia: LocalDate, hoje: LocalDate): String {
    val semana = dia.dayOfWeek.getDisplayName(TextStyle.FULL, PT_BR).substringBefore('-')
    return when (dia) {
        hoje -> "hoje ($semana)"
        hoje.plusDays(1) -> "amanhã ($semana)"
        else -> "$semana (%02d/%02d)".format(dia.dayOfMonth, dia.monthValue)
    }
}

// "A, B e C".
private fun lista(itens: List<String>): String =
    if (itens.size <= 1) itens.joinToString() else itens.dropLast(1).joinToString(", ") + " e " + itens.last()

// Para a família: "a sessão de João às 14h" / "as sessões de João às 14h e de Maria às 15h".
// Usa "de" em vez de "do/da" porque o cadastro não guarda o gênero da criança.
fun sessoesParaFamilia(sessoes: List<Pair<String, LocalDateTime>>): String {
    val porCrianca = sessoes.sortedBy { it.second }.groupBy({ primeiroNome(it.first) }, { horaCurta(it.second.toLocalTime()) })
    val partes = porCrianca.map { (nome, horas) -> "de $nome às ${lista(horas)}" }
    return (if (sessoes.size == 1) "a sessão " else "as sessões ") + lista(partes)
}

// Para a terapeuta: "João às 14h e Maria às 15h" (primeiro nome e horário, spec §5.3).
fun sessoesParaTerapeuta(sessoes: List<Pair<String, LocalDateTime>>): String =
    lista(sessoes.sortedBy { it.second }.map { "${primeiroNome(it.first)} às ${horaCurta(it.second.toLocalTime())}" })

fun preencher(modelo: String, padrao: String, nome: String, sessoes: String, quando: String): String =
    (if (modeloValido(modelo)) modelo else padrao)
        .replace(MARCA_NOME, primeiroNome(nome)).replace(MARCA_SESSOES, sessoes).replace(MARCA_QUANDO, quando)

// Uma mensagem pronta: para quem, o texto e as sessões que ela cobre.
data class Mensagem(val para: Contato, val texto: String, val sessoes: List<Sessao>)

// Sessões do dia que ainda esperam a resposta da família (mesma regra da pendência "Confirmar").
fun aConfirmarComFamilia(sessoes: List<Sessao>, dia: LocalDate): List<Sessao> =
    sessoes.filter { it.inicio.toLocalDate() == dia && situacao(it) == Situacao.AGUARDANDO && it.familia == Resposta.AGUARDANDO }

// Uma mensagem por responsável por dia, juntando os filhos dele (spec §5.3). Criança sem responsável com
// telefone fica de fora: a tela mostra isso à parte.
fun mensagensParaFamilias(
    sessoes: List<Sessao>, dia: LocalDate, hoje: LocalDate,
    nomeDaCrianca: (Long) -> String, responsaveisDe: (Long) -> List<Contato>,
    escolhidos: Map<Long, Long> = emptyMap(), modelo: String = MODELO_FAMILIA_PADRAO,
): List<Mensagem> =
    aConfirmarComFamilia(sessoes, dia)
        .mapNotNull { s -> destinatario(responsaveisDe(s.criancaId), escolhidos[s.criancaId])?.let { it to s } }
        .groupBy({ it.first.id }, { it })
        .map { (_, pares) ->
            val para = pares.first().first
            val doResponsavel = pares.map { it.second }.sortedBy { it.inicio }
            val texto = sessoesParaFamilia(doResponsavel.map { nomeDaCrianca(it.criancaId) to it.inicio })
            Mensagem(para, preencher(modelo, MODELO_FAMILIA_PADRAO, para.nome, texto, quandoEmTexto(dia, hoje)), doResponsavel)
        }

// Uma mensagem por terapeuta por dia, só com o que a família já confirmou (avisosParaTerapeutas).
fun mensagensParaTerapeutas(
    sessoes: List<Sessao>, dia: LocalDate, hoje: LocalDate,
    nomeDaCrianca: (Long) -> String, terapeuta: (Long) -> Contato?, modelo: String = MODELO_TERAPEUTA_PADRAO,
): List<Mensagem> =
    avisosParaTerapeutas(sessoes, dia).mapNotNull { (terapeutaId, doDia) ->
        val para = terapeuta(terapeutaId) ?: return@mapNotNull null
        val texto = sessoesParaTerapeuta(doDia.map { nomeDaCrianca(it.criancaId) to it.inicio })
        Mensagem(para, preencher(modelo, MODELO_TERAPEUTA_PADRAO, para.nome, texto, quandoEmTexto(dia, hoje)), doDia)
    }

// Link oficial "clique para conversar": número internacional só com dígitos e texto codificado (espaço = %20).
// null se o telefone não for um celular/fixo do Brasil válido.
fun linkWhatsApp(telefone: String, texto: String): String? {
    val numero = telefoneWhatsApp(telefone) ?: return null
    // encode(String, Charset) só existe a partir do Android 13; o app vai até o 12.
    return "https://wa.me/$numero?text=" + URLEncoder.encode(texto, "UTF-8").replace("+", "%20")
}
