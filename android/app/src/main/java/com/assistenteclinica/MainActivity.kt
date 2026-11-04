package com.assistenteclinica

import android.Manifest
import android.util.Log
import java.time.LocalDateTime
import com.assistenteclinica.ui.backup.TelaBackup
import com.assistenteclinica.backup.prepararBackupDoDia
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.assistenteclinica.dados.bateriaConferidaEm
import com.assistenteclinica.dados.marcarBateriaConferida
import com.assistenteclinica.lembretes.Avisos
import com.assistenteclinica.ui.lembretes.FaixaDeAvisos
import com.assistenteclinica.ui.lembretes.TelaAvisos
import java.time.LocalDate
import kotlinx.coroutines.launch
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.assistenteclinica.acesso.Estado
import com.assistenteclinica.acesso.SessaoDeAcesso
import com.assistenteclinica.dominio.Detalhe
import com.assistenteclinica.dominio.Papel
import com.assistenteclinica.dominio.Usuaria
import com.assistenteclinica.ui.acesso.BotaoGrande
import com.assistenteclinica.ui.acesso.LocalAtividade
import com.assistenteclinica.ui.acesso.TelaComRolagem
import com.assistenteclinica.ui.acesso.TelaDestravar
import com.assistenteclinica.ui.acesso.TelaPrimeiroUso
import com.assistenteclinica.ui.admin.TelaAdministracao
import com.assistenteclinica.ui.admin.TelaCriancasInativas
import com.assistenteclinica.ui.admin.TelaRegistro
import com.assistenteclinica.ui.acesso.TelaUsuarias
import com.assistenteclinica.ui.agenda.TelaAgenda
import com.assistenteclinica.ui.pendencias.TelaPendencias
import com.assistenteclinica.ui.pendencias.TelaResumos
import com.assistenteclinica.ui.cadastros.TelaCriancas
import com.assistenteclinica.ui.cadastros.TelaTerapeutas
import com.assistenteclinica.ui.cadastros.TelaTerapias
import com.assistenteclinica.ui.FaixaSemBloqueioDeTela
import com.assistenteclinica.ui.TecladoSemAprendizado
import com.assistenteclinica.ui.theme.AssistenteTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val sessao get() = (application as AssistenteApp).sessao

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Bloqueia prints e esconde o conteúdo na lista de apps recentes (spec §6.2).
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        // Fora do preenchimento automático (revisão de segurança, item B): o Android não oferece "Salvar senha?"
        // para o PIN nem para a senha de backup, que iriam para a conta Google.
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        enableEdgeToEdge()
        setContent {
            AssistenteTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    CompositionLocalProvider(LocalAtividade provides sessao::registrarAtividade) {
                        TecladoSemAprendizado { App(sessao, Modifier.padding(innerPadding)) }
                    }
                }
            }
        }
    }

    // Qualquer toque conta como uso: zera o relógio da trava automática.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        sessao.registrarAtividade()
        return super.dispatchTouchEvent(ev)
    }
}

@Composable
private fun App(sessao: SessaoDeAcesso, modifier: Modifier) {
    val estado by sessao.estado.collectAsState()
    LaunchedEffect(Unit) {
        while (true) { delay(15_000); sessao.verificarTempoSemUso() }
    }
    // O aviso de celular sem bloqueio de tela aparece em todas as telas, inclusive antes do primeiro uso.
    Column(modifier) {
        FaixaSemBloqueioDeTela()
        val resto = Modifier.weight(1f)
        when (val e = estado) {
            Estado.PrimeiroUso -> TelaPrimeiroUso(sessao, resto)
            Estado.Travado -> TelaDestravar(sessao, resto)
            is Estado.Destravado -> TelaPrincipal(sessao, e.usuaria, resto)
        }
    }
}

private enum class Tela { PENDENCIAS, AGENDA, MENU, USUARIAS, CRIANCAS, TERAPIAS, TERAPEUTAS, RESUMOS, AVISOS, BACKUP, ADMINISTRACAO, REGISTRO, INATIVAS }

// Telas da dona (Bloco 8, etapa 6): o "Voltar" delas leva à Administração, não ao menu.
private val TELAS_DA_DONA = setOf(Tela.USUARIAS, Tela.BACKUP, Tela.REGISTRO, Tela.INATIVAS)

