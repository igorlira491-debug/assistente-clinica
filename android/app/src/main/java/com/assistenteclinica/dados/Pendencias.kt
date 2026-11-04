package com.assistenteclinica.dados

import androidx.room.Dao
import androidx.room.Entity
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
import com.assistenteclinica.dominio.NovaPendencia
import com.assistenteclinica.dominio.Resposta
import com.assistenteclinica.dominio.Situacao
import com.assistenteclinica.dominio.TipoCliente
import com.assistenteclinica.dominio.TipoPendencia
import com.assistenteclinica.dominio.clinicaFechada
import com.assistenteclinica.dominio.detalheDaResposta
import com.assistenteclinica.dominio.detalheDoDesfecho
import com.assistenteclinica.dominio.indisponivel
import com.assistenteclinica.dominio.pendenciasAoEncerrar
import com.assistenteclinica.dominio.pendenciasAoResponder
import com.assistenteclinica.dominio.situacao
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// Só as pendências que nascem de um evento e pedem uma decisão (Remarcar, Definir próximo passo, Entrar em contato).
// As que dependem só da hora (confirmar, avisar terapeuta, como foi, lançar mês) são calculadas: pendenciasCalculadas.
@Entity(indices = [Index("resolvidaEm"), Index("sessaoId")])
data class Pendencia(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tipo: TipoPendencia,
    val criancaId: Long?,
    val sessaoId: Long?,
    val prazo: LocalDateTime,
    val criadaEm: LocalDateTime,
    val motivoAdiamento: String? = null,
    val resolvidaEm: LocalDateTime? = null,
    val resolvidaPor: Long? = null, // id da usuária
    val como: String? = null,
)

private val DATA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
const val RESPOSTA_CORRIGIDA = "Resposta corrigida"

// Toda ação entra no registro de ações na mesma transação (DaoComRegistro).
@Dao abstract class PendenciasDao(db: RoomDatabase) : DaoComRegistro(db) {
    @Query("SELECT * FROM Pendencia WHERE resolvidaEm IS NULL ORDER BY prazo") abstract suspend fun abertas(): List<Pendencia>

    // Base das pendências calculadas: sem desfecho e sem nenhum "não" (essas já viraram "Remarcar" e nunca voltam
    // à lista), até a data pedida. Sem o filtro do "não", a consulta cresceria com todo o histórico.
    @Query("SELECT * FROM Sessao WHERE desfecho IS NULL AND familia != 'NAO' AND terapeuta != 'NAO' AND inicio < :ate") abstract suspend fun sessoesEmAberto(ate: LocalDateTime): List<LinhaSessao>

    @Query("SELECT * FROM Sessao WHERE id = :id") protected abstract suspend fun sessao(id: Long): LinhaSessao?
    @Query("SELECT * FROM Sessao WHERE inicio >= :de AND inicio < :ate") protected abstract suspend fun sessoesEntre(de: LocalDateTime, ate: LocalDateTime): List<LinhaSessao>
    @Update protected abstract suspend fun atualizar(s: LinhaSessao)
    @Insert protected abstract suspend fun inserir(s: LinhaSessao): Long
    @Query("SELECT tipo FROM Crianca WHERE id = :id") protected abstract suspend fun tipoDaCrianca(id: Long): TipoCliente

    @Query("SELECT * FROM Pendencia WHERE id = :id") protected abstract suspend fun pendencia(id: Long): Pendencia?
    @Insert protected abstract suspend fun inserir(p: Pendencia): Long
    @Update protected abstract suspend fun atualizar(p: Pendencia)
    // Um "Remarcar" fechado como "Resposta corrigida" não conta: era engano, e um "não" de verdade depois precisa do seu.
    @Query("SELECT COUNT(*) > 0 FROM Pendencia WHERE sessaoId = :sessaoId AND tipo = 'REMARCAR' AND (como IS NULL OR como != '$RESPOSTA_CORRIGIDA')")
    protected abstract suspend fun jaTemRemarcar(sessaoId: Long): Boolean
    @Query("SELECT COUNT(*) > 0 FROM Pendencia WHERE sessaoId = :sessaoId AND tipo = 'REMARCAR' AND resolvidaEm IS NULL")
    abstract suspend fun temRemarcarAberto(sessaoId: Long): Boolean
    @Query("SELECT * FROM Pendencia WHERE sessaoId = :sessaoId AND tipo = 'REMARCAR' AND resolvidaEm IS NULL")
    protected abstract suspend fun remarcarAberto(sessaoId: Long): List<Pendencia>

