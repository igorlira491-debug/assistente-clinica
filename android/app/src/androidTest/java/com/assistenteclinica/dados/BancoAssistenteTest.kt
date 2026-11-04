package com.assistenteclinica.dados

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BancoAssistenteTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val nome = "teste_${Random.nextLong().toULong()}.db"
    private val chave = Random.nextBytes(32)

    @After fun apagar() { context.deleteDatabase(nome) }

    @Test fun gravaFechaELeDeNovoComAMesmaChave() = runBlocking {
        abrirBanco(context, chave, nome).apply { configuracao().salvar(Configuracao("teste", "ok")); close() }
        val banco = abrirBanco(context, chave, nome)
        assertEquals("ok", banco.configuracao().valor("teste"))
        banco.close()
    }

    @Test fun chaveErradaNaoAbre() {
        runBlocking { abrirBanco(context, chave, nome).apply { configuracao().salvar(Configuracao("teste", "ok")); close() } }
        val banco = abrirBanco(context, Random.nextBytes(32), nome)
        assertThrows(Exception::class.java) { runBlocking { banco.configuracao().valor("teste") } }
        banco.close()
    }

    @Test fun arquivoNoDiscoNaoESqliteLegivel() {
        runBlocking { abrirBanco(context, chave, nome).apply { configuracao().salvar(Configuracao("teste", "ok")); close() } }
        val inicio = context.getDatabasePath(nome).readBytes().copyOf(16)
        assertFalse(inicio.contentEquals("SQLite format 3\u0000".toByteArray()))
    }

    // Banco do Bloco 2 (v1, criado à mão igual ao app/schemas/1.json): a v2 abre, mantém o dado e ganha as tabelas novas.
    @Test fun atualizaDoBancoV1SemPerderDados() = runBlocking {
        System.loadLibrary("sqlcipher")
        context.getDatabasePath(nome).parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(nome), chave.copyOf(), null, null).apply {
            execSQL("CREATE TABLE IF NOT EXISTS `Configuracao` (`chave` TEXT NOT NULL, `valor` TEXT NOT NULL, PRIMARY KEY(`chave`))")
            execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'aa8299d60e3ab95d942f9481e38aefca')")
            execSQL("INSERT INTO Configuracao VALUES ('teste', 'ok')")
            version = 1
            close()
        }
        val banco = abrirBanco(context, chave, nome)
        assertEquals("ok", banco.configuracao().valor("teste"))
        assertEquals(emptyList<Crianca>(), banco.cadastros().criancas())
        banco.close()
    }
    // Banco do Bloco 3 (v2, criado à mão igual ao app/schemas/2.json): a v3 abre, mantém a criança e ganha a agenda.
    @Test fun atualizaDoBancoV2SemPerderDados() = runBlocking {
        System.loadLibrary("sqlcipher")
        context.getDatabasePath(nome).parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(nome), chave.copyOf(), null, null).apply {
            listOf(
            "CREATE TABLE IF NOT EXISTS `Configuracao` (`chave` TEXT NOT NULL, `valor` TEXT NOT NULL, PRIMARY KEY(`chave`))",
            "CREATE TABLE IF NOT EXISTS `Terapia` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `nome` TEXT NOT NULL, `duracaoMin` INTEGER NOT NULL, `ativa` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `Terapeuta` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `nome` TEXT NOT NULL, `telefone` TEXT NOT NULL, `ativa` INTEGER NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_Terapeuta_telefone` ON `Terapeuta` (`telefone`)",
            "CREATE TABLE IF NOT EXISTS `TerapeutaTerapia` (`terapeutaId` INTEGER NOT NULL, `terapiaId` INTEGER NOT NULL, PRIMARY KEY(`terapeutaId`, `terapiaId`), FOREIGN KEY(`terapeutaId`) REFERENCES `Terapeuta`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`terapiaId`) REFERENCES `Terapia`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_TerapeutaTerapia_terapiaId` ON `TerapeutaTerapia` (`terapiaId`)",
            "CREATE TABLE IF NOT EXISTS `Crianca` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `nome` TEXT NOT NULL, `tipo` TEXT NOT NULL, `observacao` TEXT NOT NULL, `ativa` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `CriancaTerapia` (`criancaId` INTEGER NOT NULL, `terapiaId` INTEGER NOT NULL, `terapeutaId` INTEGER NOT NULL, PRIMARY KEY(`criancaId`, `terapiaId`), FOREIGN KEY(`criancaId`) REFERENCES `Crianca`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`terapiaId`) REFERENCES `Terapia`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`terapeutaId`) REFERENCES `Terapeuta`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            "CREATE INDEX IF NOT EXISTS `index_CriancaTerapia_terapiaId` ON `CriancaTerapia` (`terapiaId`)",
            "CREATE INDEX IF NOT EXISTS `index_CriancaTerapia_terapeutaId` ON `CriancaTerapia` (`terapeutaId`)",
            "CREATE TABLE IF NOT EXISTS `Responsavel` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `nome` TEXT NOT NULL, `telefone` TEXT NOT NULL, `consentiuEm` INTEGER, `versaoTermo` TEXT)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_Responsavel_telefone` ON `Responsavel` (`telefone`)",
            "CREATE TABLE IF NOT EXISTS `CriancaResponsavel` (`criancaId` INTEGER NOT NULL, `responsavelId` INTEGER NOT NULL, `principal` INTEGER NOT NULL, PRIMARY KEY(`criancaId`, `responsavelId`), FOREIGN KEY(`criancaId`) REFERENCES `Crianca`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`responsavelId`) REFERENCES `Responsavel`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_CriancaResponsavel_responsavelId` ON `CriancaResponsavel` (`responsavelId`)",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '9f31b5de91806ea846a8baca5462ece3')",
            "INSERT INTO Crianca (nome, tipo, observacao, ativa) VALUES ('Criança Teste A', 'FIXO', '', 1)",
            ).forEach { execSQL(it) }
            version = 2
            close()
        }
        val banco = abrirBanco(context, chave, nome)
        assertEquals(listOf("Criança Teste A"), banco.cadastros().criancas().map { it.nome })
        assertEquals(emptyList<Any>(), banco.agenda().horariosParaLancar()) // a tabela nova existe e está vazia
        banco.close()
    }
    // Banco do Bloco 4 (v3, criado à mão igual ao app/schemas/3.json): a v4 abre, mantém a sessão e ganha as pendências.
    @Test fun atualizaDoBancoV3SemPerderDados() = runBlocking {
        System.loadLibrary("sqlcipher")
        context.getDatabasePath(nome).parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(nome), chave.copyOf(), null, null).apply {
            listOf(
            "CREATE TABLE IF NOT EXISTS `Configuracao` (`chave` TEXT NOT NULL, `valor` TEXT NOT NULL, PRIMARY KEY(`chave`))",
            "CREATE TABLE IF NOT EXISTS `Terapia` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `nome` TEXT NOT NULL, `duracaoMin` INTEGER NOT NULL, `ativa` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `Terapeuta` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `nome` TEXT NOT NULL, `telefone` TEXT NOT NULL, `ativa` INTEGER NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_Terapeuta_telefone` ON `Terapeuta` (`telefone`)",
            "CREATE TABLE IF NOT EXISTS `TerapeutaTerapia` (`terapeutaId` INTEGER NOT NULL, `terapiaId` INTEGER NOT NULL, PRIMARY KEY(`terapeutaId`, `terapiaId`), FOREIGN KEY(`terapeutaId`) REFERENCES `Terapeuta`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`terapiaId`) REFERENCES `Terapia`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_TerapeutaTerapia_terapiaId` ON `TerapeutaTerapia` (`terapiaId`)",
            "CREATE TABLE IF NOT EXISTS `Crianca` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `nome` TEXT NOT NULL, `tipo` TEXT NOT NULL, `observacao` TEXT NOT NULL, `ativa` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `CriancaTerapia` (`criancaId` INTEGER NOT NULL, `terapiaId` INTEGER NOT NULL, `terapeutaId` INTEGER NOT NULL, PRIMARY KEY(`criancaId`, `terapiaId`), FOREIGN KEY(`criancaId`) REFERENCES `Crianca`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`terapiaId`) REFERENCES `Terapia`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`terapeutaId`) REFERENCES `Terapeuta`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            "CREATE INDEX IF NOT EXISTS `index_CriancaTerapia_terapiaId` ON `CriancaTerapia` (`terapiaId`)",
            "CREATE INDEX IF NOT EXISTS `index_CriancaTerapia_terapeutaId` ON `CriancaTerapia` (`terapeutaId`)",
            "CREATE TABLE IF NOT EXISTS `Responsavel` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `nome` TEXT NOT NULL, `telefone` TEXT NOT NULL, `consentiuEm` INTEGER, `versaoTermo` TEXT)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_Responsavel_telefone` ON `Responsavel` (`telefone`)",
            "CREATE TABLE IF NOT EXISTS `CriancaResponsavel` (`criancaId` INTEGER NOT NULL, `responsavelId` INTEGER NOT NULL, `principal` INTEGER NOT NULL, PRIMARY KEY(`criancaId`, `responsavelId`), FOREIGN KEY(`criancaId`) REFERENCES `Crianca`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`responsavelId`) REFERENCES `Responsavel`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_CriancaResponsavel_responsavelId` ON `CriancaResponsavel` (`responsavelId`)",
            "CREATE TABLE IF NOT EXISTS `Sessao` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `criancaId` INTEGER NOT NULL, `terapiaId` INTEGER NOT NULL, `terapeutaId` INTEGER NOT NULL, `inicio` INTEGER NOT NULL, `duracaoMin` INTEGER NOT NULL, `familia` TEXT NOT NULL, `terapeuta` TEXT NOT NULL, `origem` TEXT NOT NULL, `tipo` TEXT NOT NULL, `desfecho` TEXT, FOREIGN KEY(`criancaId`) REFERENCES `Crianca`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , FOREIGN KEY(`terapiaId`) REFERENCES `Terapia`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , FOREIGN KEY(`terapeutaId`) REFERENCES `Terapeuta`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            "CREATE INDEX IF NOT EXISTS `index_Sessao_inicio` ON `Sessao` (`inicio`)",
            "CREATE INDEX IF NOT EXISTS `index_Sessao_criancaId` ON `Sessao` (`criancaId`)",
            "CREATE INDEX IF NOT EXISTS `index_Sessao_terapiaId` ON `Sessao` (`terapiaId`)",
            "CREATE INDEX IF NOT EXISTS `index_Sessao_terapeutaId` ON `Sessao` (`terapeutaId`)",
            "CREATE TABLE IF NOT EXISTS `Horario` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `criancaId` INTEGER NOT NULL, `terapiaId` INTEGER NOT NULL, `diaDaSemana` INTEGER NOT NULL, `hora` INTEGER NOT NULL, `ativo` INTEGER NOT NULL, FOREIGN KEY(`criancaId`) REFERENCES `Crianca`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , FOREIGN KEY(`terapiaId`) REFERENCES `Terapia`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            "CREATE INDEX IF NOT EXISTS `index_Horario_criancaId` ON `Horario` (`criancaId`)",
            "CREATE INDEX IF NOT EXISTS `index_Horario_terapiaId` ON `Horario` (`terapiaId`)",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '0ab020931bd5a0954d7152cc7eb54ab6')",
            "INSERT INTO Crianca (nome, tipo, observacao, ativa) VALUES ('Criança Teste A', 'FIXO', '', 1)",
            "INSERT INTO Terapia (nome, duracaoMin, ativa) VALUES ('Fono', 40, 1)",
            "INSERT INTO Terapeuta (nome, telefone, ativa) VALUES ('Terapeuta Teste', '5581999990002', 1)",
            "INSERT INTO Sessao (criancaId, terapiaId, terapeutaId, inicio, duracaoMin, familia, terapeuta, origem, tipo, desfecho) VALUES (1, 1, 1, 1793710800, 40, 'AGUARDANDO', 'AGUARDANDO', 'AVULSA', 'COMUM', NULL)",
            ).forEach { execSQL(it) }
            version = 3
            close()
        }
        val banco = abrirBanco(context, chave, nome)
        assertEquals(1, banco.pendencias().sessoesEmAberto(java.time.LocalDateTime.MAX).size)
        assertEquals(emptyList<Any>(), banco.pendencias().abertas())
        banco.close()
    }
    // Banco do Bloco 5 (v4, criado à mão igual ao app/schemas/4.json): a v5 abre, mantém sessão e pendência,
    // e as sessões antigas ficam sem as marcas novas (confirmação da família e lançada depois).
    @Test fun atualizaDoBancoV4SemPerderDados() = runBlocking {
        System.loadLibrary("sqlcipher")
        context.getDatabasePath(nome).parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(nome), chave.copyOf(), null, null).apply {
            listOf(
            "CREATE TABLE IF NOT EXISTS `Configuracao` (`chave` TEXT NOT NULL, `valor` TEXT NOT NULL, PRIMARY KEY(`chave`))",
            "CREATE TABLE IF NOT EXISTS `Terapia` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `nome` TEXT NOT NULL, `duracaoMin` INTEGER NOT NULL, `ativa` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `Terapeuta` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `nome` TEXT NOT NULL, `telefone` TEXT NOT NULL, `ativa` INTEGER NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_Terapeuta_telefone` ON `Terapeuta` (`telefone`)",
            "CREATE TABLE IF NOT EXISTS `TerapeutaTerapia` (`terapeutaId` INTEGER NOT NULL, `terapiaId` INTEGER NOT NULL, PRIMARY KEY(`terapeutaId`, `terapiaId`), FOREIGN KEY(`terapeutaId`) REFERENCES `Terapeuta`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`terapiaId`) REFERENCES `Terapia`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_TerapeutaTerapia_terapiaId` ON `TerapeutaTerapia` (`terapiaId`)",
            "CREATE TABLE IF NOT EXISTS `Crianca` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `nome` TEXT NOT NULL, `tipo` TEXT NOT NULL, `observacao` TEXT NOT NULL, `ativa` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `CriancaTerapia` (`criancaId` INTEGER NOT NULL, `terapiaId` INTEGER NOT NULL, `terapeutaId` INTEGER NOT NULL, PRIMARY KEY(`criancaId`, `terapiaId`), FOREIGN KEY(`criancaId`) REFERENCES `Crianca`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`terapiaId`) REFERENCES `Terapia`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`terapeutaId`) REFERENCES `Terapeuta`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            "CREATE INDEX IF NOT EXISTS `index_CriancaTerapia_terapiaId` ON `CriancaTerapia` (`terapiaId`)",
            "CREATE INDEX IF NOT EXISTS `index_CriancaTerapia_terapeutaId` ON `CriancaTerapia` (`terapeutaId`)",
            "CREATE TABLE IF NOT EXISTS `Responsavel` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `nome` TEXT NOT NULL, `telefone` TEXT NOT NULL, `consentiuEm` INTEGER, `versaoTermo` TEXT)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_Responsavel_telefone` ON `Responsavel` (`telefone`)",
            "CREATE TABLE IF NOT EXISTS `CriancaResponsavel` (`criancaId` INTEGER NOT NULL, `responsavelId` INTEGER NOT NULL, `principal` INTEGER NOT NULL, PRIMARY KEY(`criancaId`, `responsavelId`), FOREIGN KEY(`criancaId`) REFERENCES `Crianca`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`responsavelId`) REFERENCES `Responsavel`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_CriancaResponsavel_responsavelId` ON `CriancaResponsavel` (`responsavelId`)",
            "CREATE TABLE IF NOT EXISTS `Sessao` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `criancaId` INTEGER NOT NULL, `terapiaId` INTEGER NOT NULL, `terapeutaId` INTEGER NOT NULL, `inicio` INTEGER NOT NULL, `duracaoMin` INTEGER NOT NULL, `familia` TEXT NOT NULL, `terapeuta` TEXT NOT NULL, `origem` TEXT NOT NULL, `tipo` TEXT NOT NULL, `desfecho` TEXT, FOREIGN KEY(`criancaId`) REFERENCES `Crianca`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , FOREIGN KEY(`terapiaId`) REFERENCES `Terapia`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , FOREIGN KEY(`terapeutaId`) REFERENCES `Terapeuta`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            "CREATE INDEX IF NOT EXISTS `index_Sessao_inicio` ON `Sessao` (`inicio`)",
            "CREATE INDEX IF NOT EXISTS `index_Sessao_criancaId` ON `Sessao` (`criancaId`)",
            "CREATE INDEX IF NOT EXISTS `index_Sessao_terapiaId` ON `Sessao` (`terapiaId`)",
            "CREATE INDEX IF NOT EXISTS `index_Sessao_terapeutaId` ON `Sessao` (`terapeutaId`)",
            "CREATE TABLE IF NOT EXISTS `Horario` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `criancaId` INTEGER NOT NULL, `terapiaId` INTEGER NOT NULL, `diaDaSemana` INTEGER NOT NULL, `hora` INTEGER NOT NULL, `ativo` INTEGER NOT NULL, FOREIGN KEY(`criancaId`) REFERENCES `Crianca`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , FOREIGN KEY(`terapiaId`) REFERENCES `Terapia`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            "CREATE INDEX IF NOT EXISTS `index_Horario_criancaId` ON `Horario` (`criancaId`)",
            "CREATE INDEX IF NOT EXISTS `index_Horario_terapiaId` ON `Horario` (`terapiaId`)",
            "CREATE TABLE IF NOT EXISTS `Pendencia` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tipo` TEXT NOT NULL, `criancaId` INTEGER, `sessaoId` INTEGER, `prazo` INTEGER NOT NULL, `criadaEm` INTEGER NOT NULL, `motivoAdiamento` TEXT, `resolvidaEm` INTEGER, `resolvidaPor` INTEGER, `como` TEXT)",
            "CREATE INDEX IF NOT EXISTS `index_Pendencia_resolvidaEm` ON `Pendencia` (`resolvidaEm`)",
            "CREATE INDEX IF NOT EXISTS `index_Pendencia_sessaoId` ON `Pendencia` (`sessaoId`)",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '580afce1db12a7ba39067f6b02cf1926')",
            "INSERT INTO Crianca (nome, tipo, observacao, ativa) VALUES ('Criança Teste A', 'FIXO', '', 1)",
            "INSERT INTO Terapia (nome, duracaoMin, ativa) VALUES ('Fono', 40, 1)",
            "INSERT INTO Terapeuta (nome, telefone, ativa) VALUES ('Terapeuta Teste', '5581999990002', 1)",
            "INSERT INTO Sessao (criancaId, terapiaId, terapeutaId, inicio, duracaoMin, familia, terapeuta, origem, tipo, desfecho) VALUES (1, 1, 1, 1793710800, 40, 'SIM', 'AGUARDANDO', 'AVULSA', 'COMUM', NULL)",
            "INSERT INTO Pendencia (tipo, criancaId, sessaoId, prazo, criadaEm, motivoAdiamento, resolvidaEm, resolvidaPor, como) VALUES ('REMARCAR', 1, 1, 1793710800, 1793710800, NULL, NULL, NULL, NULL)",
            ).forEach { execSQL(it) }
            version = 4
            close()
        }
        val banco = abrirBanco(context, chave, nome)
        val antiga = banco.agenda().sessao(1)!!.sessao
        assertEquals(com.assistenteclinica.dominio.Resposta.SIM, antiga.familia)
        assertEquals(null, antiga.familiaConfirmouEm)
        assertEquals(null, antiga.lancadaDepoisEm)
        assertEquals(null, antiga.lancadaPor)
        assertEquals(1, banco.pendencias().abertas().size)
        // As colunas novas gravam e voltam.
        val em = java.time.LocalDateTime.of(2026, 10, 28, 19, 0)
        banco.agenda().salvarSessao(antiga.copy(lancadaDepoisEm = em, lancadaPor = 2))
        assertEquals(em, banco.agenda().sessao(1)!!.sessao.lancadaDepoisEm)
        assertEquals(2L, banco.agenda().sessao(1)!!.sessao.lancadaPor)
        // v6 (Bloco 8): a tabela do registro existe, com as duas regras que recusam alterar e apagar.
        val regras = banco.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type = 'trigger' ORDER BY name").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        assertEquals(listOf("registro_nao_altera", "registro_nao_apaga"), regras)
        assertEquals(1, banco.registro().ultimos(10).size) // só o "alterou a sessão" de agora
        banco.close()
    }
}
