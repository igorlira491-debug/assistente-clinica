package com.assistenteclinica.dados

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.assistenteclinica.dominio.Acao
import com.assistenteclinica.dominio.Alvo
import com.assistenteclinica.dominio.Detalhe
import com.assistenteclinica.dominio.NomesDoRegistro
import com.assistenteclinica.dominio.Usuaria
import com.assistenteclinica.dominio.Registro
import java.time.LocalDateTime

// Registro de ações (spec §6.3). Sem campo de texto: só números e as listas fechadas do domínio (dominio/Registro.kt).
@Entity(tableName = "Registro", indices = [Index("momento"), Index("criancaId")])
data class LinhaRegistro(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val momento: LocalDateTime,
    val usuariaId: Long?,
    val acao: Acao,
    val alvo: Alvo?,
    val alvoId: Long?,
    val criancaId: Long?,
    val detalhe: Detalhe?,
    val quantidade: Int?,
)

fun LinhaRegistro.paraDominio() = Registro(momento, usuariaId, acao, alvo, alvoId, criancaId, detalhe, quantidade)
fun Registro.paraLinha() = LinhaRegistro(0, momento, usuariaId, acao, alvo, alvoId, criancaId, detalhe, quantidade)

// Só inserir e ler: não existe alterar nem apagar. As regras do próprio banco (criarRegrasDoRegistro) recusam as duas
// coisas mesmo vindas de um erro de programação.
@Dao abstract class RegistroDao {
    @Insert abstract suspend fun inserir(r: LinhaRegistro): Long
    @Query("SELECT * FROM Registro ORDER BY id DESC LIMIT :limite") abstract suspend fun ultimos(limite: Int): List<LinhaRegistro>

    // Tela da dona: um dia, do mais novo para o mais antigo; usuariaId null = todas.
    @Query(
        """SELECT * FROM Registro WHERE momento >= :de AND momento < :ate AND (:usuariaId IS NULL OR usuariaId = :usuariaId)
           ORDER BY id DESC LIMIT :limite""",
    )
    abstract suspend fun doPeriodo(de: LocalDateTime, ate: LocalDateTime, usuariaId: Long?, limite: Int): List<LinhaRegistro>

    // Quando cada criança foi desativada pela última vez (para a lista de crianças inativas).
    @Query("SELECT alvoId AS id, MAX(momento) AS desde FROM Registro WHERE acao = 'DESATIVOU' AND alvo = 'CRIANCA' AND alvoId IS NOT NULL GROUP BY alvoId")
    abstract suspend fun criancasDesativadasEm(): List<DesativadaEm>
}

data class DesativadaEm(val id: Long, val desde: LocalDateTime)

// Os nomes para a tela do registro, lidos do cadastro na hora (as usuárias vêm do cadastro de acesso).
suspend fun BancoAssistente.nomesDoRegistro(usuarias: List<Usuaria>): NomesDoRegistro {
    val c = cadastros()
    return NomesDoRegistro(
        usuarias.associate { it.id to it.nome },
        c.criancas().associate { it.id to it.nome },
        c.terapeutas().associate { it.id to it.nome },
        c.terapias().associate { it.id to it.nome },
        c.responsaveis().associate { it.id to it.nome },
    )
}

// Base dos DAOs que gravam: cada ação insere a sua linha na mesma transação (ou as duas ficam, ou nenhuma).
// Quem e quando vêm do banco aberto: ele é aberto a cada entrada, para uma usuária só (SessaoDeAcesso.entrar).
abstract class DaoComRegistro(private val db: RoomDatabase) {
    @Insert protected abstract suspend fun inserirRegistro(r: LinhaRegistro)

    protected suspend fun registrar(
        acao: Acao, alvo: Alvo? = null, alvoId: Long? = null, criancaId: Long? = null,
        detalhe: Detalhe? = null, quantidade: Int? = null,
    ) {
        val b = db as BancoAssistente
        inserirRegistro(LinhaRegistro(0, LocalDateTime.now(b.relogio), b.usuariaAtual, acao, alvo, alvoId, criancaId, detalhe, quantidade))
    }
}

// Roda a cada abertura do banco (IF NOT EXISTS): vale para banco novo e para o que veio da versão 5.
fun criarRegrasDoRegistro(db: SupportSQLiteDatabase) {
    db.execSQL("CREATE TRIGGER IF NOT EXISTS registro_nao_altera BEFORE UPDATE ON Registro BEGIN SELECT RAISE(ABORT, 'o registro de ações não pode ser alterado'); END")
    db.execSQL("CREATE TRIGGER IF NOT EXISTS registro_nao_apaga BEFORE DELETE ON Registro BEGIN SELECT RAISE(ABORT, 'o registro de ações não pode ser apagado'); END")
}
