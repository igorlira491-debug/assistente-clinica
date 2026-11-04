package com.assistenteclinica.dominio

import java.security.MessageDigest

// Bloco 8, etapa 2: código de conferência (plano 2026-10-29, parte 2). Resume o banco inteiro em quantidades por
// tabela e num código de 8 letras, sem nenhum dado: o backup guarda o do momento da cópia, e a restauração recalcula
// e compara. Só o conteúdo entra (não a versão do banco nem a ordem física das linhas).

// Uma tabela já lida, com as linhas em ordem fixa (quem lê ordena por todas as colunas). Cada valor é Long, Double,
// String, ByteArray ou null, como o SQLite guarda.
class TabelaLida(val nome: String, val linhas: List<List<Any?>>)

data class Conferencia(val quantidades: Map<String, Int>, val codigo: String)

// Sem 0/O, 1/I/L nem U: dá para ditar e comparar de olho sem confundir (alfabeto de Crockford).
private const val ALFABETO = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

fun conferencia(tabelas: List<TabelaLida>): Conferencia {
    val ordenadas = tabelas.sortedBy { it.nome }
    val sha = MessageDigest.getInstance("SHA-256")
    ordenadas.forEach { t ->
        campo(sha, "T", t.nome)
        campo(sha, "N", t.linhas.size.toString())
        t.linhas.forEach { linha ->
            sha.update('R'.code.toByte())
            linha.forEach { valor(sha, it) }
        }
    }
    return Conferencia(ordenadas.associate { it.nome to it.linhas.size }, codigoDe(sha.digest()))
}

// Cada valor leva o tipo e o tamanho: null, "" e "null" dão códigos diferentes, e nada se mistura com o vizinho.
private fun valor(sha: MessageDigest, v: Any?) = when (v) {
    null -> sha.update('Z'.code.toByte())
    is Long -> campo(sha, "I", v.toString())
    is Int -> campo(sha, "I", v.toString())
    is Double -> campo(sha, "F", v.toBits().toString())
    is String -> campo(sha, "S", v)
    is ByteArray -> { campo(sha, "B", v.size.toString()); sha.update(v) }
    else -> error("tipo inesperado no banco: ${v::class.simpleName}")
}

private fun campo(sha: MessageDigest, tipo: String, texto: String) {
    val bytes = texto.toByteArray(Charsets.UTF_8)
    sha.update("$tipo${bytes.size}:".toByteArray(Charsets.UTF_8))
    sha.update(bytes)
}

// Os primeiros 40 bits do SHA-256 em 8 letras, no formato "K7QF-2M9D".
private fun codigoDe(digest: ByteArray): String {
    var bits = 0L
    for (i in 0 until 5) bits = (bits shl 8) or (digest[i].toLong() and 0xFF)
    val letras = (7 downTo 0).map { ALFABETO[((bits shr (it * 5)) and 31).toInt()] }.joinToString("")
    return letras.take(4) + "-" + letras.drop(4)
}

// O que a tela mostra ("12 crianças · 340 sessões"): só as tabelas que a secretária reconhece, nesta ordem.
// As outras (ligações, configurações) entram no código, mas não no texto.
private val NA_TELA = listOf(
    Triple("Crianca", "criança", "crianças"),
    Triple("Responsavel", "responsável", "responsáveis"),
    Triple("Terapeuta", "terapeuta", "terapeutas"),
    Triple("Terapia", "terapia", "terapias"),
    Triple("Horario", "horário fixo", "horários fixos"),
    Triple("Sessao", "sessão", "sessões"),
    Triple("Pendencia", "pendência", "pendências"),
    Triple("Registro", "linha do registro", "linhas do registro"),
)

fun quantidadesEmTexto(c: Conferencia): String = NA_TELA.mapNotNull { (tabela, um, varios) ->
    c.quantidades[tabela]?.let { n -> if (n == 1) "1 $um" else "$n $varios" }
}.joinToString(" · ")
