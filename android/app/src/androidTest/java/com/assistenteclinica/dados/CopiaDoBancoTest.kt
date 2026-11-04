package com.assistenteclinica.dados

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.assistenteclinica.dominio.Papel
import com.assistenteclinica.dominio.TipoCliente
import com.assistenteclinica.dominio.Usuaria
import com.assistenteclinica.seguranca.AberturaDoBackup
import com.assistenteclinica.seguranca.ArquivoDeBackup
import com.assistenteclinica.seguranca.Chaves
import com.assistenteclinica.seguranca.ConteudoDoBackup
import java.time.LocalDateTime
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

// Bloco 8, etapa 3: banco de verdade → cópia → arquivo de backup → abrir → banco de volta, com o mesmo código de
// conferência. Só bancos de teste com nome sorteado e dados inventados.
@RunWith(AndroidJUnit4::class)
class CopiaDoBancoTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sorteio = Random.nextLong().toULong()
    private val nome = "teste_$sorteio.db"
    private val nomeVolta = "teste_${sorteio}_volta.db"
    private val chave = Random.nextBytes(32)

    @After fun apagar() { context.deleteDatabase(nome); context.deleteDatabase(nomeVolta) }

    @Test fun copiaComOBancoAbertoEDeixaSoOArquivoNaMemoria() = runBlocking {
        val banco = abrirBanco(context, chave, nome)
        banco.cadastros().salvarCrianca(Crianca(nome = "Criança Teste A", tipo = TipoCliente.FIXO))
        val copia = copiarBanco(context, chave, nome)
        assertEquals(1, copia.conferencia.quantidades["Crianca"])
        assertEquals(6, copia.versao)
        assertEquals(calcularConferencia(banco.openHelper.readableDatabase), copia.conferencia)
        assertFalse(context.getDatabasePath("backup_em_preparo.db").exists()) // o temporário não fica no celular
        banco.close()
    }

    @Test fun doBancoAoArquivoEDeVoltaComOMesmoCodigo() = runBlocking {
        val banco = abrirBanco(context, chave, nome)
        val fono = banco.cadastros().salvarTerapia(Terapia(nome = "Fono Teste", duracaoMin = 40))
        banco.cadastros().salvarTerapeuta(Terapeuta(nome = "Terapeuta Teste", telefone = "5511988880070"), setOf(fono))
        banco.cadastros().salvarCrianca(Crianca(nome = "Criança Teste A", tipo = TipoCliente.AVULSO))
        val copia = copiarBanco(context, chave, nome)
        banco.close()

        val senha = "cavalo-azul-77-teste"
        val chaveDeBackup = Chaves.novaChavePrincipal()
        val arquivo = ArquivoDeBackup.montar(
            ConteudoDoBackup(copia.versao, LocalDateTime.of(2026, 10, 29, 8, 12), listOf(Usuaria(1, "Dona Teste", Papel.DONA)), copia.conferencia, copia.chave, copia.bytes),
            chaveDeBackup, Chaves.embrulharComSenha(chaveDeBackup, senha, iteracoes = 1_000),
        )
        val aberto = (ArquivoDeBackup.abrir(arquivo, senha, versaoDoApp = 6) as AberturaDoBackup.Ok).conteudo

        // Volta para um arquivo de banco: o código é conferido antes de qualquer migração (conexão direta, sem Room).
        context.getDatabasePath(nomeVolta).apply { parentFile!!.mkdirs(); writeBytes(aberto.banco) }
        assertEquals(aberto.conferencia, conferenciaDoArquivo(context, nomeVolta, aberto.chaveDoBanco))
        val volta = abrirBanco(context, aberto.chaveDoBanco, nomeVolta)
        assertEquals(listOf("Criança Teste A"), volta.cadastros().criancas().map { it.nome })
        assertEquals(listOf("Terapeuta Teste"), volta.cadastros().terapeutas().map { it.nome })
        volta.close()
    }
}
