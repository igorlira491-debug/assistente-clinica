package com.assistenteclinica.dados

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.assistenteclinica.dominio.TipoCliente
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

// Bloco 8, etapa 2: o código de conferência calculado sobre o banco de verdade. Só bancos de teste com nome
// sorteado e dados inventados.
@RunWith(AndroidJUnit4::class)
class ConferenciaTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sorteio = Random.nextLong().toULong()
    private val nomeA = "teste_${sorteio}_a.db"
    private val nomeB = "teste_${sorteio}_b.db"
    private val nomeCopia = "teste_${sorteio}_copia.db"
    private val chave = Random.nextBytes(32)

    @After fun apagar() { listOf(nomeA, nomeB, nomeCopia).forEach { context.deleteDatabase(it) } }

    private suspend fun BancoAssistente.preencher(criancasNaOrdem: List<String>) {
        val fono = cadastros().salvarTerapia(Terapia(nome = "Fono Teste", duracaoMin = 40))
        cadastros().salvarTerapeuta(Terapeuta(nome = "Terapeuta Teste", telefone = "5591988880060"), setOf(fono))
        criancasNaOrdem.forEach { cadastros().salvarCrianca(Crianca(nome = it, tipo = TipoCliente.FIXO)) }
    }

    private fun BancoAssistente.codigo() = calcularConferencia(openHelper.readableDatabase)

    @Test fun contaAsTabelasDoBanco() = runBlocking {
        val banco = abrirBanco(context, chave, nomeA)
        banco.preencher(listOf("Criança Teste A", "Criança Teste B"))
        val c = banco.codigo()
        assertEquals(2, c.quantidades["Crianca"])
        assertEquals(1, c.quantidades["Terapeuta"])
        assertEquals(4, c.quantidades["Registro"]) // uma linha por cadastro salvo
        assertEquals(false, "room_master_table" in c.quantidades)
        banco.close()
    }

    @Test fun mesmosDadosMesmoCodigoEMudancaMudaOCodigo() = runBlocking {
        // O momento do registro vem do relógio do banco: os dois usam o mesmo, fixo, para o conteúdo ser igual.
        val relogio = Clock.fixed(Instant.parse("2026-11-05T14:05:00Z"), ZoneOffset.UTC)
        val a = abrirBanco(context, chave, nomeA).apply { this.relogio = relogio; preencher(listOf("Criança Teste A")) }
        val b = abrirBanco(context, Random.nextBytes(32), nomeB).apply { this.relogio = relogio; preencher(listOf("Criança Teste A")) }
        assertEquals(a.codigo(), b.codigo())
        b.cadastros().salvarCrianca(Crianca(id = 1, nome = "Criança Teste A", tipo = TipoCliente.AVULSO))
        assertNotEquals(a.codigo().codigo, b.codigo().codigo)
        a.close(); b.close()
    }

    // A cópia do backup (mesma conexão própria da etapa 0) tem que dar o mesmo código do original.
    @Test fun copiaPeloExportDaOMesmoCodigo() = runBlocking {
        val banco = abrirBanco(context, chave, nomeA)
        banco.preencher(listOf("Criança Teste B", "Criança Teste A"))
        val original = banco.codigo()
        val chaveCopia = Random.nextBytes(32)
        val direta = SQLiteDatabase.openDatabase(
            context.getDatabasePath(nomeA).absolutePath, chave.copyOf(), null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY, null,
        )
        try {
            direta.execSQL("ATTACH DATABASE ? AS copia KEY ?", arrayOf<Any>(context.getDatabasePath(nomeCopia).absolutePath, chaveCopia))
            direta.rawQuery("SELECT sqlcipher_export('copia')", null).use { it.moveToFirst() }
            direta.execSQL("DETACH DATABASE copia")
        } finally {
            direta.close()
        }
        banco.close()
        val copia = abrirBanco(context, chaveCopia, nomeCopia)
        assertEquals(original, copia.codigo())
        copia.close()
    }
}