// A tela inicial é a lista do que fazer (spec §5.4); dela se vai à agenda e ao menu dos cadastros.
@Composable
private fun TelaPrincipal(sessao: SessaoDeAcesso, usuaria: Usuaria, modifier: Modifier) {
    var tela by rememberSaveable { mutableStateOf(Tela.PENDENCIAS) } // sobrevive a girar a tela
    when (tela) {
        Tela.PENDENCIAS -> Unit
        Tela.AGENDA, Tela.MENU -> BackHandler { tela = Tela.PENDENCIAS }
        in TELAS_DA_DONA -> BackHandler { tela = Tela.ADMINISTRACAO }
        else -> BackHandler { tela = Tela.MENU }
    }
    // Defesa extra: a secretária não chega a uma tela da dona nem por estado salvo antigo.
    if (usuaria.papel != Papel.DONA && (tela in TELAS_DA_DONA || tela == Tela.ADMINISTRACAO)) tela = Tela.MENU
    val voltar = { tela = Tela.MENU }
    val voltarAdmin = { tela = Tela.ADMINISTRACAO }
    val context = LocalContext.current
    val escopo = rememberCoroutineScope()
    // Relido sempre que o app volta para a frente (ela pode ter mudado nas Configurações).
    var avisos by remember { mutableStateOf(Avisos.estado(context)) }
    val ciclo = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(ciclo) {
        val observador = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) avisos = Avisos.estado(context) }
        ciclo.addObserver(observador)
        onDispose { ciclo.removeObserver(observador) }
    }
    // Bloco 8: na primeira entrada do dia, prepara o backup em segundo plano. Uma falha (por exemplo, o app travou
    // no meio) só faz tentar de novo na próxima entrada.
    LaunchedEffect(usuaria.id) {
        // Cancelamento (travou ou trocou de usuária) não é falha: segue adiante em vez de virar aviso no log.
        try { prepararBackupDoDia(context, sessao, LocalDateTime.now()) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { Log.w("Assistente", "backup do dia não preparado") }
    }
    var bateriaEm by remember { mutableStateOf<LocalDate?>(null) }
    LaunchedEffect(Unit) { bateriaEm = runCatching { sessao.banco.configuracao().bateriaConferidaEm() }.getOrNull() }
    val configuracoes: (String) -> Unit = { acao ->
        runCatching { context.startActivity(Intent(acao, Uri.parse("package:${context.packageName}"))) }
    }
    val abrirAlarmes = { configuracoes(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) }
    // Recusada de novo (ou de vez, ou Android 12), o Android não pergunta mais e o pedido volta "negado" na hora,
    // sem nada na tela: aí a tela de avisos explica e oferece as configurações. Depois de uma recusa só, o
    // Android ainda pergunta (shouldShowRequestPermissionRationale = true) e o botão continua pedindo.
    var notificacoesRecusadas by remember { mutableStateOf(false) }
    val pedirNotificacoes = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        avisos = Avisos.estado(context)
        notificacoesRecusadas = !avisos.notificacoes &&
            (context as? Activity)?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) != true
    }
    Column(modifier) {
        FaixaDeAvisos(avisos, aoArrumarNotificacoes = { tela = Tela.AVISOS }, aoPermitirAlarmes = abrirAlarmes)
        val modifier = Modifier.weight(1f) // a faixa fica em cima; cada tela ocupa o resto
        when (tela) {
            Tela.PENDENCIAS -> TelaPendencias(sessao, { tela = Tela.AGENDA }, { tela = Tela.MENU }, modifier)
            Tela.AGENDA -> TelaAgenda(sessao, { tela = Tela.MENU }, modifier)
            Tela.USUARIAS -> TelaUsuarias(sessao, voltarAdmin, modifier)
            Tela.CRIANCAS -> TelaCriancas(sessao, voltar, modifier)
            Tela.TERAPIAS -> TelaTerapias(sessao, voltar, modifier)
            Tela.TERAPEUTAS -> TelaTerapeutas(sessao, voltar, modifier)
            Tela.RESUMOS -> TelaResumos(sessao, voltar, modifier)
            Tela.BACKUP -> TelaBackup(sessao, voltarAdmin, modifier)
            Tela.REGISTRO -> TelaRegistro(sessao, voltarAdmin, modifier)
            Tela.INATIVAS -> TelaCriancasInativas(sessao, voltarAdmin, modifier)
            Tela.ADMINISTRACAO -> TelaAdministracao(
                aoUsuarias = { tela = Tela.USUARIAS }, aoRegistro = { tela = Tela.REGISTRO },
                aoBackup = { tela = Tela.BACKUP }, aoInativas = { tela = Tela.INATIVAS }, aoVoltar = voltar, modifier = modifier,
            )
            Tela.AVISOS -> TelaAvisos(
                avisos, Build.MANUFACTURER, bateriaEm, notificacoesRecusadas,
                aoPedirNotificacoes = { pedirNotificacoes.launch(Manifest.permission.POST_NOTIFICATIONS) },
                aoAbrirNotificacoes = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }
                },
                aoAbrirAlarmes = abrirAlarmes,
                aoAbrirBateria = { configuracoes(Settings.ACTION_APPLICATION_DETAILS_SETTINGS) },
                aoMarcarBateria = {
                    val hoje = LocalDate.now()
                    escopo.launch { if (runCatching { sessao.banco.configuracao().marcarBateriaConferida(hoje) }.isSuccess) bateriaEm = hoje }
                },
                aoVoltar = voltar, modifier = modifier,
            )
            Tela.MENU -> TelaComRolagem("Olá, ${usuaria.nome}!", modifier) {
                BotaoGrande("O que fazer", { tela = Tela.PENDENCIAS })
                BotaoGrande("Agenda", { tela = Tela.AGENDA })
                BotaoGrande("Crianças", { tela = Tela.CRIANCAS })
                BotaoGrande("Terapeutas", { tela = Tela.TERAPEUTAS })
                BotaoGrande("Terapias", { tela = Tela.TERAPIAS })
                BotaoGrande("Resumos do mês", { tela = Tela.RESUMOS })
                BotaoGrande("Avisos do celular", { tela = Tela.AVISOS })
                if (usuaria.papel == Papel.DONA) BotaoGrande("Administração", { tela = Tela.ADMINISTRACAO })
                BotaoGrande("Travar agora", { sessao.travar(Detalhe.NO_BOTAO) })
            }
        }
    }
}
