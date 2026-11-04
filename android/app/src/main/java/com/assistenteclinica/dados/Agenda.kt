package com.assistenteclinica.dados

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import com.assistenteclinica.dominio.Acao
import com.assistenteclinica.dominio.Alvo
import com.assistenteclinica.dominio.Desfecho
import com.assistenteclinica.dominio.Detalhe
import com.assistenteclinica.dominio.HorarioFixo
import com.assistenteclinica.dominio.Origem
import com.assistenteclinica.dominio.QuemPediu
import com.assistenteclinica.dominio.Resposta
import com.assistenteclinica.dominio.Sessao
import com.assistenteclinica.dominio.TipoSessao
import com.assistenteclinica.dominio.detalheDeQuemPediu
import com.assistenteclinica.dominio.sessoesDoHorarioParaTirar
import com.assistenteclinica.dominio.sessoesFixasDoMes
import com.assistenteclinica.dominio.sessoesMovidas
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth

// Mesmos campos da Sessao do domínio, que não pode depender do Room.
@Entity(
    tableName = "Sessao",
    indices = [Index("inicio"), Index("criancaId"), Index("terapiaId"), Index("terapeutaId")],
    foreignKeys = [
        ForeignKey(Crianca::class, ["id"], ["criancaId"]),
        ForeignKey(Terapia::class, ["id"], ["terapiaId"]),
        ForeignKey(Terapeuta::class, ["id"], ["terapeutaId"]),
    ],
)
data class LinhaSessao(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val criancaId: Long,
    val terapiaId: Long,
    val terapeutaId: Long,
    val inicio: LocalDateTime,
    val duracaoMin: Int,
    val familia: Resposta,
    val terapeuta: Resposta,
    val origem: Origem,
    val tipo: TipoSessao,
    val desfecho: Desfecho?,
    val familiaConfirmouEm: LocalDateTime? = null,
    val lancadaDepoisEm: LocalDateTime? = null,
    val lancadaPor: Long? = null,
)

fun LinhaSessao.paraDominio() = Sessao(
    id, criancaId, terapiaId, terapeutaId, inicio, duracaoMin, familia, terapeuta, origem, tipo, desfecho, familiaConfirmouEm, lancadaDepoisEm, lancadaPor,
)
fun Sessao.paraLinha() = LinhaSessao(
    id, criancaId, terapiaId, terapeutaId, inicio, duracaoMin, familia, terapeuta, origem, tipo, desfecho, familiaConfirmouEm, lancadaDepoisEm, lancadaPor,
)

// Sem terapeuta nem duração: no lançamento elas vêm da CriancaTerapia e da Terapia daquele momento.
@Entity(
    indices = [Index("criancaId"), Index("terapiaId")],
    foreignKeys = [
        ForeignKey(Crianca::class, ["id"], ["criancaId"]),
        ForeignKey(Terapia::class, ["id"], ["terapiaId"]),
    ],
)
data class Horario(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val criancaId: Long,
    val terapiaId: Long,
    val diaDaSemana: DayOfWeek,
    val hora: LocalTime,
    val ativo: Boolean = true,
)

data class SessaoNaAgenda(@Embedded val sessao: LinhaSessao, val crianca: String, val terapia: String, val nomeTerapeuta: String)

// semTerapeuta: a criança não faz mais a terapia, ou a terapeuta dela está desativada → o horário não lança.
data class HorarioNaFicha(@Embedded val horario: Horario, val terapia: String, val semTerapeuta: Boolean)

// Toda gravação entra no registro de ações na mesma transação (DaoComRegistro).
@Dao abstract class AgendaDao(db: RoomDatabase) : DaoComRegistro(db) {
    @Query(
        """SELECT s.*, c.nome AS crianca, t.nome AS terapia, p.nome AS nomeTerapeuta FROM Sessao s
           JOIN Crianca c ON c.id = s.criancaId JOIN Terapia t ON t.id = s.terapiaId JOIN Terapeuta p ON p.id = s.terapeutaId
           WHERE s.inicio >= :de AND s.inicio < :ate ORDER BY s.inicio, c.nome COLLATE NOCASE""",
    )
    abstract suspend fun sessoesEntre(de: LocalDateTime, ate: LocalDateTime): List<SessaoNaAgenda>

