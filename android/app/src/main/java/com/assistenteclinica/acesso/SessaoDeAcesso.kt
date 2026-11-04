package com.assistenteclinica.acesso

import android.content.Context
import com.assistenteclinica.backup.DestinoEscolhido
import com.assistenteclinica.dados.BancoAssistente
import com.assistenteclinica.dados.CadastroDeAcesso
import com.assistenteclinica.dados.LinhaRegistro
import com.assistenteclinica.dados.NOME_DO_BANCO
import com.assistenteclinica.dados.VERSAO_DO_BANCO
import com.assistenteclinica.dados.conferenciaDoArquivo
import com.assistenteclinica.dados.abrirBanco
import com.assistenteclinica.dados.copiarBanco
import com.assistenteclinica.dominio.Acao
import com.assistenteclinica.dominio.Alvo
import com.assistenteclinica.dominio.Detalhe
import com.assistenteclinica.dominio.Conferencia
import com.assistenteclinica.dominio.EXTENSAO_DO_BACKUP
import com.assistenteclinica.dominio.dataComoNumero
import com.assistenteclinica.dominio.donasParaRestaurar
import com.assistenteclinica.dominio.Papel
import com.assistenteclinica.dominio.Usuaria
import com.assistenteclinica.dominio.esperaAposErros
import com.assistenteclinica.dominio.marcaDeSaidaValida
import com.assistenteclinica.dominio.nomeDoArquivoDeBackup
import com.assistenteclinica.dominio.pinValido
import com.assistenteclinica.dominio.podeDesativar
import com.assistenteclinica.dominio.podeTentarPin
import com.assistenteclinica.dominio.senhaBackupValida
import com.assistenteclinica.dominio.travouPorTempo
import com.assistenteclinica.dominio.voltaSemPin
import com.assistenteclinica.seguranca.AberturaDoBackup
import com.assistenteclinica.seguranca.ArquivoDeBackup
import com.assistenteclinica.seguranca.Chaves
import com.assistenteclinica.seguranca.ConteudoDoBackup
import com.assistenteclinica.seguranca.Embrulho
import com.assistenteclinica.seguranca.FalhaDoAparelho
import com.assistenteclinica.seguranca.ProtecaoDoAparelho
import android.util.Log
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface Estado {
    data object PrimeiroUso : Estado
    data object Travado : Estado
    data class Destravado(val usuaria: Usuaria) : Estado
}

sealed interface ResultadoPin {
    data object Ok : ResultadoPin
    data object PinErrado : ResultadoPin
    data class Espere(val ate: Instant) : ResultadoPin
    data object Inativa : ResultadoPin
    data object FalhaNoAparelho : ResultadoPin // a proteção do aparelho não abre: só a senha de backup resolve
    data object SemPin : ResultadoPin // veio de um backup restaurado: a dona cria o PIN (Redefinir PIN ou Esqueci meu PIN)
}

// Restaurar (Bloco 8, etapa 5), primeiro passo: abrir o arquivo com a senha e conferir o banco antes de gravar algo.
sealed interface AberturaParaRestaurar {
    // conferenciaDoArquivo: a gravada no backup; codigoRestaurado: recalculado no banco que vai ser restaurado.
    class Pronta(val criadoEm: LocalDateTime, val donas: List<Usuaria>, val conferenciaDoArquivo: Conferencia, val codigoRestaurado: String) : AberturaParaRestaurar
    data object NaoEBackup : AberturaParaRestaurar
    data object SenhaErrada : AberturaParaRestaurar
    data class Espere(val ate: Instant) : AberturaParaRestaurar
    data object Danificado : AberturaParaRestaurar
    data class VersaoMaisNova(val versao: Int) : AberturaParaRestaurar
    data object ConferenciaDiferente : AberturaParaRestaurar // abriu, mas o banco não é o que foi copiado
    data object Interrompida : AberturaParaRestaurar // o app saiu da tela no meio: começar de novo
}

// O que fica na memória entre abrir o arquivo e a dona criar o PIN. A senha não fica: os embrulhos que dependem
// dela já saem prontos do primeiro passo.
private class RestauracaoPendente(
    val chave: ByteArray, // a do banco restaurado: vira a chave principal deste celular
    val usuarias: List<Usuaria>,
    val criadoEm: LocalDateTime,
    val embrulhoBackup: Embrulho,
    val chaveDeBackupPelaPrincipal: ByteArray,
    val chaveDeBackupPelaSenha: Embrulho,
    val errosDaSenha: Int,
)

