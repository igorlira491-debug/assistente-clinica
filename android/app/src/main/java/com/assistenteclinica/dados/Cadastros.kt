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
import androidx.room.TypeConverter
import androidx.room.Update
import androidx.room.Upsert
import com.assistenteclinica.dominio.Acao
import com.assistenteclinica.dominio.Alvo
import com.assistenteclinica.dominio.TipoCliente
import com.assistenteclinica.dominio.acaoAoSalvar
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

// Só o que o spec §5.1 lista. Nada se apaga aqui: crianças, terapeutas e terapias ficam inativas (§6.3).

@Entity data class Terapia(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nome: String,
    val duracaoMin: Int,
    val ativa: Boolean = true,
)

@Entity(indices = [Index(value = ["telefone"], unique = true)])
data class Terapeuta(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nome: String,
    val telefone: String,
    val ativa: Boolean = true,
)

@Entity(
    primaryKeys = ["terapeutaId", "terapiaId"],
    indices = [Index("terapiaId")],
    foreignKeys = [
        ForeignKey(Terapeuta::class, ["id"], ["terapeutaId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Terapia::class, ["id"], ["terapiaId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class TerapeutaTerapia(val terapeutaId: Long, val terapiaId: Long)

@Entity data class Crianca(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nome: String,
    val tipo: TipoCliente,
    val observacao: String = "",
    val ativa: Boolean = true,
)

// Uma terapeuta por terapia para cada criança: a chave (criança, terapia) garante isso.
@Entity(
    primaryKeys = ["criancaId", "terapiaId"],
    indices = [Index("terapiaId"), Index("terapeutaId")],
    foreignKeys = [
        ForeignKey(Crianca::class, ["id"], ["criancaId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Terapia::class, ["id"], ["terapiaId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Terapeuta::class, ["id"], ["terapeutaId"]),
    ],
)
data class CriancaTerapia(val criancaId: Long, val terapiaId: Long, val terapeutaId: Long)

// O telefone é a identidade do responsável: irmãos apontam para a mesma linha.
@Entity(indices = [Index(value = ["telefone"], unique = true)])
data class Responsavel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nome: String,
    val telefone: String,
    val consentiuEm: LocalDate? = null,
    val versaoTermo: String? = null,
)

@Entity(
    primaryKeys = ["criancaId", "responsavelId"],
    indices = [Index("responsavelId")],
    foreignKeys = [
        ForeignKey(Crianca::class, ["id"], ["criancaId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Responsavel::class, ["id"], ["responsavelId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class CriancaResponsavel(val criancaId: Long, val responsavelId: Long, val principal: Boolean)

// criancas: de quantas crianças a pessoa é responsável (1 = remover apaga os dados dela).
data class ResponsavelDaCrianca(@Embedded val responsavel: Responsavel, val principal: Boolean, val criancas: Int)

class Conversores {
    @TypeConverter fun paraLong(d: LocalDate?): Long? = d?.toEpochDay()
    @TypeConverter fun paraData(n: Long?): LocalDate? = n?.let(LocalDate::ofEpochDay)

    // Data e hora locais (sem fuso) como segundos: ordena e compara direto no SQL.
    @TypeConverter fun deDataHora(d: LocalDateTime?): Long? = d?.toEpochSecond(ZoneOffset.UTC)
    @TypeConverter fun paraDataHora(n: Long?): LocalDateTime? = n?.let { LocalDateTime.ofEpochSecond(it, 0, ZoneOffset.UTC) }
    @TypeConverter fun deHora(h: LocalTime?): Int? = h?.let { it.toSecondOfDay() / 60 }
    @TypeConverter fun paraHora(n: Int?): LocalTime? = n?.let { LocalTime.ofSecondOfDay(it * 60L) }
    // Número de 1 (segunda) a 7 (domingo), para ordenar os horários pela semana.
    @TypeConverter fun deDia(d: DayOfWeek?): Int? = d?.value
    @TypeConverter fun paraDia(n: Int?): DayOfWeek? = n?.let(DayOfWeek::of)
}

// salvarX: id 0 insere e devolve o id novo; senão atualiza e devolve o mesmo id (o @Upsert devolveria -1).
// Toda gravação entra no registro de ações na mesma transação (DaoComRegistro).
@Dao abstract class CadastrosDao(db: RoomDatabase) : DaoComRegistro(db) {
    @Query("SELECT * FROM Terapia ORDER BY nome COLLATE NOCASE") abstract suspend fun terapias(): List<Terapia>
    @Insert protected abstract suspend fun inserir(t: Terapia): Long
    @Update protected abstract suspend fun atualizar(t: Terapia)
    @Query("SELECT ativa FROM Terapia WHERE id = :id") protected abstract suspend fun terapiaAtiva(id: Long): Boolean?

    @Transaction
    open suspend fun salvarTerapia(t: Terapia): Long {
        val acao = acaoAoSalvar(t.id == 0L, if (t.id == 0L) null else terapiaAtiva(t.id), t.ativa)
        val id = if (t.id == 0L) inserir(t) else t.id.also { atualizar(t) }
        registrar(acao, Alvo.TERAPIA, id)
        return id
    }

    @Query("SELECT * FROM Terapeuta ORDER BY nome COLLATE NOCASE") abstract suspend fun terapeutas(): List<Terapeuta>
    @Query("SELECT terapiaId FROM TerapeutaTerapia WHERE terapeutaId = :terapeutaId")
    abstract suspend fun terapiasDaTerapeuta(terapeutaId: Long): List<Long>
    @Insert protected abstract suspend fun inserir(t: Terapeuta): Long
    @Update protected abstract suspend fun atualizar(t: Terapeuta)
    @Insert protected abstract suspend fun inserir(v: List<TerapeutaTerapia>)
    @Query("DELETE FROM TerapeutaTerapia WHERE terapeutaId = :terapeutaId")
    protected abstract suspend fun limparTerapias(terapeutaId: Long)
    @Query("SELECT ativa FROM Terapeuta WHERE id = :id") protected abstract suspend fun terapeutaAtiva(id: Long): Boolean?

    @Transaction
    open suspend fun salvarTerapeuta(t: Terapeuta, terapiaIds: Set<Long>): Long {
        val acao = acaoAoSalvar(t.id == 0L, if (t.id == 0L) null else terapeutaAtiva(t.id), t.ativa)
        val id = if (t.id == 0L) inserir(t) else t.id.also { atualizar(t) }
        limparTerapias(id)
        inserir(terapiaIds.map { TerapeutaTerapia(id, it) })
        registrar(acao, Alvo.TERAPEUTA, id)
        return id
    }

    @Query(
        """SELECT t.* FROM Terapeuta t JOIN TerapeutaTerapia tt ON tt.terapeutaId = t.id
           WHERE tt.terapiaId = :terapiaId AND t.ativa = 1 ORDER BY t.nome COLLATE NOCASE""",
    )
    abstract suspend fun terapeutasQueAtendem(terapiaId: Long): List<Terapeuta>

    @Query(
        """SELECT COUNT(DISTINCT c.id) FROM CriancaTerapia ct JOIN Crianca c ON c.id = ct.criancaId
           WHERE ct.terapeutaId = :terapeutaId AND c.ativa = 1""",
    )
    abstract suspend fun criancasAtendidasPor(terapeutaId: Long): Int

    @Query("SELECT * FROM Crianca ORDER BY nome COLLATE NOCASE") abstract suspend fun criancas(): List<Crianca>
    @Query("SELECT * FROM Crianca WHERE id = :id") abstract suspend fun crianca(id: Long): Crianca?

    // Crianças com alguma terapia cuja terapeuta foi desativada ou deixou de atender aquela terapia.
    @Query(
        """SELECT DISTINCT ct.criancaId FROM CriancaTerapia ct JOIN Terapeuta t ON t.id = ct.terapeutaId
           WHERE t.ativa = 0 OR NOT EXISTS (
               SELECT 1 FROM TerapeutaTerapia tt WHERE tt.terapeutaId = ct.terapeutaId AND tt.terapiaId = ct.terapiaId)""",
    )
    abstract suspend fun criancasParaRevisarTerapeuta(): List<Long>
    @Insert protected abstract suspend fun inserir(c: Crianca): Long
    @Update protected abstract suspend fun atualizar(c: Crianca)

    @Transaction
    open suspend fun salvarCrianca(c: Crianca): Long {
        val acao = acaoAoSalvar(c.id == 0L, if (c.id == 0L) null else crianca(c.id)?.ativa, c.ativa)
        val id = if (c.id == 0L) inserir(c) else c.id.also { atualizar(c) }
        registrar(acao, Alvo.CRIANCA, id, criancaId = id)
        return id
    }

    @Query("SELECT * FROM CriancaTerapia WHERE criancaId = :criancaId")
    abstract suspend fun terapiasDaCrianca(criancaId: Long): List<CriancaTerapia>
    @Upsert protected abstract suspend fun gravarTerapia(v: CriancaTerapia)
    @Query("DELETE FROM CriancaTerapia WHERE criancaId = :criancaId AND terapiaId = :terapiaId")
    protected abstract suspend fun apagarTerapia(criancaId: Long, terapiaId: Long)

    // Terapia e terapeuta da criança fazem parte da ficha dela: entram como "alterou a criança".
    @Transaction
    open suspend fun definirTerapia(v: CriancaTerapia) {
        gravarTerapia(v)
        registrar(Acao.ALTEROU, Alvo.CRIANCA, v.criancaId, criancaId = v.criancaId)
    }

    @Transaction
    open suspend fun removerTerapia(criancaId: Long, terapiaId: Long) {
        apagarTerapia(criancaId, terapiaId)
        registrar(Acao.ALTEROU, Alvo.CRIANCA, criancaId, criancaId = criancaId)
    }

    @Query(
        """SELECT r.*, cr.principal, (SELECT COUNT(*) FROM CriancaResponsavel x WHERE x.responsavelId = r.id) AS criancas
           FROM Responsavel r JOIN CriancaResponsavel cr ON cr.responsavelId = r.id
           WHERE cr.criancaId = :criancaId ORDER BY r.nome COLLATE NOCASE""",
    )
    abstract suspend fun responsaveisDaCrianca(criancaId: Long): List<ResponsavelDaCrianca>
    @Query("SELECT * FROM Responsavel") abstract suspend fun responsaveis(): List<Responsavel>
    @Query("SELECT * FROM Responsavel WHERE telefone = :telefone") abstract suspend fun responsavelPorTelefone(telefone: String): Responsavel?
    @Query("SELECT * FROM Responsavel WHERE id = :id") protected abstract suspend fun responsavel(id: Long): Responsavel?
    @Insert protected abstract suspend fun inserir(r: Responsavel): Long
    @Update protected abstract suspend fun atualizarResponsavel(r: Responsavel)

    // Consentimento que passa de vazio a preenchido é registrado à parte; o resto é "alterou".
    @Transaction
    open suspend fun salvarResponsavel(r: Responsavel) {
        val antes = responsavel(r.id)
        atualizarResponsavel(r)
        val acao = if (antes?.consentiuEm == null && r.consentiuEm != null) Acao.REGISTROU_CONSENTIMENTO else Acao.ALTEROU
        registrar(acao, Alvo.RESPONSAVEL, r.id)
    }
    @Upsert protected abstract suspend fun vincular(v: CriancaResponsavel)
    @Query("UPDATE CriancaResponsavel SET principal = 0 WHERE criancaId = :criancaId")
    protected abstract suspend fun desmarcarPrincipais(criancaId: Long)
    @Query("DELETE FROM CriancaResponsavel WHERE criancaId = :criancaId AND responsavelId = :responsavelId")
    protected abstract suspend fun desvincular(criancaId: Long, responsavelId: Long)
    @Query("DELETE FROM Responsavel WHERE id = :id AND NOT EXISTS (SELECT 1 FROM CriancaResponsavel WHERE responsavelId = :id)")
    protected abstract suspend fun apagarSeSemCrianca(id: Long): Int

    // Mesmo telefone = mesma pessoa: reaproveita a linha e só completa o consentimento se ainda não havia.
    @Transaction
    open suspend fun vincularResponsavel(criancaId: Long, r: Responsavel, principal: Boolean): Long {
        val existente = responsavelPorTelefone(r.telefone)
        val id = when {
            existente == null -> inserir(r.copy(id = 0)).also { registrar(Acao.CRIOU, Alvo.RESPONSAVEL, it, criancaId) }
            existente.consentiuEm == null && r.consentiuEm != null ->
                existente.id.also { salvarResponsavel(existente.copy(consentiuEm = r.consentiuEm, versaoTermo = r.versaoTermo)) }
            else -> existente.id
        }
        val atuais = responsaveisDaCrianca(criancaId)
        val vaiSerPrincipal = principal || atuais.none { it.principal && it.responsavel.id != id }
        if (vaiSerPrincipal) desmarcarPrincipais(criancaId)
        vincular(CriancaResponsavel(criancaId, id, vaiSerPrincipal))
        registrar(Acao.VINCULOU, Alvo.RESPONSAVEL, id, criancaId)
        return id
    }

    @Transaction
    open suspend fun desvincularResponsavel(criancaId: Long, responsavelId: Long) {
        val eraPrincipal = responsaveisDaCrianca(criancaId).any { it.responsavel.id == responsavelId && it.principal }
        desvincular(criancaId, responsavelId)
        registrar(Acao.DESVINCULOU, Alvo.RESPONSAVEL, responsavelId, criancaId)
        // LGPD: sem nenhuma criança, nome e telefone não ficam guardados
        if (apagarSeSemCrianca(responsavelId) > 0) registrar(Acao.REMOVEU, Alvo.RESPONSAVEL, responsavelId, criancaId)
        if (eraPrincipal) responsaveisDaCrianca(criancaId).firstOrNull()?.let {
            vincular(CriancaResponsavel(criancaId, it.responsavel.id, true))
        }
    }
}
