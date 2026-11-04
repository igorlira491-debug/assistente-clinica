package com.assistenteclinica.dominio

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

// Bloco 8, etapa 4: regras do backup diário (plano 2026-10-29, parte 1, "Quando" e "Onde fica").

// Só a data no nome: nenhum nome de pessoa, código de conferência ou número sai em claro (decidido pelo usuário).
fun nomeDoArquivoDeBackup(dia: LocalDate): String = "assistente-backup-$dia.assistente"

const val EXTENSAO_DO_BACKUP = ".assistente"

// A pendência "Salvar backup" vence às 18h do dia em que o backup foi preparado. Preparado depois disso (primeira
// entrada às 19h30), vence 1 h depois, para não nascer atrasada.
private val HORA_SALVAR_BACKUP = LocalTime.of(18, 0)
fun prazoSalvarBackup(preparadoEm: LocalDateTime): LocalDateTime =
    maxOf(preparadoEm.toLocalDate().atTime(HORA_SALVAR_BACKUP), preparadoEm.plusHours(1))

// Na primeira vez do dia que o app é destravado (decidido pelo usuário, desenho §7).
fun precisaPrepararBackup(ultimoPreparo: LocalDate?, hoje: LocalDate): Boolean = ultimoPreparo != hoje

// O Android informa só o app escolhido na tela de compartilhar, não se o envio terminou.
private const val PACOTE_DO_DRIVE = "com.google.android.apps.docs"
fun detalheDoDestino(pacote: String?): Detalhe = if (pacote == PACOTE_DO_DRIVE) Detalhe.DRIVE else Detalhe.OUTRO_APP

// ---- Restauração (Bloco 8, etapa 5) ----

// Quem pode criar o PIN novo depois de restaurar: só uma dona ativa (a senha de backup provou que é ela).
fun donasParaRestaurar(usuarias: List<Usuaria>): List<Usuaria> = usuarias.filter { it.papel == Papel.DONA && it.ativa }

// O registro não aceita texto livre: a data do backup restaurado vai como número (2026-10-29 vira 20261029).
fun dataComoNumero(d: LocalDate): Int = d.year * 10_000 + d.monthValue * 100 + d.dayOfMonth

fun dataDoNumero(n: Int): LocalDate? = runCatching { LocalDate.of(n / 10_000, n / 100 % 100, n % 100) }.getOrNull()

// Um arquivo maior que isso nem é lido (alguém escolheu um vídeo por engano) nem é gravado (o backup falha à vista em
// vez de gerar um arquivo que não abre): o banco da clínica fica bem abaixo.
const val LIMITE_DO_ARQUIVO_DE_BACKUP = 200L * 1024 * 1024