    // Uma sessão tem no máximo um "Remarcar" na vida: um "não" seguido de "Faltou" não duplica.
    private suspend fun gravar(novas: List<NovaPendencia>, agora: LocalDateTime) {
        novas.filter { it.tipo != TipoPendencia.REMARCAR || it.sessaoId == null || !jaTemRemarcar(it.sessaoId) }
            .forEach { inserir(Pendencia(tipo = it.tipo, criancaId = it.criancaId, sessaoId = it.sessaoId, prazo = it.prazo, criadaEm = agora)) }
    }

    @Transaction
    open suspend fun responder(sessaoId: Long, daFamilia: Boolean, sim: Boolean, agora: LocalDateTime, usuariaId: Long) {
        val antes = sessao(sessaoId)?.paraDominio() ?: return
        val r = if (sim) Resposta.SIM else Resposta.NAO
        val depois = if (daFamilia) antes.copy(familia = r, familiaConfirmouEm = if (sim) agora else null) else antes.copy(terapeuta = r)
        atualizar(depois.paraLinha())
        registrar(Acao.MARCOU_RESPOSTA, Alvo.SESSAO, sessaoId, antes.criancaId, detalheDaResposta(daFamilia, sim))
        gravar(pendenciasAoResponder(antes, depois, agora), agora)
        // Tocou "não" por engano e corrigiu: o "Remarcar" que nasceu do "não" deixa de fazer sentido.
        if (situacao(depois) != Situacao.NAO_VAI_ACONTECER) remarcarAberto(sessaoId).forEach {
            atualizar(it.copy(resolvidaEm = agora, resolvidaPor = usuariaId, como = RESPOSTA_CORRIGIDA))
        }
    }

    @Transaction
    open suspend fun encerrar(sessaoId: Long, desfecho: Desfecho, agora: LocalDateTime) {
        val antes = sessao(sessaoId)?.paraDominio() ?: return
        if (antes.desfecho != null) return // já registrado: dois toques em "Realizada" não criam duas pendências
        val s = antes.copy(desfecho = desfecho)
        atualizar(s.paraLinha())
        registrar(Acao.REGISTROU_COMO_FOI, Alvo.SESSAO, sessaoId, s.criancaId, detalheDoDesfecho(desfecho))
        gravar(pendenciasAoEncerrar(s, desfecho, tipoDaCrianca(s.criancaId), agora), agora)
    }

    // O "como" (que pode ter o motivo digitado) fica só na pendência; o registro guarda que foi resolvida.
    @Transaction
    open suspend fun resolver(pendenciaId: Long, como: String, agora: LocalDateTime, usuariaId: Long) {
        val p = fechar(pendenciaId, como, agora, usuariaId) ?: return
        registrar(Acao.RESOLVEU, Alvo.PENDENCIA, pendenciaId, p.criancaId)
    }

    // Fecha sem registrar: quem chama registra a própria ação (nova data, lembrar depois). Uma ação, uma linha.
    private suspend fun fechar(pendenciaId: Long, como: String, agora: LocalDateTime, usuariaId: Long): Pendencia? {
        val p = pendencia(pendenciaId) ?: return null
        atualizar(p.copy(resolvidaEm = agora, resolvidaPor = usuariaId, como = como))
        return p
    }

    @Transaction
    open suspend fun marcarNovaData(pendenciaId: Long, nova: LinhaSessao, agora: LocalDateTime, usuariaId: Long): Long {
        val id = inserir(nova)
        registrar(Acao.CRIOU, Alvo.SESSAO, id, nova.criancaId, Detalhe.NOVA_DATA)
        fechar(pendenciaId, "Nova sessão em ${nova.inicio.format(DATA_HORA)}", agora, usuariaId)
        return id
    }

