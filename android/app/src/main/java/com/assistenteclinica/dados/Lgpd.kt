package com.assistenteclinica.dados

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import com.assistenteclinica.dominio.Acao
import com.assistenteclinica.dominio.Alvo
import com.assistenteclinica.dominio.DadosDaCrianca
import com.assistenteclinica.dominio.HorarioExportado
import com.assistenteclinica.dominio.PendenciaExportada
import com.assistenteclinica.dominio.ResponsavelExportado
import com.assistenteclinica.dominio.SessaoExportada
import com.assistenteclinica.dominio.TerapiaExportada

// Bloco 8, etapa 7: exportar e apagar a pedido (LGPD). Cada uma grava a sua linha no registro na mesma transação.
@Dao abstract class LgpdDao(db: RoomDatabase) : DaoComRegistro(db) {
    @Query("SELECT * FROM Crianca WHERE id = :id") protected abstract suspend fun crianca(id: Long): Crianca?

    @Query(
        """SELECT tp.nome AS terapia, t.nome AS terapeuta FROM CriancaTerapia ct
           JOIN Terapia tp ON tp.id = ct.terapiaId JOIN Terapeuta t ON t.id = ct.terapeutaId
           WHERE ct.criancaId = :id ORDER BY tp.nome COLLATE NOCASE""",
    )
    protected abstract suspend fun terapias(id: Long): List<TerapiaExportada>

    @Query(
        """SELECT r.nome, r.telefone, cr.principal, r.consentiuEm, r.versaoTermo FROM Responsavel r
           JOIN CriancaResponsavel cr ON cr.responsavelId = r.id WHERE cr.criancaId = :id ORDER BY r.nome COLLATE NOCASE""",
    )
    protected abstract suspend fun responsaveis(id: Long): List<ResponsavelExportado>

    @Query(
        """SELECT h.diaDaSemana AS dia, h.hora, tp.nome AS terapia, h.ativo FROM Horario h
           JOIN Terapia tp ON tp.id = h.terapiaId WHERE h.criancaId = :id ORDER BY h.diaDaSemana, h.hora""",
    )
    protected abstract suspend fun horarios(id: Long): List<HorarioExportado>

    @Query(
        """SELECT s.inicio, tp.nome AS terapia, t.nome AS terapeuta, s.tipo, s.desfecho FROM Sessao s
           JOIN Terapia tp ON tp.id = s.terapiaId JOIN Terapeuta t ON t.id = s.terapeutaId
           WHERE s.criancaId = :id ORDER BY s.inicio""",
    )
    protected abstract suspend fun sessoes(id: Long): List<SessaoExportada>

    // As pendências da criança e as das sessões dela (algumas só têm a sessão).
    @Query(
        """SELECT tipo, prazo, resolvidaEm, motivoAdiamento FROM Pendencia
           WHERE criancaId = :id OR sessaoId IN (SELECT id FROM Sessao WHERE criancaId = :id) ORDER BY prazo""",
    )
    protected abstract suspend fun pendencias(id: Long): List<PendenciaExportada>

    // null se a criança não existe mais. Fica registrado (a tela abre o compartilhar em seguida).
    @Transaction
    open suspend fun exportar(id: Long): DadosDaCrianca? {
        val c = crianca(id) ?: return null
        val dados = DadosDaCrianca(c.nome, c.tipo, c.observacao, c.ativa, terapias(id), responsaveis(id), horarios(id), sessoes(id), pendencias(id))
        registrar(Acao.EXPORTOU_OS_DADOS, Alvo.CRIANCA, id, id)
        return dados
    }

    @Query("SELECT responsavelId FROM CriancaResponsavel WHERE criancaId = :id") protected abstract suspend fun idsDosResponsaveis(id: Long): List<Long>
    @Query("DELETE FROM Pendencia WHERE criancaId = :id OR sessaoId IN (SELECT id FROM Sessao WHERE criancaId = :id)")
    protected abstract suspend fun apagarPendencias(id: Long)
    @Query("DELETE FROM Sessao WHERE criancaId = :id") protected abstract suspend fun apagarSessoes(id: Long): Int
    @Query("DELETE FROM Horario WHERE criancaId = :id") protected abstract suspend fun apagarHorarios(id: Long)
    // As ligações (terapias e responsáveis) saem junto, em cascata.
    @Query("DELETE FROM Crianca WHERE id = :id") protected abstract suspend fun apagarCrianca(id: Long): Int
    @Query("DELETE FROM Responsavel WHERE id = :id AND NOT EXISTS (SELECT 1 FROM CriancaResponsavel WHERE responsavelId = :id)")
    protected abstract suspend fun apagarSeSemCrianca(id: Long): Int

    // Apaga a criança, as sessões, os horários, as pendências e os responsáveis que não têm outra criança (a regra
    // do Bloco 3). O registro de ações fica (o banco não deixa apagar) e passa a mostrar "registro removido".
    // Devolve quantas sessões foram apagadas.
    @Transaction
    open suspend fun apagarAPedido(id: Long): Int {
        checkNotNull(crianca(id)) { "a criança não existe" }
        val responsaveis = idsDosResponsaveis(id)
        apagarPendencias(id)
        val sessoes = apagarSessoes(id)
        apagarHorarios(id)
        apagarCrianca(id)
        responsaveis.forEach { if (apagarSeSemCrianca(it) > 0) registrar(Acao.REMOVEU, Alvo.RESPONSAVEL, it, id) }
        registrar(Acao.APAGOU_A_PEDIDO, Alvo.CRIANCA, id, id, quantidade = sessoes)
        return sessoes
    }
}
