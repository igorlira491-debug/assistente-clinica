package com.assistenteclinica.dados

import com.assistenteclinica.dominio.Conferencia
import java.time.LocalDate
import java.time.LocalDateTime

// Bloco 8, etapa 4: o que o app lembra do último backup. Fica no banco cifrado (só aparece com o app destravado);
// o código de conferência nunca sai em claro do celular.
private const val PREPARADO_EM = "backup_preparado_em"
private const val CODIGO = "backup_codigo"
private const val QUANTIDADES = "backup_quantidades"
private const val SALVO_EM = "backup_salvo_em"

class UltimoBackup(val preparadoEm: LocalDateTime?, val conferencia: Conferencia?, val salvoEm: LocalDateTime?)

suspend fun ConfiguracaoDao.ultimoBackup(): UltimoBackup {
    val preparado = valor(PREPARADO_EM)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
    val codigo = valor(CODIGO)
    val quantidades = valor(QUANTIDADES)?.split(";")?.filter { it.isNotBlank() }?.associate {
        val (t, n) = it.split("="); t to n.toInt()
    }
    val salvo = valor(SALVO_EM)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
    return UltimoBackup(preparado, if (codigo != null && quantidades != null) Conferencia(quantidades, codigo) else null, salvo)
}

fun UltimoBackup.preparadoNoDia(): LocalDate? = preparadoEm?.toLocalDate()

suspend fun ConfiguracaoDao.marcarBackupPreparado(em: LocalDateTime, c: Conferencia) {
    salvar(Configuracao(PREPARADO_EM, em.toString()))
    salvar(Configuracao(CODIGO, c.codigo))
    salvar(Configuracao(QUANTIDADES, c.quantidades.entries.joinToString(";") { "${it.key}=${it.value}" }))
}

suspend fun ConfiguracaoDao.marcarBackupSalvo(em: LocalDateTime) = salvar(Configuracao(SALVO_EM, em.toString()))
