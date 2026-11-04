package com.assistenteclinica.dados

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.assistenteclinica.dominio.TipoCliente
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

// Etapa 0 do Bloco 8: prova de que o sqlcipher_export copia o banco do app para outro arquivo cifrado com outra
// chave, que o Room abre igual (backup do plano 2026-10-29). Só bancos de teste com nome sorteado e dados inventados.
@RunWith(AndroidJUnit4::class)
class ProvaExportacaoTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sorteio = Random.nextLong().toULong()
    private val nome = "teste_$sorteio.db"
    private val nomeCopia = "teste_${sorteio}_copia.db"
    private val chave = Random.nextBytes(32)
    private val chaveCopia = Random.nextBytes(32)

    @After fun apagar() { context.deleteDatabase(nome); context.deleteDatabase(nomeCopia) }

    // Grava pelo Room e exporta com o Room ainda aberto, como no app em uso. O ATTACH vai por uma segunda conexão,
    // direta e sem WAL ligado nela: pela conexão do Room, o SQLCipher tenta desligar o WAL no ATTACH e falha com
    // as conexões do Room abertas (1ª rodada no Poco, 29/10). O que está no WAL do Room também precisa ir.
    // Devolve a versão do banco original, para comparar com a da cópia.
    private fun gravarEExportar(): Int = runBlocking {
        val banco = abrirBanco(context, chave, nome)
        val cadastros = banco.cadastros()
        val fono = cadastros.salvarTerapia(Terapia(nome = "Fono Teste", duracaoMin = 40))
        cadastros.salvarTerapeuta(Terapeuta(nome = "Terapeuta Teste", telefone = "5591988880040"), setOf(fono))
        cadastros.salvarCrianca(Crianca(nome = "Criança Teste B", tipo = TipoCliente.FIXO))
        banco.configuracao().salvar(Configuracao("teste", "ok"))
        val destino = context.getDatabasePath(nomeCopia).absolutePath
        val direta = SQLiteDatabase.openDatabase(context.getDatabasePath(nome).absolutePath, chave.copyOf(), null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY, null)
        val versaoOriginal = try {
            direta.execSQL("ATTACH DATABASE ? AS copia KEY ?", arrayOf<Any>(destino, chaveCopia))
            direta.rawQuery("SELECT sqlcipher_export('copia')", null).use { it.moveToFirst() }
            direta.execSQL("DETACH DATABASE copia")
            direta.version
        } finally {
            direta.close()
        }
        banco.close()
        versaoOriginal
    }

    @Test fun copiaAbreComAChaveNovaComOsMesmosDados() = runBlocking {
        val versaoOriginal = gravarEExportar()
        val copia = abrirBanco(context, chaveCopia, nomeCopia)
        assertEquals(listOf("Fono Teste"), copia.cadastros().terapias().map { it.nome })
        assertEquals(listOf("Terapeuta Teste"), copia.cadastros().terapeutas().map { it.nome })
        assertEquals(listOf("Criança Teste B"), copia.cadastros().criancas().map { it.nome })
        assertEquals("ok", copia.configuracao().valor("teste"))
        // O Room confia no user_version para saber a versão do banco: sem ele, tentaria migrar a cópia. Compara com a
        // versão do original (não um número fixo), para o teste valer a cada versão nova do banco.
        val versao = copia.openHelper.readableDatabase.query("PRAGMA user_version").use { it.moveToFirst(); it.getInt(0) }
        assertEquals(versaoOriginal, versao)
        copia.close()
    }

    @Test fun copiaNaoAbreComAChaveDoBancoNemComOutra() {
        gravarEExportar()
        listOf(chave, Random.nextBytes(32)).forEach { k ->
            val copia = abrirBanco(context, k, nomeCopia)
            assertThrows(Exception::class.java) { runBlocking { copia.configuracao().valor("teste") } }
            copia.close()
        }
    }

    @Test fun copiaNoDiscoNaoESqliteLegivel() {
        gravarEExportar()
        val inicio = context.getDatabasePath(nomeCopia).readBytes().copyOf(16)
        assertFalse(inicio.contentEquals("SQLite format 3\u0000".toByteArray()))
    }
}
