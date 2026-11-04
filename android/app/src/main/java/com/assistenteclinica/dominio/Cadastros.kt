package com.assistenteclinica.dominio

import java.text.Normalizer
import java.time.LocalDate

const val AVISO_OBSERVACAO = "Não escreva informações de saúde aqui."
const val MAX_OBSERVACAO = 200

// Telefone no formato do wa.me: 55 + DDD + número. null = não é um número do Brasil com DDD.
fun telefoneWhatsApp(texto: String): String? {
    var n = texto.filter { it in '0'..'9' }
    if (n.length in 12..13 && n.startsWith("55")) n = n.drop(2)
    if (n.length in 11..12 && n.startsWith("0")) n = n.drop(1) // 0 de ligação interurbana
    val dddOk = n.length >= 2 && n[0] != '0' && n[1] != '0'
    val fixo = n.length == 10 && n[2] in '2'..'5'
    val celular = n.length == 11 && n[2] == '9'
    return if (dddOk && (fixo || celular)) "55$n" else null
}

fun nomeValido(nome: String): Boolean = nome.trim().length in 1..80

fun duracaoValida(minutos: Int): Boolean = minutos in 5..240

fun observacaoValida(texto: String): Boolean = texto.length <= MAX_OBSERVACAO

fun consentimentoValido(data: LocalDate, hoje: LocalDate): Boolean = !data.isAfter(hoje)

// A criança aparece com aviso (nunca bloqueio, spec §6.4) se não tem responsável ou se algum não consentiu.
fun precisaAvisoDeConsentimento(consentimentos: List<LocalDate?>): Boolean =
    consentimentos.isEmpty() || consentimentos.any { it == null }

fun combinaComBusca(nome: String, busca: String): Boolean = semAcento(nome).contains(semAcento(busca.trim()))

private val ACENTOS = Regex("\\p{Mn}+")

private fun semAcento(texto: String): String = Normalizer.normalize(texto, Normalizer.Form.NFD).replace(ACENTOS, "").lowercase()