    @Query("SELECT * FROM Sessao WHERE inicio >= :de AND inicio < :ate")
    abstract suspend fun sessoesDominioEntre(de: LocalDateTime, ate: LocalDateTime): List<LinhaSessao>

    @Query(
        """SELECT s.*, c.nome AS crianca, t.nome AS terapia, p.nome AS nomeTerapeuta FROM Sessao s
           JOIN Crianca c ON c.id = s.criancaId JOIN Terapia t ON t.id = s.terapiaId JOIN Terapeuta p ON p.id = s.terapeutaId
           WHERE s.id = :id""",
    )
    abstract suspend fun sessao(id: Long): SessaoNaAgenda?

    @Insert protected abstract suspend fun inserir(s: LinhaSessao): Long
    @Update protected abstract suspend fun atualizar(s: LinhaSessao)
    @Query("DELETE FROM Sessao WHERE id = :id") protected abstract suspend fun apagar(id: Long)
    @Query("SELECT criancaId FROM Sessao WHERE id = :id") protected abstract suspend fun criancaDaSessao(id: Long): Long?
    @Insert protected abstract suspend fun inserirSessoes(sessoes: List<LinhaSessao>)

    @Transaction
    open suspend fun salvarSessao(s: LinhaSessao): Long {
        val nova = s.id == 0L
        val id = if (nova) inserir(s) else s.id.also { atualizar(s) }
        val detalhe = if (nova && s.lancadaDepoisEm != null) Detalhe.LANCADA_DEPOIS_DO_DIA else null
        registrar(if (nova) Acao.CRIOU else Acao.ALTEROU, Alvo.SESSAO, id, s.criancaId, detalhe)
        return id
    }

    @Transaction
    open suspend fun apagarSessao(id: Long) {
        val crianca = criancaDaSessao(id) ?: return
        apagar(id)
        registrar(Acao.REMOVEU, Alvo.SESSAO, id, crianca)
    }

    @Transaction
    open suspend fun lancar(sessoes: List<LinhaSessao>) {
        inserirSessoes(sessoes)
        registrar(Acao.LANCOU_O_MES, quantidade = sessoes.size)
    }

    // quem: quem pediu (a tela sempre sabe); fica no registro junto da sessão antiga.
    @Transaction
    open suspend fun remarcarSessao(original: LinhaSessao, nova: LinhaSessao, quem: QuemPediu): Long {
        atualizar(original)
        val id = inserir(nova)
        registrar(Acao.REMARCOU, Alvo.SESSAO, original.id, original.criancaId, detalheDeQuemPediu(quem))
        return id
    }

    @Query(
        """SELECT h.*, t.nome AS terapia, NOT EXISTS (
               SELECT 1 FROM CriancaTerapia ct JOIN Terapeuta p ON p.id = ct.terapeutaId
               WHERE ct.criancaId = h.criancaId AND ct.terapiaId = h.terapiaId AND p.ativa = 1) AS semTerapeuta
           FROM Horario h JOIN Terapia t ON t.id = h.terapiaId
           WHERE h.criancaId = :criancaId AND h.ativo = 1 ORDER BY h.diaDaSemana, h.hora""",
    )
    abstract suspend fun horariosDaCrianca(criancaId: Long): List<HorarioNaFicha>

    @Query(
        """SELECT h.id, h.criancaId, h.terapiaId, ct.terapeutaId, h.diaDaSemana, h.hora, t.duracaoMin, h.ativo
           FROM Horario h
           JOIN Crianca c ON c.id = h.criancaId AND c.ativa = 1 AND c.tipo = 'FIXO'
           JOIN Terapia t ON t.id = h.terapiaId AND t.ativa = 1
           JOIN CriancaTerapia ct ON ct.criancaId = h.criancaId AND ct.terapiaId = h.terapiaId
           JOIN Terapeuta p ON p.id = ct.terapeutaId AND p.ativa = 1
           WHERE h.ativo = 1""",
    )
    abstract suspend fun horariosParaLancar(): List<HorarioFixo>

