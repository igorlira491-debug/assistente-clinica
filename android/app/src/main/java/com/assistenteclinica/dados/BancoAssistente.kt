package com.assistenteclinica.dados

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.Clock
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Entity data class Configuracao(@PrimaryKey val chave: String, val valor: String)

@Dao interface ConfiguracaoDao {
    @Upsert suspend fun salvar(c: Configuracao)
    @Query("SELECT valor FROM Configuracao WHERE chave = :chave") suspend fun valor(chave: String): String?
}

// Versões 2 (Bloco 3), 3 (Bloco 4), 4 (Bloco 5) e 6 (Bloco 8, Registro): só tabelas e colunas novas, então a migração
// automática não toca no que já existe.
@Database(
    entities = [
        Configuracao::class, Terapia::class, Terapeuta::class, TerapeutaTerapia::class,
        Crianca::class, CriancaTerapia::class, Responsavel::class, CriancaResponsavel::class,
        LinhaSessao::class, Horario::class, Pendencia::class, LinhaRegistro::class,
    ],
    version = VERSAO_DO_BANCO,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6)],
)
@TypeConverters(Conversores::class)
abstract class BancoAssistente : RoomDatabase() {
    abstract fun configuracao(): ConfiguracaoDao
    abstract fun cadastros(): CadastrosDao
    abstract fun agenda(): AgendaDao
    abstract fun pendencias(): PendenciasDao
    abstract fun registro(): RegistroDao
    abstract fun lgpd(): LgpdDao

    // Quem está usando o banco aberto (registro de ações): definida em SessaoDeAcesso.entrar, uma usuária por abertura.
    @Volatile var usuariaAtual: Long? = null
    @Volatile var relogio: Clock = Clock.systemDefaultZone()
}

const val NOME_DO_BANCO = "assistente.db"

// Um backup de banco mais novo que esta versão não é restaurado ("atualize o app antes").
const val VERSAO_DO_BANCO = 6

// O arquivo inteiro fica cifrado no disco; sem a chave principal ele é ilegível.
fun abrirBanco(context: Context, chave: ByteArray, nome: String = NOME_DO_BANCO): BancoAssistente {
    System.loadLibrary("sqlcipher")
    return Room.databaseBuilder(context, BancoAssistente::class.java, nome)
        .openHelperFactory(SupportOpenHelperFactory(chave.copyOf())) // a fábrica pode zerar o array que recebe
        .addCallback(object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) = criarRegrasDoRegistro(db)
        })
        .build()
}
