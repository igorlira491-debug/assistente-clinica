package com.assistenteclinica.dominio

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

enum class TipoCliente { FIXO, AVULSO }
enum class Resposta { AGUARDANDO, SIM, NAO }
enum class Origem { FIXA, AVULSA, REMARCACAO }
enum class Desfecho { REALIZADA, FALTOU, NAO_ACONTECEU, CANCELADA_PELA_CLINICA }
enum class Situacao { AGUARDANDO, CONFIRMADA, NAO_VAI_ACONTECER, ENCERRADA }
enum class TipoSessao { COMUM, ANAMNESE, DEVOLUTIVA }

// A duração é copiada da terapia quando a sessão é criada: mudar a terapia depois não altera sessões antigas.
data class Sessao(
    val id: Long,
    val criancaId: Long,
    val terapiaId: Long,
    val terapeutaId: Long,
    val inicio: LocalDateTime,
    val duracaoMin: Int,
    val familia: Resposta = Resposta.AGUARDANDO,
    val terapeuta: Resposta = Resposta.AGUARDANDO,
    val origem: Origem,
    val tipo: TipoSessao = TipoSessao.COMUM,
    val desfecho: Desfecho? = null,
    val familiaConfirmouEm: LocalDateTime? = null,
    // Lançada depois do horário (encaixe que ficou sem registro): quando e por quem. O histórico completo é do Bloco 8.
    val lancadaDepoisEm: LocalDateTime? = null,
    val lancadaPor: Long? = null,
) {
    val fim: LocalDateTime get() = inicio.plusMinutes(duracaoMin.toLong())
}

data class HorarioFixo(
    val id: Long,
    val criancaId: Long,
    val terapiaId: Long,
    val terapeutaId: Long,
    val diaDaSemana: DayOfWeek,
    val hora: LocalTime,
    val duracaoMin: Int,
    val ativo: Boolean = true,
)
