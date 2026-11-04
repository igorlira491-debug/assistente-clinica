package com.assistenteclinica.acesso

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.assistenteclinica.dados.CadastroDeAcesso
import com.assistenteclinica.dados.Configuracao
import com.assistenteclinica.dominio.Acao
import com.assistenteclinica.dominio.Alvo
import com.assistenteclinica.dominio.Detalhe
import com.assistenteclinica.dominio.Papel
import com.assistenteclinica.dados.calcularConferencia
import com.assistenteclinica.seguranca.AberturaDoBackup
import com.assistenteclinica.seguranca.ArquivoDeBackup
import java.io.File
import java.time.LocalDateTime
import com.assistenteclinica.seguranca.ProtecaoKeystore
import java.security.KeyStore
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private class RelogioAjustavel(var agora: Instant) : Clock() {
    override fun instant() = agora
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId) = this
    fun avancar(d: Duration) { agora = agora.plus(d) }
}

@RunWith(AndroidJUnit4::class)
class SessaoDeAcessoTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sufixo = Random.nextLong().toULong().toString()
    private val arquivo = "acesso_teste_$sufixo"
    private val banco = "teste_$sufixo.db"
    private val alias = "teste_$sufixo"
    private val relogio = RelogioAjustavel(Instant.parse("2026-11-05T12:00:00Z"))
    private val cadastro = CadastroDeAcesso(context, arquivo)
    private val pinDona = "482913"
    private val senha = "cavalo-azul-dourado-77"

    private fun novaSessao(aoIncompleto: () -> Unit = {}, recalcular: suspend (com.assistenteclinica.dados.BancoAssistente) -> Unit = {}) =
        SessaoDeAcesso(
            context, cadastro, ProtecaoKeystore(alias), relogio, banco, iteracoes = 1_000,
            recalcularLembretes = recalcular, aoRecalculoIncompleto = aoIncompleto,
        )

    private fun sessaoComDona() = novaSessao().apply { configurarPrimeiroUso("Dona", pinDona, senha) }

    private val SessaoDeAcesso.donaId get() = cadastro.usuarias().first { it.papel == Papel.DONA }.id

    @After fun apagar() {
        context.deleteSharedPreferences(arquivo)
        context.deleteDatabase(banco)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    // Bloco 7: a tela trava na hora; o banco fecha só depois do recálculo dos lembretes (ou do limite de 2 s).
    @Test fun recalculaComOBancoAbertoAntesDeFechar() = runBlocking {
        val ordem = mutableListOf<String>()
        var recebido: com.assistenteclinica.dados.BancoAssistente? = null
        // Uma consulta de verdade: o Room só abre a conexão na primeira consulta, então isOpen sozinho não prova nada.
        val s = novaSessao { b ->
            recebido = b
            b.configuracao().valor("qualquer")
            ordem += if (b.isOpen) "gravou com o banco aberto" else "banco já fechado"
        }
        s.configurarPrimeiroUso("Dona", pinDona, senha)
        s.aoSairDaTela()
        assertEquals(Estado.Travado, s.estado.value) // a tela não espera o recálculo
        s.esperarFechar()
        ordem += if (recebido!!.isOpen) "banco aberto depois" else "banco fechado depois"
        assertEquals(listOf("gravou com o banco aberto", "banco fechado depois"), ordem)
        assertEquals(null, s.recalculoIncompletoEm)
    }

    // Controle negativo do teste acima: com o banco fechado ANTES da consulta, o mesmo detector tem que acusar.
    // Se o Room reabrisse o banco sozinho, este teste falharia e o de cima não provaria nada.
    @Test fun detectorAcusaBancoFechadoAntesDeGravar() = runBlocking {
        val ordem = mutableListOf<String>()
        val s = novaSessao { b ->
            b.close() // simula o erro: fechar antes de gravar
            val gravou = runCatching { b.configuracao().valor("qualquer") }.isSuccess && b.isOpen
            ordem += if (gravou) "gravou com o banco aberto" else "banco já fechado"
        }
        s.configurarPrimeiroUso("Dona", pinDona, senha)
        s.aoSairDaTela()
        s.esperarFechar()
        assertEquals(listOf("banco já fechado"), ordem)
    }

    @Test fun recalculoQueDemoraTravaMesmoAssim() = runBlocking {
        var recebido: com.assistenteclinica.dados.BancoAssistente? = null
        val s = novaSessao { b -> recebido = b; Thread.sleep(5_000) } // preso numa consulta que não responde
        s.configurarPrimeiroUso("Dona", pinDona, senha)
        val inicio = System.nanoTime()
        s.travar()
        assertEquals(Estado.Travado, s.estado.value)
        s.esperarFechar()
        val segundos = (System.nanoTime() - inicio) / 1e9
        assertTrue("fechou em $segundos s", segundos in 1.9..3.5)
        assertFalse(recebido!!.isOpen)
        assertTrue(s.recalculoIncompletoEm != null)
    }

    @Test fun travaPorTempoNaoBloqueiaATela() {
        val s = novaSessao { Thread.sleep(1_500) }.apply { configurarPrimeiroUso("Dona", pinDona, senha) }
        relogio.avancar(Duration.ofMinutes(4))
        var ms = 0L
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val t = System.nanoTime()
            s.verificarTempoSemUso()
            ms = (System.nanoTime() - t) / 1_000_000
        }
        assertEquals(Estado.Travado, s.estado.value)
        assertTrue("a thread da tela ficou presa $ms ms", ms < 200)
        runBlocking { s.esperarFechar() }
    }

    @Test fun sairPeloWhatsAppTambemRecalculaENaoTrava() {
        val recalculou = java.util.concurrent.CountDownLatch(1)
        val s = novaSessao { recalculou.countDown() }.apply { configurarPrimeiroUso("Dona", pinDona, senha) }
        s.marcarSaidaPeloWhatsApp()
        s.aoSairDaTela()
        assertTrue(recalculou.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(s.estado.value is Estado.Destravado)
    }

    @Test fun travaPorTempoTambemRecalculaUmaVez() = runBlocking {
        var vezes = 0
        val s = novaSessao { vezes++ }.apply { configurarPrimeiroUso("Dona", pinDona, senha) }
        relogio.avancar(Duration.ofMinutes(4))
        s.verificarTempoSemUso()
        s.travar() // já travado: sem banco, não recalcula de novo
        s.esperarFechar()
        assertEquals(1, vezes)
    }

    @Test fun falhaNoRecalculoNaoImpedeDeTravarEDeixaAListaDesatualizada() = runBlocking {
        var desatualizou = 0
        val s = novaSessao(aoIncompleto = { desatualizou++ }) { error("falhou") }.apply { configurarPrimeiroUso("Dona", pinDona, senha) }
        s.aoSairDaTela()
        s.esperarFechar()
        assertEquals(Estado.Travado, s.estado.value)
        assertEquals(null, s.recalculoIncompletoEm) // falhou rápido: não é estouro do limite
        assertEquals(1, desatualizou) // os números da lista antiga não valem mais
    }

    @Test fun recalculoQueTerminaNaoDesatualizaALista() = runBlocking {
        var desatualizou = 0
        val s = novaSessao(aoIncompleto = { desatualizou++ }).apply { configurarPrimeiroUso("Dona", pinDona, senha) }
        s.aoSairDaTela()
        s.esperarFechar()
        assertEquals(0, desatualizou)
    }

    // Estourou os 2 s: o recálculo é cancelado e não grava mais nada depois.
    @Test fun recalculoQueEstouraOLimiteECancelado() = runBlocking {
        val gravou = java.util.concurrent.atomic.AtomicBoolean(false)
        var desatualizou = 0
        val s = novaSessao(aoIncompleto = { desatualizou++ }) { kotlinx.coroutines.delay(3_000); gravou.set(true) }
            .apply { configurarPrimeiroUso("Dona", pinDona, senha) }
        s.travar()
        s.esperarFechar()
        Thread.sleep(1_500) // passa dos 3 s do recálculo
        assertFalse(gravou.get())
        assertEquals(1, desatualizou)
    }

    // Saiu pelo WhatsApp e voltou depois da tolerância: a trava espera o recálculo da saída e não roda outro.
    @Test fun travarDuranteORecalculoDoWhatsAppEsperaEle() = runBlocking {
        val vezes = java.util.concurrent.atomic.AtomicInteger()
        val ordem = java.util.Collections.synchronizedList(mutableListOf<String>())
        val s = novaSessao { b ->
            vezes.incrementAndGet()
            Thread.sleep(500)
            val leu = runCatching { b.configuracao().valor("qualquer") }.isSuccess && b.isOpen
            ordem += if (leu) "leu com o banco aberto" else "banco fechado no meio"
        }.apply { configurarPrimeiroUso("Dona", pinDona, senha) }
        s.marcarSaidaPeloWhatsApp()
        s.aoSairDaTela()
        relogio.avancar(Duration.ofMinutes(10))
        s.aoVoltarATela()
        assertEquals(Estado.Travado, s.estado.value)
        s.esperarFechar()
        assertEquals(1, vezes.get())
        assertEquals(listOf("leu com o banco aberto"), ordem)
    }

    @Test fun primeiroUsoCriaADonaEDestrava() {
        val s = novaSessao()
        assertEquals(Estado.PrimeiroUso, s.estado.value)
        s.configurarPrimeiroUso("Dona", pinDona, senha)
        assertEquals("Dona", (s.estado.value as Estado.Destravado).usuaria.nome)
    }

    @Test fun pinCertoDestravaEPinErradoNao() {
        val s = sessaoComDona().apply { travar() }
        assertEquals(ResultadoPin.PinErrado, s.destravar(s.donaId, "111222"))
        assertEquals(Estado.Travado, s.estado.value)
        assertEquals(ResultadoPin.Ok, s.destravar(s.donaId, pinDona))
        assertTrue(s.estado.value is Estado.Destravado)
    }

    @Test fun sextoErroPedeEspera() {
        val s = sessaoComDona().apply { travar() }
        repeat(5) { assertEquals(ResultadoPin.PinErrado, s.destravar(s.donaId, "111222")) }
        assertTrue(s.destravar(s.donaId, pinDona) is ResultadoPin.Espere)
    }

    @Test fun errosContinuamDepoisDeRecriarASessao() {
        val s = sessaoComDona().apply { travar() }
        repeat(5) { s.destravar(s.donaId, "111222") }
        val outra = novaSessao()
        assertTrue(outra.destravar(s.donaId, pinDona) is ResultadoPin.Espere)
    }

    @Test fun sessaoNovaComecaTravada() {
        sessaoComDona()
        assertEquals(Estado.Travado, novaSessao().estado.value)
    }

    @Test fun dadosGravadosContinuamDepoisDeTravarEDestravar() {
        val s = sessaoComDona()
        runBlocking { s.banco.configuracao().salvar(Configuracao("teste", "ok")) }
        s.travar()
        assertThrows(IllegalStateException::class.java) { s.banco }
        s.destravar(s.donaId, pinDona)
        assertEquals("ok", runBlocking { s.banco.configuracao().valor("teste") })
    }

    @Test fun secretariaCriadaPelaDonaDestravaComOProprioPin() {
        val s = sessaoComDona()
        val sec = s.criarUsuaria("Secretária", Papel.SECRETARIA, "907163")
        s.travar()
        assertEquals(ResultadoPin.PinErrado, s.destravar(sec.id, pinDona))
        assertEquals(ResultadoPin.Ok, s.destravar(sec.id, "907163"))
        assertEquals(sec, (s.estado.value as Estado.Destravado).usuaria)
    }

    @Test fun pinRedefinidoOAntigoParaDeAbrir() {
        val s = sessaoComDona()
        val sec = s.criarUsuaria("Secretária", Papel.SECRETARIA, "907163")
        s.redefinirPin(sec.id, "315820")
        s.travar()
        assertEquals(ResultadoPin.PinErrado, s.destravar(sec.id, "907163"))
        assertEquals(ResultadoPin.Ok, s.destravar(sec.id, "315820"))
    }

    @Test fun secretariaDesativadaRecebeInativa() {
        val s = sessaoComDona()
        val sec = s.criarUsuaria("Secretária", Papel.SECRETARIA, "907163")
        s.desativar(sec.id)
        assertThrows(IllegalStateException::class.java) { s.desativar(s.donaId) } // a única dona
        s.travar()
        assertEquals(ResultadoPin.Inativa, s.destravar(sec.id, "907163"))
    }

    @Test fun secretariaNaoPodeCriarUsuaria() {
        val s = sessaoComDona()
        val sec = s.criarUsuaria("Secretária", Papel.SECRETARIA, "907163")
        s.travar(); s.destravar(sec.id, "907163")
        assertThrows(IllegalStateException::class.java) { s.criarUsuaria("Outra", Papel.SECRETARIA, "556677") }
    }

    @Test fun senhaDeBackupRecuperaADonaComPinNovo() {
        val s = sessaoComDona().apply { travar() }
        assertEquals(ResultadoPin.PinErrado, s.recuperarComSenhaBackup(s.donaId, "senha-errada-123", "731904"))
        assertEquals(ResultadoPin.Ok, s.recuperarComSenhaBackup(s.donaId, senha, "731904"))
        s.travar()
        assertEquals(ResultadoPin.PinErrado, s.destravar(s.donaId, pinDona))
        assertEquals(ResultadoPin.Ok, s.destravar(s.donaId, "731904"))
    }

    @Test fun tresMinutosSemUsoTrava() {
        val s = sessaoComDona()
        relogio.avancar(Duration.ofSeconds(179)); s.verificarTempoSemUso()
        assertTrue(s.estado.value is Estado.Destravado)
        relogio.avancar(Duration.ofSeconds(1)); s.verificarTempoSemUso()
        assertEquals(Estado.Travado, s.estado.value)
    }

    @Test fun saidaComumTravaESaidaPeloWhatsappComVoltaRapidaNao() {
        val s = sessaoComDona()
        s.marcarSaidaPeloWhatsApp(); s.aoSairDaTela()
        relogio.avancar(Duration.ofMinutes(2)); s.aoVoltarATela()
        assertTrue(s.estado.value is Estado.Destravado)

        s.marcarSaidaPeloWhatsApp(); s.aoSairDaTela()
        relogio.avancar(Duration.ofMinutes(4)); s.aoVoltarATela()
        assertEquals(Estado.Travado, s.estado.value)

        s.destravar(s.donaId, pinDona)
        s.aoSairDaTela() // saída comum: trava na hora, sem esperar a volta
        assertEquals(Estado.Travado, s.estado.value)
    }

    // Seletor translúcido (contatos no Android 17): o app só pausa, não sai. A marca não pode sobrar para a próxima saída.
    @Test fun marcaDesfeitaNaoDeixaAProximaSaidaSemTrava() {
        val s = sessaoComDona()
        s.marcarSaidaPeloWhatsApp()
        s.desmarcarSaidaPeloWhatsApp() // voltou do seletor sem o app ter saído da tela
        s.aoSairDaTela()
        assertEquals(Estado.Travado, s.estado.value)

        // Mesmo sem desmarcar (tela recriada, resultado perdido), a marca expira em 10 s.
        s.destravar(s.donaId, pinDona)
        s.marcarSaidaPeloWhatsApp()
        relogio.avancar(Duration.ofSeconds(11)); s.aoSairDaTela()
        assertEquals(Estado.Travado, s.estado.value)
    }

    @Test fun falhaDoAparelhoNaoContaComoPinErrado() {
        val s = sessaoComDona().apply { travar() }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) // proteção do aparelho perdida
        repeat(6) { assertEquals(ResultadoPin.FalhaNoAparelho, s.destravar(s.donaId, pinDona)) }
        assertEquals(0, cadastro.erros(s.donaId))
        assertEquals(ResultadoPin.Ok, s.recuperarComSenhaBackup(s.donaId, senha, "731904")) // a saída é a senha de backup
    }

    @Test fun desativadaPerdeOEmbrulhoENaoRecebePinNovo() {
        val s = sessaoComDona()
        val sec = s.criarUsuaria("Secretária", Papel.SECRETARIA, "907163")
        s.desativar(sec.id)
        assertEquals(null, cadastro.embrulhoDe(sec.id))
        assertThrows(IllegalArgumentException::class.java) { s.redefinirPin(sec.id, "315820") }
    }

    @Test fun donaQueSeDesativaSaiNaHora() {
        val s = sessaoComDona()
        s.criarUsuaria("Outra dona", Papel.DONA, "907163")
        s.desativar(s.donaId)
        assertEquals(Estado.Travado, s.estado.value)
    }

    @Test fun senhaDeBackupErradaTambemPedeEspera() {
        val s = sessaoComDona().apply { travar() }
        repeat(5) { assertEquals(ResultadoPin.PinErrado, s.recuperarComSenhaBackup(s.donaId, "chute-errado-00", "731904")) }
        assertTrue(s.recuperarComSenhaBackup(s.donaId, senha, "731904") is ResultadoPin.Espere)
        relogio.avancar(Duration.ofSeconds(30))
        assertEquals(ResultadoPin.Ok, s.recuperarComSenhaBackup(s.donaId, senha, "731904"))
    }

    // Bloco 8, etapa 1: registro de ações do acesso. Os PINs errados acontecem com o banco cifrado e só entram no
    // registro na próxima entrada daquela usuária; a saída é gravada com o banco ainda aberto.
    private fun SessaoDeAcesso.registro() = runBlocking { banco.registro().ultimos(100).reversed() }

    @Test fun entrarSairEErrosDePinFicamNoRegistro() = runBlocking {
        val s = sessaoComDona()
        s.travar()
        s.esperarFechar()
        assertEquals(ResultadoPin.PinErrado, s.destravar(s.donaId, "000000"))
        assertEquals(ResultadoPin.PinErrado, s.destravar(s.donaId, "000001"))
        assertEquals(ResultadoPin.Ok, s.destravar(s.donaId, pinDona))
        val linhas = s.registro()
        assertEquals(
            listOf(Acao.CONFIGUROU_O_APP, Acao.SAIU, Acao.ENTROU, Acao.ERROS_DE_PIN),
            linhas.map { it.acao },
        )
        assertEquals(Detalhe.NO_BOTAO, linhas[1].detalhe)
        assertEquals(2, linhas[3].quantidade)
        linhas.forEach { assertEquals(s.donaId, it.usuariaId) }
    }

    @Test fun usuariasCriadasRedefinidasEDesativadasFicamNoRegistro() {
        val s = sessaoComDona()
        val sec = s.criarUsuaria("Secretária", Papel.SECRETARIA, "135790")
        s.redefinirPin(sec.id, "246802")
        s.desativar(sec.id)
        assertEquals(
            listOf(Triple(Acao.CRIOU, Alvo.USUARIA, sec.id), Triple(Acao.REDEFINIU_PIN, Alvo.USUARIA, sec.id), Triple(Acao.DESATIVOU, Alvo.USUARIA, sec.id)),
            s.registro().drop(1).map { Triple(it.acao, it.alvo, it.alvoId) },
        )
    }

    @Test fun recuperarPelaSenhaRegistraAEntradaEOsErrosDaSenha() = runBlocking {
        val s = sessaoComDona()
        s.travar()
        s.esperarFechar()
        assertEquals(ResultadoPin.PinErrado, s.recuperarComSenhaBackup(s.donaId, "senha-errada-123", "731904"))
        assertEquals(ResultadoPin.Ok, s.recuperarComSenhaBackup(s.donaId, senha, "731904"))
        val ultimas = s.registro().takeLast(2)
        assertEquals(Acao.ENTROU_PELA_SENHA_DE_BACKUP, ultimas[0].acao)
        assertEquals(Triple(Acao.ERROS_DE_PIN, Detalhe.SENHA_DE_BACKUP, 1), Triple(ultimas[1].acao, ultimas[1].detalhe, ultimas[1].quantidade))
    }

    // Bloco 8, etapa 4: chave de backup e arquivo do dia. Pasta de teste com nome sorteado, apagada no fim.
    private val pastaDeTeste get() = File(context.cacheDir, "backup_teste_$sufixo")

    // Simula um app de antes do Bloco 8: sem as duas linhas da chave de backup no cadastro de acesso.
    private fun tirarChaveDeBackup() {
        context.getSharedPreferences(arquivo, android.content.Context.MODE_PRIVATE).edit()
            .remove("chave_backup_principal").remove("chave_backup_senha").commit()
    }

    @Test fun primeiroUsoJaCriaAChaveDeBackup() {
        assertTrue(sessaoComDona().temChaveDeBackup())
    }

    @Test fun appAntigoConfiguraAChaveComASenhaCerta() {
        val s = sessaoComDona()
        tirarChaveDeBackup()
        assertFalse(s.temChaveDeBackup())
        assertEquals(ResultadoPin.PinErrado, s.configurarChaveDeBackup("outra-senha-errada"))
        assertFalse(s.temChaveDeBackup())
        assertEquals(ResultadoPin.Ok, s.configurarChaveDeBackup(senha))
        assertTrue(s.temChaveDeBackup())
        assertEquals(Acao.CONFIGUROU_O_BACKUP, s.registro().last().acao)
    }

    @Test fun secretariaNaoConfiguraOBackup() {
        val s = sessaoComDona()
        s.criarUsuaria("Secretária", Papel.SECRETARIA, "135790")
        s.travar()
        assertEquals(ResultadoPin.Ok, s.destravar(cadastro.usuarias().first { it.papel == Papel.SECRETARIA }.id, "135790"))
        assertThrows(IllegalStateException::class.java) { s.configurarChaveDeBackup(senha) }
    }

    @Test fun arquivoDoDiaAbreComASenhaESoOMaisRecenteFica() {
        val s = sessaoComDona()
        try {
            val (ontem, _) = s.prepararArquivoDeBackup(pastaDeTeste, LocalDateTime.of(2026, 10, 28, 8, 0))
            val (hoje, conferencia) = s.prepararArquivoDeBackup(pastaDeTeste, LocalDateTime.of(2026, 10, 29, 8, 0))
            assertFalse(ontem.exists())
            assertEquals(listOf(hoje.name), pastaDeTeste.listFiles()!!.map { it.name })
            assertEquals("assistente-backup-2026-10-29.assistente", hoje.name)
            val aberto = (ArquivoDeBackup.abrir(hoje.readBytes(), senha, versaoDoApp = 6) as AberturaDoBackup.Ok).conteudo
            assertEquals(listOf("Dona"), aberto.usuarias.map { it.nome })
            assertEquals(conferencia, aberto.conferencia)
            assertEquals(calcularConferencia(s.banco.openHelper.readableDatabase).quantidades.keys, aberto.conferencia.quantidades.keys)
            assertTrue(ArquivoDeBackup.abrir(hoje.readBytes(), "outra-senha-errada", 6) is AberturaDoBackup.SenhaErrada)
        } finally {
            pastaDeTeste.deleteRecursively()
        }
    }

    // ---- Restaurar (Bloco 8, etapa 5) ----
    // O "outro celular": outro cadastro de acesso, outro banco e outra chave do Keystore (sorteados, nunca o assistente.db).

    private val arquivo2 = "acesso_teste2_$sufixo"
    private val banco2 = "teste2_$sufixo.db"
    private val alias2 = "teste2_$sufixo"
    private val cadastro2 = CadastroDeAcesso(context, arquivo2)
    private val pinNovo = "246810"

    private fun outroCelular() = SessaoDeAcesso(context, cadastro2, ProtecaoKeystore(alias2), relogio, banco2, iteracoes = 1_000)

    @After fun apagarOutroCelular() {
        context.deleteSharedPreferences(arquivo2)
        context.deleteDatabase(banco2)
        context.deleteDatabase("restaurando_$banco2")
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias2)
    }

    // Um backup com uma secretária e um dado gravado. Devolve os bytes do arquivo e a conferência da cópia.
    private fun backupDeTeste(s: SessaoDeAcesso = sessaoComDona()): Pair<ByteArray, com.assistenteclinica.dominio.Conferencia> {
        s.criarUsuaria("Secretária", Papel.SECRETARIA, "135790")
        runBlocking { s.banco.configuracao().salvar(Configuracao("teste_restauracao", "valor de teste")) }
        try {
            val (arq, conferencia) = s.prepararArquivoDeBackup(pastaDeTeste, LocalDateTime.of(2026, 10, 29, 8, 0))
            return arq.readBytes() to conferencia
        } finally {
            pastaDeTeste.deleteRecursively()
        }
    }

    private fun nadaGravadoNoOutroCelular(n: SessaoDeAcesso) {
        assertEquals(Estado.PrimeiroUso, n.estado.value)
        assertTrue(cadastro2.usuarias().isEmpty())
        assertFalse(context.getDatabasePath(banco2).exists())
        assertFalse(context.getDatabasePath("restaurando_$banco2").exists())
    }

    @Test fun restaurarNumAppVazioTrazOsDadosEADonaCriaPinNovo() {
        val (bytes, conferencia) = backupDeTeste()
        val n = outroCelular()
        val a = n.abrirBackupParaRestaurar(bytes, senha) as AberturaParaRestaurar.Pronta
        assertEquals(conferencia, a.conferenciaDoArquivo)
        assertEquals(conferencia.codigo, a.codigoRestaurado)
        assertEquals(LocalDateTime.of(2026, 10, 29, 8, 0), a.criadoEm)
        assertEquals(listOf("Dona"), a.donas.map { it.nome })
        assertEquals(Estado.PrimeiroUso, n.estado.value) // antes do PIN, nada vai para o cadastro de acesso
        assertTrue(cadastro2.usuarias().isEmpty())

        n.concluirRestauracao(a.donas.single().id, pinNovo)
        assertTrue(n.estado.value is Estado.Destravado)
        assertEquals("valor de teste", runBlocking { n.banco.configuracao().valor("teste_restauracao") })
        assertEquals(listOf("Dona", "Secretária"), cadastro2.usuarias().map { it.nome })
        val ultima = n.registro().last()
        assertEquals(Acao.RESTAUROU_O_BACKUP, ultima.acao)
        assertEquals(20261029, ultima.quantidade)
        assertFalse(context.getDatabasePath("restaurando_$banco2").exists())

        // Nenhum PIN antigo funciona: a dona entra com o novo, a secretária fica sem PIN até a dona redefinir.
        val secId = cadastro2.usuarias().first { it.papel == Papel.SECRETARIA }.id
        assertFalse(n.temPin(secId))
        n.travar()
        assertEquals(ResultadoPin.SemPin, n.destravar(secId, "135790"))
        assertEquals(ResultadoPin.PinErrado, n.destravar(a.donas.single().id, pinDona))
        assertEquals(ResultadoPin.Ok, n.destravar(a.donas.single().id, pinNovo))
        n.redefinirPin(secId, "579135")
        n.travar()
        assertEquals(ResultadoPin.Ok, n.destravar(secId, "579135"))
    }

    @Test fun oCelularNovoJaFazBackupQueAbreComAMesmaSenha() {
        val (bytes, _) = backupDeTeste()
        val n = outroCelular()
        n.concluirRestauracao((n.abrirBackupParaRestaurar(bytes, senha) as AberturaParaRestaurar.Pronta).donas.single().id, pinNovo)
        assertTrue(n.temChaveDeBackup())
        try {
            val (arq, conferencia) = n.prepararArquivoDeBackup(pastaDeTeste, LocalDateTime.of(2026, 10, 30, 8, 0))
            val aberto = (ArquivoDeBackup.abrir(arq.readBytes(), senha, versaoDoApp = 6) as AberturaDoBackup.Ok).conteudo
            assertEquals(conferencia, aberto.conferencia)
            assertEquals(listOf("Dona", "Secretária"), aberto.usuarias.map { it.nome })
        } finally {
            pastaDeTeste.deleteRecursively()
        }
    }

    @Test fun senhaErradaNaoGravaNadaEPedeEspera() {
        val (bytes, _) = backupDeTeste()
        val n = outroCelular()
        repeat(5) { assertEquals(AberturaParaRestaurar.SenhaErrada, n.abrirBackupParaRestaurar(bytes, "outra-senha-errada")) }
        assertTrue(n.abrirBackupParaRestaurar(bytes, senha) is AberturaParaRestaurar.Espere) // nem a certa, durante a espera
        nadaGravadoNoOutroCelular(n)
        relogio.avancar(Duration.ofMinutes(1))
        assertTrue(n.abrirBackupParaRestaurar(bytes, senha) is AberturaParaRestaurar.Pronta)
    }

    @Test fun arquivoDanificadoOuOutroArquivoNaoGravaNada() {
        val (bytes, _) = backupDeTeste()
        val n = outroCelular()
        val alterado = bytes.copyOf().also { it[it.size - 100] = (it[it.size - 100] + 1).toByte() }
        assertEquals(AberturaParaRestaurar.Danificado, n.abrirBackupParaRestaurar(alterado, senha))
        assertEquals(AberturaParaRestaurar.Danificado, n.abrirBackupParaRestaurar(bytes.copyOf(bytes.size - 10), senha))
        assertEquals(AberturaParaRestaurar.NaoEBackup, n.abrirBackupParaRestaurar("não é backup".toByteArray(), senha))
        nadaGravadoNoOutroCelular(n)
        assertEquals(0, cadastro2.erros(0)) // arquivo ruim não conta como senha errada
    }

    @Test fun appComDadosNaoRestaura() {
        val s = sessaoComDona()
        val (bytes, _) = backupDeTeste(s)
        assertThrows(IllegalStateException::class.java) { s.abrirBackupParaRestaurar(bytes, senha) }
    }

    @Test fun sairDoAppNoMeioCancelaARestauracao() {
        val (bytes, _) = backupDeTeste()
        val n = outroCelular()
        val a = n.abrirBackupParaRestaurar(bytes, senha) as AberturaParaRestaurar.Pronta
        n.aoSairDaTela()
        assertThrows(IllegalStateException::class.java) { n.concluirRestauracao(a.donas.single().id, pinNovo) }
        nadaGravadoNoOutroCelular(n)
    }

    @Test fun soUmaDonaAtivaDoBackupCriaOPin() {
        val (bytes, _) = backupDeTeste()
        val n = outroCelular()
        n.abrirBackupParaRestaurar(bytes, senha) as AberturaParaRestaurar.Pronta
        val secId = 2L // a secretária criada no backup
        assertThrows(IllegalArgumentException::class.java) { n.concluirRestauracao(secId, pinNovo) }
        assertEquals(Estado.PrimeiroUso, n.estado.value)
    }

    // ---- Trocar a senha de backup (etapa 6) ----

    @Test fun trocarSenhaDeBackupComASenhaCerta() {
        val s = sessaoComDona()
        val novaSenha = "girafa-verde-amarela-26"
        try {
            val (antes, _) = s.prepararArquivoDeBackup(pastaDeTeste, LocalDateTime.of(2026, 10, 29, 8, 0))
            val bytesAntes = antes.readBytes()
            assertEquals(ResultadoPin.PinErrado, s.trocarSenhaDeBackup("outra-senha-errada", novaSenha))
            assertEquals(ResultadoPin.Ok, s.trocarSenhaDeBackup(senha, novaSenha))
            assertEquals(Acao.TROCOU_A_SENHA_DE_BACKUP, s.registro().last().acao)
            val (depois, _) = s.prepararArquivoDeBackup(pastaDeTeste, LocalDateTime.of(2026, 10, 30, 8, 0))
            // O arquivo novo abre só com a senha nova; o antigo, só com a antiga.
            assertTrue(ArquivoDeBackup.abrir(depois.readBytes(), novaSenha, 6) is AberturaDoBackup.Ok)
            assertTrue(ArquivoDeBackup.abrir(depois.readBytes(), senha, 6) is AberturaDoBackup.SenhaErrada)
            assertTrue(ArquivoDeBackup.abrir(bytesAntes, senha, 6) is AberturaDoBackup.Ok)
            assertTrue(ArquivoDeBackup.abrir(bytesAntes, novaSenha, 6) is AberturaDoBackup.SenhaErrada)
            // "Esqueci meu PIN" passa a usar a senha nova.
            s.travar()
            assertEquals(ResultadoPin.PinErrado, s.recuperarComSenhaBackup(s.donaId, senha, "731904"))
            assertEquals(ResultadoPin.Ok, s.recuperarComSenhaBackup(s.donaId, novaSenha, "731904"))
        } finally {
            pastaDeTeste.deleteRecursively()
        }
    }

    @Test fun secretariaNaoTrocaASenhaDeBackup() {
        val s = sessaoComDona()
        s.criarUsuaria("Secretária", Papel.SECRETARIA, "135790")
        s.travar()
        assertEquals(ResultadoPin.Ok, s.destravar(cadastro.usuarias().first { it.papel == Papel.SECRETARIA }.id, "135790"))
        assertThrows(IllegalStateException::class.java) { s.trocarSenhaDeBackup(senha, "girafa-verde-amarela-26") }
    }

    // ---- PIN de novo antes de apagar a pedido (etapa 7) ----

    @Test fun conferirPinDaDonaAntesDeApagar() {
        val s = sessaoComDona()
        assertEquals(ResultadoPin.PinErrado, s.conferirPinDaDona("000111"))
        assertEquals(1, cadastro.erros(s.donaId))
        assertEquals(ResultadoPin.Ok, s.conferirPinDaDona(pinDona))
        assertEquals(0, cadastro.erros(s.donaId))
        assertTrue(s.estado.value is Estado.Destravado) // conferir não trava nem troca nada
        s.criarUsuaria("Secretária", Papel.SECRETARIA, "135790")
        s.travar()
        assertEquals(ResultadoPin.Ok, s.destravar(cadastro.usuarias().first { it.papel == Papel.SECRETARIA }.id, "135790"))
        assertThrows(IllegalStateException::class.java) { s.conferirPinDaDona("135790") }
    }

    // ---- Chave do aparelho com defeito (revisão de segurança, item E) ----

    // Simula o defeito: enquanto "quebrada", nem proteger nem abrir funcionam; recriar conserta (com chave nova).
    private class AparelhoComDefeito(private val real: ProtecaoKeystore) : com.assistenteclinica.seguranca.ProtecaoDoAparelho {
        var quebrada = false
        override fun proteger(dados: ByteArray) = if (quebrada) error("defeito") else real.proteger(dados)
        override fun abrir(dados: ByteArray) = if (quebrada) error("defeito") else real.abrir(dados)
        override fun recriar() { real.recriar(); quebrada = false }
    }

    @Test fun chaveComDefeitoSaiPelaSenhaDeBackupSemPerderDados() {
        val aparelho = AparelhoComDefeito(ProtecaoKeystore(alias))
        val s = SessaoDeAcesso(context, cadastro, aparelho, relogio, banco, iteracoes = 1_000)
        s.configurarPrimeiroUso("Dona", pinDona, senha)
        val sec = s.criarUsuaria("Secretária", Papel.SECRETARIA, "135790")
        runBlocking { s.banco.configuracao().salvar(Configuracao("antes_do_defeito", "continua aqui")) }
        s.travar()
        aparelho.quebrada = true
        assertEquals(ResultadoPin.FalhaNoAparelho, s.destravar(s.donaId, pinDona))
        assertEquals(ResultadoPin.Ok, s.recuperarComSenhaBackup(s.donaId, senha, "731904"))
        assertEquals("continua aqui", runBlocking { s.banco.configuracao().valor("antes_do_defeito") })
        // A secretária ficou com o embrulho da chave antiga: a dona redefine o PIN dela.
        s.travar()
        assertEquals(ResultadoPin.FalhaNoAparelho, s.destravar(sec.id, "135790"))
        assertEquals(ResultadoPin.Ok, s.destravar(s.donaId, "731904"))
        s.redefinirPin(sec.id, "579135")
        s.travar()
        assertEquals(ResultadoPin.Ok, s.destravar(sec.id, "579135"))
    }
}