    @Query("SELECT * FROM Horario WHERE id = :id") protected abstract suspend fun horario(id: Long): Horario?
    @Insert protected abstract suspend fun inserir(h: Horario): Long
    @Update protected abstract suspend fun atualizar(h: Horario)

    @Transaction
    open suspend fun salvarHorario(h: Horario): Long {
        val id = if (h.id == 0L) inserir(h) else h.id.also { atualizar(h) }
        registrar(if (h.id == 0L) Acao.CRIOU else Acao.ALTEROU, Alvo.HORARIO, id, h.criancaId)
        return id
    }

    // Prévia do lançamento: só as sessões que ainda não existem (sessoesFixasDoMes não recria nada).
    open suspend fun previaDoMes(mes: YearMonth, hoje: LocalDate): List<Sessao> =
        sessoesFixasDoMes(horariosParaLancar(), doMes(mes), mes, hoje)

    @Query("SELECT * FROM Sessao WHERE criancaId = :criancaId AND terapiaId = :terapiaId AND inicio > :agora")
    protected abstract suspend fun futurasDa(criancaId: Long, terapiaId: Long, agora: LocalDateTime): List<LinhaSessao>

    private suspend fun doMes(mes: YearMonth) =
        sessoesDominioEntre(mes.atDay(1).atStartOfDay(), mes.plusMonths(1).atDay(1).atStartOfDay()).map { it.paraDominio() }

    private suspend fun paraTirar(h: Horario, agora: LocalDateTime): List<Sessao> =
        sessoesDoHorarioParaTirar(
            HorarioFixo(h.id, h.criancaId, h.terapiaId, 0, h.diaDaSemana, h.hora, 0), // terapeuta e duração não contam aqui
            futurasDa(h.criancaId, h.terapiaId, agora).map { it.paraDominio() },
            agora,
        )

    open suspend fun contarParaTirar(id: Long, agora: LocalDateTime): Int = horario(id)?.let { paraTirar(it, agora).size } ?: 0

    @Transaction
    open suspend fun desativarHorario(id: Long, agora: LocalDateTime): Int {
        val h = horario(id) ?: return 0
        val tirar = paraTirar(h, agora)
        tirar.forEach { apagar(it.id) }
        atualizar(h.copy(ativo = false))
        registrar(Acao.DESATIVOU, Alvo.HORARIO, id, h.criancaId, quantidade = tirar.size)
        return tirar.size
    }

    // As que saíram voltam no novo dia e hora, na mesma semana (sessoesMovidas). Sem terapeuta ativa não há como
    // recriá-las: aí nada muda e devolve -1 (a tela já impede editar nesse caso).
    // shortcut: lançar de novo um mês já lançado depois de editar recria o dia novo nas semanas que ficaram com a
    // sessão antiga respondida (a prévia mostra antes de salvar); resolver guardando o horário de origem na sessão se incomodar.
    @Transaction
    open suspend fun editarHorario(h: Horario, agora: LocalDateTime): Int {
        val antigo = horario(h.id) ?: return 0
        val fixo = horariosParaLancar().firstOrNull { it.id == h.id } ?: return -1
        val tirar = paraTirar(antigo, agora)
        tirar.forEach { apagar(it.id) }
        atualizar(h)
        val existentes = futurasDa(h.criancaId, h.terapiaId, agora).map { it.paraDominio() }
        inserirSessoes(sessoesMovidas(tirar, fixo.copy(diaDaSemana = h.diaDaSemana, hora = h.hora), existentes, agora).map { it.paraLinha() })
        registrar(Acao.ALTEROU, Alvo.HORARIO, h.id, h.criancaId, quantidade = tirar.size)
        return tirar.size
    }
}
