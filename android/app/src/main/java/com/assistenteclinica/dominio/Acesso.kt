package com.assistenteclinica.dominio

import java.time.Duration
import java.time.Instant

enum class Papel { DONA, SECRETARIA }

data class Usuaria(val id: Long, val nome: String, val papel: Papel, val ativa: Boolean = true)

const val ERROS_SEM_ESPERA = 5
val TEMPO_SEM_USO: Duration = Duration.ofMinutes(3)
val TOLERANCIA_WHATSAPP: Duration = Duration.ofMinutes(3)

// A marca "vou sair pelo WhatsApp" só vale se a saída vier logo em seguida. Se a outra tela não tirou o app
// da tela (seletor translúcido) ou a tela foi recriada, a marca expira sozinha e a próxima saída trava.
val VALIDADE_MARCA_DE_SAIDA: Duration = Duration.ofSeconds(10)

private val PRIMEIRA_ESPERA: Duration = Duration.ofSeconds(30)
private val ESPERA_MAXIMA: Duration = Duration.ofHours(1)
private val PINS_OBVIOS = setOf("123456", "654321")

// 6 dígitos; recusa os mais fáceis de adivinhar (todos iguais, sequência).
fun pinValido(pin: String): Boolean =
    pin.length == 6 && pin.all { it in '0'..'9' } && pin.toSet().size > 1 && pin !in PINS_OBVIOS

// Senha de backup nova ou trocada (revisão de segurança, item D, aprovado pelo usuário em 2026-10-29). É a única
// proteção do arquivo no Drive, onde dá para tentar senhas sem limite. As que já existem continuam valendo: só
// quem cria ou troca passa por estas regras. null = aceita; senão, o motivo para a tela.
const val TAMANHO_MINIMO_SENHA_BACKUP = 16
private val PALAVRAS_OBVIAS = listOf("assistente", "senha", "backup", "clinica", "clínica", "qwerty", "asdf")

fun problemaDaSenhaBackup(senha: String): String? {
    if (senha.length < TAMANHO_MINIMO_SENHA_BACKUP) return "A senha de backup precisa ter $TAMANHO_MINIMO_SENHA_BACKUP ou mais caracteres."
    if (senha.lowercase().toSet().size < 6) return "Use uma frase com palavras diferentes, não caracteres repetidos."
    if (ehSequencia(senha.lowercase())) return "Não use sequências como 123456789 ou abcdefgh."
    // Sem as palavras óbvias e sem os números, ainda precisa sobrar uma frase de verdade.
    var resto = senha.lowercase()
    PALAVRAS_OBVIAS.forEach { resto = resto.replace(it, "") }
    if (resto.filter { it.isLetter() }.length < 10) return "Não use o nome da clínica, \"senha\" ou \"backup\" como parte principal. Use uma frase só sua."
    return null
}

// Cada caractere é o anterior +1 ou -1 (abcd, 4321), repetindo ou não ("1234567890123456").
private fun ehSequencia(s: String): Boolean {
    val semEspacos = s.filter { !it.isWhitespace() }
    return semEspacos.zipWithNext().all { (a, b) -> b - a == 1 || a - b == 1 || (a == '9' && b == '0') || (a == '0' && b == '9') }
}

fun senhaBackupValida(senha: String): Boolean = problemaDaSenhaBackup(senha) == null

// Até o 5º erro, sem espera; a partir dele, 30 s que dobram a cada erro, com teto de 1 h.
fun esperaAposErros(erros: Int): Duration {
    if (erros < ERROS_SEM_ESPERA) return Duration.ZERO
    val dobras = (erros - ERROS_SEM_ESPERA).coerceAtMost(7) // 30 s * 2^7 já passa de 1 h; evita estouro
    return PRIMEIRA_ESPERA.multipliedBy(1L shl dobras).coerceAtMost(ESPERA_MAXIMA)
}

fun podeTentarPin(erros: Int, ultimoErro: Instant?, agora: Instant): Boolean {
    if (ultimoErro == null) return true
    // Relógio voltou (troca manual, bateria arriada): libera em vez de prender a clínica numa espera sem fim.
    if (agora.isBefore(ultimoErro)) return true
    return !agora.isBefore(ultimoErro.plus(esperaAposErros(erros)))
}

fun travouPorTempo(ultimaAtividade: Instant, agora: Instant): Boolean =
    Duration.between(ultimaAtividade, agora) >= TEMPO_SEM_USO

// Só vale para quem saiu pelo botão "Abrir WhatsApp"; qualquer outra saída trava.
fun voltaSemPin(saiuPeloWhatsAppEm: Instant?, voltouEm: Instant): Boolean =
    saiuPeloWhatsAppEm != null && !voltouEm.isBefore(saiuPeloWhatsAppEm) &&
        Duration.between(saiuPeloWhatsAppEm, voltouEm) <= TOLERANCIA_WHATSAPP

fun marcaDeSaidaValida(marcadaEm: Instant?, saiuEm: Instant): Boolean =
    marcadaEm != null && !saiuEm.isBefore(marcadaEm) && Duration.between(marcadaEm, saiuEm) <= VALIDADE_MARCA_DE_SAIDA

// Nunca a última dona ativa, senão ninguém mais administra o app.
fun podeDesativar(alvo: Usuaria, usuarias: List<Usuaria>): Boolean =
    alvo.papel != Papel.DONA || usuarias.any { it.id != alvo.id && it.papel == Papel.DONA && it.ativa }