    @Transaction
    open suspend fun lembrarDepois(pendenciaId: Long, dias: Int, agora: LocalDateTime, usuariaId: Long) {
        val p = pendencia(pendenciaId) ?: return
        fechar(pendenciaId, "Lembrar em $dias dias", agora, usuariaId)
        registrar(Acao.LEMBRAR_DEPOIS, Alvo.PENDENCIA, pendenciaId, p.criancaId, quantidade = dias)
        inserir(Pendencia(tipo = TipoPendencia.ENTRAR_EM_CONTATO, criancaId = p.criancaId, sessaoId = p.sessaoId, prazo = agora.plusDays(dias.toLong()), criadaEm = agora))
    }

    // O motivo digitado fica só na pendência; o registro guarda que foi adiada.
    @Transaction
    open suspend fun adiar(pendenciaId: Long, novoPrazo: LocalDateTime, motivo: String) {
        val p = pendencia(pendenciaId) ?: return
        atualizar(p.copy(prazo = novoPrazo, motivoAdiamento = motivo))
        registrar(Acao.ADIOU, Alvo.PENDENCIA, pendenciaId, p.criancaId)
    }

    // ---- Backup (Bloco 8, etapa 4) ----

    @Query("SELECT * FROM Pendencia WHERE tipo = 'SALVAR_BACKUP' AND resolvidaEm IS NULL")
    protected abstract suspend fun salvarBackupAberta(): List<Pendencia>

    // Backup do dia pronto: abre a pendência "Salvar backup" (uma só; a de ontem, se ficou aberta, continua e salva o
    // de hoje, que substituiu o arquivo) e registra o preparo. A guardada entra no resumo das 8h como as outras.
    @Transaction
    open suspend fun backupPreparado(prazo: LocalDateTime, agora: LocalDateTime) {
        if (salvarBackupAberta().isEmpty()) inserir(Pendencia(tipo = TipoPendencia.SALVAR_BACKUP, criancaId = null, sessaoId = null, prazo = prazo, criadaEm = agora))
        registrar(Acao.PREPAROU_O_BACKUP)
    }

    // Ela escolheu um app na tela de compartilhar: a pendência sai e o destino fica no registro.
    @Transaction
    open suspend fun backupSalvo(destino: Detalhe, agora: LocalDateTime, usuariaId: Long) {
        salvarBackupAberta().forEach { atualizar(it.copy(resolvidaEm = agora, resolvidaPor = usuariaId, como = "Backup salvo")) }
        registrar(Acao.SALVOU_O_BACKUP, detalhe = destino)
    }

    private suspend fun doDia(dia: LocalDate) = sessoesEntre(dia.atStartOfDay(), dia.plusDays(1).atStartOfDay()).map { it.paraDominio() }

    @Transaction
    open suspend fun marcarIndisponivel(terapeutaId: Long, dia: LocalDate, agora: LocalDateTime): Int {
        val antes = doDia(dia).associateBy { it.id }
        val afetadas = indisponivel(antes.values.toList(), terapeutaId, dia, agora)
        afetadas.forEach { atualizar(it.paraLinha()); gravar(pendenciasAoResponder(antes.getValue(it.id), it, agora), agora) }
        registrar(Acao.MARCOU_TERAPEUTA_INDISPONIVEL, Alvo.TERAPEUTA, terapeutaId, quantidade = afetadas.size)
        return afetadas.size
    }

    @Transaction
    open suspend fun fecharClinica(dia: LocalDate, agora: LocalDateTime): Int {
        val afetadas = clinicaFechada(doDia(dia), dia, agora)
        afetadas.forEach {
            atualizar(it.paraLinha())
            gravar(pendenciasAoEncerrar(it, Desfecho.CANCELADA_PELA_CLINICA, tipoDaCrianca(it.criancaId), agora), agora)
        }
        registrar(Acao.FECHOU_A_CLINICA, quantidade = afetadas.size)
        return afetadas.size
    }
}
