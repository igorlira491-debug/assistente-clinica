package com.assistenteclinica.dominio

enum class TipoConflito { MESMA_TERAPEUTA, MESMA_CRIANCA }

data class Conflito(val tipo: TipoConflito, val com: Sessao)

private val NAO_OCUPA_HORARIO = setOf(Situacao.NAO_VAI_ACONTECER, Situacao.ENCERRADA)

// Conflitos avisam, não bloqueiam. Sessões encostadas (uma termina quando a outra começa) não conflitam.
fun conflitos(nova: Sessao, existentes: List<Sessao>): List<Conflito> =
    existentes
        .filter { nova.id == 0L || it.id != nova.id } // ao editar, a sessão não conflita consigo mesma
        .filter { situacao(it) !in NAO_OCUPA_HORARIO }
        .filter { nova.inicio < it.fim && it.inicio < nova.fim }
        .mapNotNull {
            when {
                // Mesma criança e mesma terapeuta: basta o aviso da criança.
                it.criancaId == nova.criancaId -> Conflito(TipoConflito.MESMA_CRIANCA, it)
                it.terapeutaId == nova.terapeutaId -> Conflito(TipoConflito.MESMA_TERAPEUTA, it)
                else -> null
            }
        }