// Quanto o banco pode esperar o recálculo dos lembretes antes de fechar (decidido pelo usuário em 2026-10-28).
val LIMITE_DO_RECALCULO: Duration = Duration.ofSeconds(2)

// Erros da senha de backup contam à parte, num id que nenhuma usuária usa (elas começam em 1).
private const val ID_BACKUP = 0L

// A chave principal só existe na memória enquanto alguém está destravado. Se o Android fechar o app,
// a próxima instância nasce travada. destravar/criar/redefinir/recuperar fazem PBKDF2: chamar fora da thread da tela.
class SessaoDeAcesso(
    private val context: Context,
    private val cadastro: CadastroDeAcesso,
    private val aparelho: ProtecaoDoAparelho,
    private val relogio: Clock = Clock.systemDefaultZone(),
    private val nomeBanco: String = NOME_DO_BANCO,
    private val iteracoes: Int = Chaves.ITERACOES,
    // Recalcula os lembretes (Bloco 7) com o banco ainda aberto, fora da thread da tela. O banco só fecha
    // quando ele termina ou quando estoura LIMITE_DO_RECALCULO. Uma falha aqui nunca impede o app de travar.
    private val recalcularLembretes: suspend (BancoAssistente) -> Unit = {},
    // Chamado quando o recálculo estoura o limite ou dá erro: a lista antiga passa a valer como desatualizada.
    private val aoRecalculoIncompleto: () -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val escopo = CoroutineScope(SupervisorJob() + io)
    private var fechando: Job? = null
    // O recálculo em andamento (saída pelo WhatsApp ou trava): nunca rodam dois juntos no mesmo banco.
    @Volatile private var recalculando: Job? = null

    // Última vez que o recálculo não terminou a tempo (a lista de lembretes ficou a de antes).
    @Volatile var recalculoIncompletoEm: Instant? = null
        private set

    private val _estado = MutableStateFlow(if (cadastro.primeiroUso()) Estado.PrimeiroUso else Estado.Travado)
    val estado: StateFlow<Estado> = _estado.asStateFlow()

    private var chave: ByteArray? = null
    private var pendente: RestauracaoPendente? = null
    // Muda a cada cancelamento: um primeiro passo que estava rodando quando o app saiu da tela não guarda nada.
    private var geracaoDaRestauracao = 0
    private val bancoRestaurando = "restaurando_$nomeBanco"
    private var _banco: BancoAssistente? = null
    @Volatile private var ultimaAtividade: Instant = relogio.instant()
    @Volatile private var vaiSairPeloWhatsAppEm: Instant? = null
    @Volatile private var saiuPeloWhatsAppEm: Instant? = null

    val banco: BancoAssistente get() = checkNotNull(_banco) { "app travado" }

    fun usuarias(): List<Usuaria> = cadastro.usuarias()

    fun temPin(usuariaId: Long): Boolean = cadastro.embrulhoDe(usuariaId) != null

    @Synchronized
    fun configurarPrimeiroUso(nomeDona: String, pin: String, senhaBackup: String) {
        check(cadastro.primeiroUso()) { "o app já foi configurado" }
        require(pinValido(pin)) { "PIN inválido" }
        require(senhaBackupValida(senhaBackup)) { "senha de backup curta" }
        val nova = Chaves.novaChavePrincipal()
        val dona = Usuaria(1, nomeDona, Papel.DONA)
        // Embrulhos antes da usuária: se o app morrer no meio, ele continua em "primeiro uso".
        cadastro.salvarEmbrulho(dona.id, Chaves.embrulharComPin(nova, pin, aparelho, iteracoes))
        cadastro.salvarEmbrulhoBackup(Chaves.embrulharComSenha(nova, senhaBackup, iteracoes))
        criarChaveDeBackup(nova, senhaBackup)
        cadastro.salvarUsuaria(dona)
        entrar(dona, nova, Acao.CONFIGUROU_O_APP)
    }

    @Synchronized
    fun destravar(usuariaId: Long, pin: String): ResultadoPin {
        val u = cadastro.usuarias().firstOrNull { it.id == usuariaId } ?: return ResultadoPin.PinErrado
        if (!u.ativa) return ResultadoPin.Inativa
        val agora = relogio.instant()
        val erros = cadastro.erros(u.id)
        val ultimoErro = cadastro.ultimoErro(u.id)
        if (!podeTentarPin(erros, ultimoErro, agora)) return ResultadoPin.Espere(ultimoErro!!.plus(esperaAposErros(erros)))
        val embrulho = cadastro.embrulhoDe(u.id) ?: return ResultadoPin.SemPin
        val aberta = try { Chaves.abrirComPin(embrulho, pin, aparelho) } catch (_: FalhaDoAparelho) { return ResultadoPin.FalhaNoAparelho }
        if (aberta == null) {
            cadastro.registrarErro(u.id, agora)
            return ResultadoPin.PinErrado
        }
        entrar(u, aberta, Acao.ENTROU, errosDePin = erros)
        cadastro.zerarErros(u.id)
        return ResultadoPin.Ok
    }

    // A tela trava e a chave some da memória na hora; o banco fecha em segundo plano, depois do recálculo
    // dos lembretes (ou do limite de 2 s). Nada disso segura a thread da tela.
    // Se o recálculo da saída pelo WhatsApp ainda está rodando, ele é o desta trava: o banco espera ele e fecha
    // (ela estava fora do app, nada mudou desde que ele começou).
    // motivo: vai para o registro de ações (a saída é gravada com o banco ainda aberto, antes do recálculo).
    @Synchronized
    fun travar(motivo: Detalhe = Detalhe.NO_BOTAO) {
        val b = _banco
        _banco = null
        if (b != null) {
            val emAndamento = recalculando?.takeIf { it.isActive }
            val saida = linha(b, Acao.SAIU, detalhe = motivo)
            fechando = escopo.launch {
                runCatching { b.registro().inserir(saida) }.onFailure { Log.w("Assistente", "não consegui registrar a saída") }
                if (emAndamento != null) emAndamento.join() else recalcularComLimite(b)
                b.close()
            }.also { if (emAndamento == null) recalculando = it }
        }
        fecharTudo()
    }

    // O recálculo roda num trabalho à parte: se ele travar numa consulta, o limite estoura mesmo assim, e ele é
    // cancelado para não gravar depois uma lista velha. Erro no recálculo também deixa a lista desatualizada.
    private suspend fun recalcularComLimite(b: BancoAssistente): Boolean {
        val trabalho = escopo.async { runCatching { recalcularLembretes(b) } }
        val resultado = withTimeoutOrNull(LIMITE_DO_RECALCULO.toMillis()) { trabalho.await() }
        if (resultado == null) {
            trabalho.cancel()
            recalculoIncompletoEm = relogio.instant()
            Log.w("Assistente", "recálculo dos lembretes não terminou em ${LIMITE_DO_RECALCULO.seconds} s; app travado mesmo assim")
        }
        val terminou = resultado?.isSuccess == true
        if (!terminou) runCatching(aoRecalculoIncompleto)
        return terminou
    }

    // Só para testes: espera o recálculo e o banco da última trava fechar.
    internal suspend fun esperarFechar() { recalculando?.join(); fechando?.join() }

    @Synchronized
    private fun fecharTudo() {
        cancelarRestauracao() // saiu do app no meio da restauração: a chave não fica na memória
        DestinoEscolhido.limpar() // o app escolhido para o backup não pode fechar a pendência em nome de quem entrar depois
        _banco = null
        chave?.fill(0)
        chave = null
        vaiSairPeloWhatsAppEm = null
        saiuPeloWhatsAppEm = null
        if (_estado.value is Estado.Destravado) _estado.value = Estado.Travado
    }

    fun registrarAtividade() { ultimaAtividade = relogio.instant() }

    fun verificarTempoSemUso() {
        if (_estado.value is Estado.Destravado && travouPorTempo(ultimaAtividade, relogio.instant())) travar(Detalhe.POR_TEMPO)
    }

    // Vale para uma saída só, e só se ela vier em até 10 s (VALIDADE_MARCA_DE_SAIDA).
    fun marcarSaidaPeloWhatsApp() { vaiSairPeloWhatsAppEm = relogio.instant() }

    // Chamar quando a outra tela devolve o resultado: se ela era translúcida, o app nem saiu e a marca sobraria.
    fun desmarcarSaidaPeloWhatsApp() { vaiSairPeloWhatsAppEm = null }

    // Saída comum trava na hora: a chave não fica na memória com o app em segundo plano.
    // Toda saída recalcula os lembretes antes (inclusive a do WhatsApp, que não trava).
    @Synchronized
    fun aoSairDaTela() {
        val agora = relogio.instant()
        if (marcaDeSaidaValida(vaiSairPeloWhatsAppEm, agora)) {
            saiuPeloWhatsAppEm = agora
            // Não trava: o banco continua aberto. Fica guardado em recalculando para a próxima trava esperar.
            _banco?.let { b -> if (recalculando?.isActive != true) recalculando = escopo.launch { recalcularComLimite(b) } }
        } else travar(Detalhe.AO_SAIR_DA_TELA)
        vaiSairPeloWhatsAppEm = null
    }

    fun aoVoltarATela() {
        val agora = relogio.instant()
        if (voltaSemPin(saiuPeloWhatsAppEm, agora)) registrarAtividade() else travar(Detalhe.AO_SAIR_DA_TELA)
        saiuPeloWhatsAppEm = null
    }

    @Synchronized
    fun criarUsuaria(nome: String, papel: Papel, pin: String): Usuaria {
        val k = chaveDaDona()
        require(pinValido(pin)) { "PIN inválido" }
        val u = Usuaria((cadastro.usuarias().maxOfOrNull { it.id } ?: 0) + 1, nome, papel)
        cadastro.salvarEmbrulho(u.id, Chaves.embrulharComPin(k, pin, aparelho, iteracoes))
        cadastro.salvarUsuaria(u)
        registrarAgora(Acao.CRIOU, Alvo.USUARIA, u.id)
        return u
    }

    @Synchronized
    fun redefinirPin(usuariaId: Long, novoPin: String) {
        val k = chaveDaDona()
        require(pinValido(novoPin)) { "PIN inválido" }
        require(cadastro.usuarias().any { it.id == usuariaId && it.ativa }) { "usuária não existe ou está desativada" }
        cadastro.salvarEmbrulho(usuariaId, Chaves.embrulharComPin(k, novoPin, aparelho, iteracoes))
        cadastro.zerarErros(usuariaId)
        registrarAgora(Acao.REDEFINIU_PIN, Alvo.USUARIA, usuariaId)
    }

    @Synchronized
    fun desativar(usuariaId: Long) {
        chaveDaDona()
        val usuarias = cadastro.usuarias()
        val alvo = requireNotNull(usuarias.firstOrNull { it.id == usuariaId }) { "usuária não existe" }
        check(podeDesativar(alvo, usuarias)) { "não dá para desativar a única dona" }
        cadastro.salvarUsuaria(alvo.copy(ativa = false))
        cadastro.removerEmbrulho(alvo.id) // o PIN antigo não abre mais nada, nem com os arquivos e o aparelho em mãos
        registrarAgora(Acao.DESATIVOU, Alvo.USUARIA, alvo.id)
        if ((_estado.value as Estado.Destravado).usuaria.id == alvo.id) travar(Detalhe.USUARIA_DESATIVADA)
    }

    // Para a dona que esqueceu o PIN, com o app travado. Quem sabe a senha de backup já pode tudo, então entra direto.
    // Os erros contam como os do PIN (mesma espera), para a senha não poder ser chutada sem limite.
    @Synchronized
    fun recuperarComSenhaBackup(donaId: Long, senha: String, novoPin: String): ResultadoPin {
        require(pinValido(novoPin)) { "PIN inválido" }
        val dona = cadastro.usuarias().firstOrNull { it.id == donaId && it.papel == Papel.DONA && it.ativa }
            ?: return ResultadoPin.Inativa
        val agora = relogio.instant()
        val erros = cadastro.erros(ID_BACKUP)
        val ultimoErro = cadastro.ultimoErro(ID_BACKUP)
        if (!podeTentarPin(erros, ultimoErro, agora)) return ResultadoPin.Espere(ultimoErro!!.plus(esperaAposErros(erros)))
        val aberta = cadastro.embrulhoBackup()?.let { Chaves.abrirComSenha(it, senha) }
        if (aberta == null) {
            cadastro.registrarErro(ID_BACKUP, agora)
            return ResultadoPin.PinErrado
        }
        // Aparelho novo ou chave do Keystore perdida: o embrulho é refeito com a proteção atual do aparelho. Se nem
        // proteger funciona (chave com defeito), ela é recriada; as secretárias recebem PIN novo pela dona.
        val embrulho = try {
            Chaves.embrulharComPin(aberta, novoPin, aparelho, iteracoes)
        } catch (_: Exception) {
            aparelho.recriar()
            Chaves.embrulharComPin(aberta, novoPin, aparelho, iteracoes)
        }
        cadastro.salvarEmbrulho(dona.id, embrulho)
        travar()
        entrar(dona, aberta, Acao.ENTROU_PELA_SENHA_DE_BACKUP, errosDePin = cadastro.erros(dona.id), errosDaSenha = erros)
        cadastro.zerarErros(ID_BACKUP)
        cadastro.zerarErros(dona.id)
        return ResultadoPin.Ok
    }

    // Antes de apagar dados a pedido (LGPD, etapa 7): a dona digita o PIN de novo. Erros contam como os da entrada.
    @Synchronized
    fun conferirPinDaDona(pin: String): ResultadoPin {
        chaveDaDona()
        val u = (_estado.value as Estado.Destravado).usuaria
        val agora = relogio.instant()
        val erros = cadastro.erros(u.id)
        val ultimoErro = cadastro.ultimoErro(u.id)
        if (!podeTentarPin(erros, ultimoErro, agora)) return ResultadoPin.Espere(ultimoErro!!.plus(esperaAposErros(erros)))
        val embrulho = cadastro.embrulhoDe(u.id) ?: return ResultadoPin.SemPin
        val aberta = try { Chaves.abrirComPin(embrulho, pin, aparelho) } catch (_: FalhaDoAparelho) { return ResultadoPin.FalhaNoAparelho }
        if (aberta == null) {
            cadastro.registrarErro(u.id, agora)
            return ResultadoPin.PinErrado
        }
        aberta.fill(0)
        cadastro.zerarErros(u.id)
        return ResultadoPin.Ok
    }

    // ---- Backup (Bloco 8, etapa 4) ----

    fun temChaveDeBackup(): Boolean = cadastro.chaveDeBackupPelaPrincipal() != null && cadastro.chaveDeBackupPelaSenha() != null

    // Para um app que já existia antes do Bloco 8 (sem chave de backup): a dona digita a senha de backup uma vez.
    // A senha é conferida contra o embrulho da chave principal; os erros contam como os da senha no "Esqueci meu PIN".
    @Synchronized
    fun configurarChaveDeBackup(senha: String): ResultadoPin {
        val k = chaveDaDona()
        val agora = relogio.instant()
        val erros = cadastro.erros(ID_BACKUP)
        val ultimoErro = cadastro.ultimoErro(ID_BACKUP)
        if (!podeTentarPin(erros, ultimoErro, agora)) return ResultadoPin.Espere(ultimoErro!!.plus(esperaAposErros(erros)))
        val aberta = cadastro.embrulhoBackup()?.let { Chaves.abrirComSenha(it, senha) }
        if (aberta == null || !aberta.contentEquals(k)) {
            aberta?.fill(0)
            cadastro.registrarErro(ID_BACKUP, agora)
            return ResultadoPin.PinErrado
        }
        aberta.fill(0)
        criarChaveDeBackup(k, senha)
        cadastro.zerarErros(ID_BACKUP)
        registrarAgora(Acao.CONFIGUROU_O_BACKUP)
        return ResultadoPin.Ok
    }

    // Só a dona, com a senha atual. A chave de backup também é trocada: com a mesma chave, quem soubesse a senha
    // antiga e tivesse um arquivo antigo abriria também os backups novos. Os arquivos antigos continuam abrindo só
    // com a senha antiga (o embrulho vai dentro de cada um). Erros contam como os da senha no "Esqueci meu PIN".
    @Synchronized
    fun trocarSenhaDeBackup(atual: String, nova: String): ResultadoPin {
        val k = chaveDaDona()
        require(senhaBackupValida(nova)) { "senha de backup curta" }
        val agora = relogio.instant()
        val erros = cadastro.erros(ID_BACKUP)
        val ultimoErro = cadastro.ultimoErro(ID_BACKUP)
        if (!podeTentarPin(erros, ultimoErro, agora)) return ResultadoPin.Espere(ultimoErro!!.plus(esperaAposErros(erros)))
        val aberta = cadastro.embrulhoBackup()?.let { Chaves.abrirComSenha(it, atual) }
        if (aberta == null || !aberta.contentEquals(k)) {
            aberta?.fill(0)
            cadastro.registrarErro(ID_BACKUP, agora)
            return ResultadoPin.PinErrado
        }
        aberta.fill(0)
        val chaveDeBackup = Chaves.novaChavePrincipal()
        try {
            cadastro.salvarSenhaDeBackup(
                Chaves.embrulharComSenha(k, nova, iteracoes),
                Chaves.cifrarComChave(k, chaveDeBackup), Chaves.embrulharComSenha(chaveDeBackup, nova, iteracoes),
            )
        } finally {
            chaveDeBackup.fill(0)
        }
        cadastro.zerarErros(ID_BACKUP)
        registrarAgora(Acao.TROCOU_A_SENHA_DE_BACKUP)
        return ResultadoPin.Ok
    }

    private fun criarChaveDeBackup(principal: ByteArray, senha: String) {
        val chaveDeBackup = Chaves.novaChavePrincipal()
        try {
            cadastro.salvarChaveDeBackup(Chaves.cifrarComChave(principal, chaveDeBackup), Chaves.embrulharComSenha(chaveDeBackup, senha, iteracoes))
        } finally {
            chaveDeBackup.fill(0)
        }
    }

    // Prepara o arquivo do dia na pasta pasta (a privada do app) e apaga os anteriores: só o mais recente fica no
    // celular. Grava primeiro com outro nome e depois troca, para nunca sobrar um arquivo pela metade. Lento (cópia
    // e cifra): chamar fora da thread da tela. Devolve o arquivo e a conferência gravada nele.
    fun prepararArquivoDeBackup(pasta: File, agora: LocalDateTime): Pair<File, com.assistenteclinica.dominio.Conferencia> {
        val (k, pelaPrincipal, pelaSenha) = synchronized(this) {
            check(_estado.value is Estado.Destravado) { "app travado" }
            Triple(checkNotNull(chave).copyOf(), checkNotNull(cadastro.chaveDeBackupPelaPrincipal()) { "backup não configurado" },
                checkNotNull(cadastro.chaveDeBackupPelaSenha()) { "backup não configurado" })
        }
        val chaveDeBackup = Chaves.decifrarComChave(k, pelaPrincipal)
        try {
            checkNotNull(chaveDeBackup) { "a chave de backup não abriu" }
            val copia = copiarBanco(context, k, nomeBanco)
            val bytes = try {
                ArquivoDeBackup.montar(
                    ConteudoDoBackup(copia.versao, agora, cadastro.usuarias(), copia.conferencia, copia.chave, copia.bytes),
                    chaveDeBackup, pelaSenha,
                )
            } finally {
                copia.chave.fill(0)
            }
            pasta.mkdirs()
            val destino = File(pasta, nomeDoArquivoDeBackup(agora.toLocalDate()))
            val provisorio = File(pasta, destino.name + ".parcial")
            provisorio.writeBytes(bytes)
            check(provisorio.renameTo(destino)) { "não consegui gravar o backup" }
            pasta.listFiles()?.filter { it != destino && (it.name.endsWith(EXTENSAO_DO_BACKUP) || it.name.endsWith(".parcial")) }?.forEach { it.delete() }
            return destino to copia.conferencia
        } finally {
            k.fill(0)
            chaveDeBackup?.fill(0)
        }
    }

    // ---- Restaurar (Bloco 8, etapa 5) ----

    // Só num app vazio (decidido pelo usuário em 2026-10-29). Abre o arquivo, grava o banco num arquivo à parte e
    // confere o código ANTES de qualquer migração. Nada vai para o cadastro de acesso aqui: se ela desistir ou o app
    // morrer, continua tudo em "primeiro uso". Senha errada conta como no "Esqueci meu PIN" (mesma espera).
    // Lento (PBKDF2 três vezes): chamar fora da thread da tela. A parte lenta roda SEM a trava da sessão: sair do
    // app (travar, na thread da tela) não fica esperando por ela; o resultado é jogado fora se o app saiu no meio.
    fun abrirBackupParaRestaurar(arquivo: ByteArray, senha: String): AberturaParaRestaurar {
        val agora = relogio.instant()
        val (geracao, erros) = synchronized(this) {
            check(cadastro.primeiroUso()) { "só dá para restaurar num app vazio" }
            cancelarRestauracao()
            val erros = cadastro.erros(ID_BACKUP)
            val ultimoErro = cadastro.ultimoErro(ID_BACKUP)
            if (!podeTentarPin(erros, ultimoErro, agora)) return AberturaParaRestaurar.Espere(ultimoErro!!.plus(esperaAposErros(erros)))
            geracaoDaRestauracao to erros
        }
        val c = when (val r = ArquivoDeBackup.abrir(arquivo, senha, VERSAO_DO_BANCO)) {
            is AberturaDoBackup.Ok -> r.conteudo
            AberturaDoBackup.SenhaErrada -> { synchronized(this) { cadastro.registrarErro(ID_BACKUP, agora) }; return AberturaParaRestaurar.SenhaErrada }
            AberturaDoBackup.NaoEBackup -> return AberturaParaRestaurar.NaoEBackup
            AberturaDoBackup.Danificado -> return AberturaParaRestaurar.Danificado
            is AberturaDoBackup.VersaoMaisNova -> return AberturaParaRestaurar.VersaoMaisNova(r.versao)
        }
        try {
            val donas = donasParaRestaurar(c.usuarias)
            if (donas.isEmpty()) return AberturaParaRestaurar.Danificado // sem dona ativa ninguém entraria
            val arq = context.getDatabasePath(bancoRestaurando)
            context.deleteDatabase(bancoRestaurando)
            arq.parentFile?.mkdirs()
            arq.writeBytes(c.banco)
            val conferida = runCatching { conferenciaDoArquivo(context, bancoRestaurando, c.chaveDoBanco) }.getOrNull()
            if (conferida == null || conferida != c.conferencia) {
                context.deleteDatabase(bancoRestaurando)
                return if (conferida == null) AberturaParaRestaurar.Danificado else AberturaParaRestaurar.ConferenciaDiferente
            }
            // O celular novo já começa com uma chave de backup nova (plano, parte 2).
            val chaveDeBackup = Chaves.novaChavePrincipal()
            val nova = try {
                RestauracaoPendente(
                    c.chaveDoBanco.copyOf(), c.usuarias, c.criadoEm,
                    Chaves.embrulharComSenha(c.chaveDoBanco, senha, iteracoes),
                    Chaves.cifrarComChave(c.chaveDoBanco, chaveDeBackup),
                    Chaves.embrulharComSenha(chaveDeBackup, senha, iteracoes),
                    erros,
                )
            } finally {
                chaveDeBackup.fill(0)
            }
            synchronized(this) {
                if (geracao != geracaoDaRestauracao || !cadastro.primeiroUso()) {
                    nova.chave.fill(0)
                    context.deleteDatabase(bancoRestaurando)
                    return AberturaParaRestaurar.Interrompida
                }
                pendente = nova
            }
            return AberturaParaRestaurar.Pronta(c.criadoEm, donas, c.conferencia, conferida.codigo)
        } finally {
            c.chaveDoBanco.fill(0)
        }
    }

    // Segundo passo: a dona escolhida cria o PIN novo e entra. As outras ficam sem PIN (a dona redefine).
    // Ordem: embrulhos e banco antes das usuárias. Se o app morrer no meio, ele continua em "primeiro uso".
    @Synchronized
    fun concluirRestauracao(donaId: Long, pin: String) {
        val p = checkNotNull(pendente) { "nada para restaurar: abra o arquivo de novo" }
        check(cadastro.primeiroUso()) { "só dá para restaurar num app vazio" }
        require(pinValido(pin)) { "PIN inválido" }
        val dona = requireNotNull(donasParaRestaurar(p.usuarias).firstOrNull { it.id == donaId }) { "não é uma dona ativa do backup" }
        // Sobras de uma restauração que morreu no meio (outro arquivo, outra dona): não podem abrir nada aqui.
        p.usuarias.filter { it.id != dona.id }.forEach { cadastro.removerEmbrulho(it.id) }
        cadastro.salvarEmbrulho(dona.id, Chaves.embrulharComPin(p.chave, pin, aparelho, iteracoes))
        cadastro.salvarEmbrulhoBackup(p.embrulhoBackup)
        cadastro.salvarChaveDeBackup(p.chaveDeBackupPelaPrincipal, p.chaveDeBackupPelaSenha)
        // Um banco que já esteja aqui com o app em "primeiro uso" é sobra da mesma situação: sem usuárias, ninguém o abre.
        context.deleteDatabase(nomeBanco)
        check(context.getDatabasePath(bancoRestaurando).renameTo(context.getDatabasePath(nomeBanco))) { "não consegui gravar o banco" }
        context.deleteDatabase(bancoRestaurando) // arquivos auxiliares que tenham sobrado
        cadastro.salvarUsuarias(p.usuarias)
        pendente = null
        // Um backup de versão mais antiga é atualizado aqui, ao abrir (o código já foi conferido antes).
        entrar(dona, p.chave, Acao.ENTROU_PELA_SENHA_DE_BACKUP, errosDaSenha = p.errosDaSenha)
        registrarAgora(Acao.RESTAUROU_O_BACKUP, quantidade = dataComoNumero(p.criadoEm.toLocalDate()))
        cadastro.zerarErros(ID_BACKUP)
    }

    @Synchronized
    fun cancelarRestauracao() {
        geracaoDaRestauracao++
        val p = pendente ?: return
        pendente = null
        p.chave.fill(0)
        p.chaveDeBackupPelaPrincipal.fill(0)
        context.deleteDatabase(bancoRestaurando)
    }

    // Abre o banco antes de guardar a chave: se falhar, a chave é apagada e nada fica pela metade.
    // Os erros de PIN (e da senha de backup) aconteceram com o banco cifrado: só agora dá para registrá-los.
    private fun entrar(u: Usuaria, k: ByteArray, como: Acao, errosDePin: Int = 0, errosDaSenha: Int = 0) {
        val b = try { abrirBanco(context, k, nomeBanco) } catch (e: Throwable) { k.fill(0); throw e }
        b.usuariaAtual = u.id
        b.relogio = relogio
        _banco = b
        chave = k
        registrarAgora(como)
        if (errosDePin > 0) registrarAgora(Acao.ERROS_DE_PIN, Alvo.USUARIA, u.id, quantidade = errosDePin)
        if (errosDaSenha > 0) registrarAgora(Acao.ERROS_DE_PIN, Alvo.USUARIA, u.id, Detalhe.SENHA_DE_BACKUP, errosDaSenha)
        registrarAtividade()
        _estado.value = Estado.Destravado(u)
    }

    private fun linha(b: BancoAssistente, acao: Acao, alvo: Alvo? = null, alvoId: Long? = null, detalhe: Detalhe? = null, quantidade: Int? = null) =
        LinhaRegistro(0, LocalDateTime.now(relogio), b.usuariaAtual, acao, alvo, alvoId, null, detalhe, quantidade)

    // Ações fora do banco (acesso e usuárias): gravadas na hora, numa thread de fundo (o Room recusa a da tela).
    // Uma falha aqui não impede de entrar nem de cuidar das usuárias: fica só o aviso no log, sem nomes.
    private fun registrarAgora(acao: Acao, alvo: Alvo? = null, alvoId: Long? = null, detalhe: Detalhe? = null, quantidade: Int? = null) {
        val b = _banco ?: return
        runCatching { runBlocking(io) { b.registro().inserir(linha(b, acao, alvo, alvoId, detalhe, quantidade)) } }
            .onFailure { Log.w("Assistente", "não consegui registrar a ação ${acao.name}") }
    }

    private fun chaveDaDona(): ByteArray {
        val e = _estado.value
        check(e is Estado.Destravado && e.usuaria.papel == Papel.DONA) { "só a dona pode fazer isso" }
        return checkNotNull(chave)
    }
}
