package com.assistenteclinica.ui.backup

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.assistenteclinica.acesso.ResultadoPin
import com.assistenteclinica.acesso.SessaoDeAcesso
import com.assistenteclinica.backup.apagarArquivoDeBackup
import com.assistenteclinica.backup.compartilharBackup
import com.assistenteclinica.backup.prepararBackupDoDia
import com.assistenteclinica.backup.registrarDestinoEscolhido
import com.assistenteclinica.dados.UltimoBackup
import com.assistenteclinica.dados.ultimoBackup
import com.assistenteclinica.dominio.dataBonita
import com.assistenteclinica.dominio.quantidadesEmTexto
import com.assistenteclinica.dominio.problemaDaSenhaBackup
import com.assistenteclinica.ui.acesso.BotaoGrande
import com.assistenteclinica.ui.acesso.CampoSenha
import com.assistenteclinica.ui.acesso.MensagemErro
import com.assistenteclinica.ui.acesso.TelaComRolagem
import com.assistenteclinica.ui.agenda.HORA
import com.assistenteclinica.ui.cadastros.MensagemOk
import com.assistenteclinica.ui.cadastros.tentar
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Abre a tela de compartilhar e, quando o app volta para a frente, fecha a pendência se ela escolheu um app.
// aoSalvar recebe a mensagem para mostrar. Também desfaz a marca de saída (a tela de compartilhar pode só pausar o app).
@Composable
fun rememberSalvarBackup(sessao: SessaoDeAcesso, aoSalvar: (String) -> Unit, aoFalhar: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val escopo = rememberCoroutineScope()
    val salvo by rememberUpdatedState(aoSalvar)
    val ciclo = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(ciclo) {
        val observador = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                sessao.desmarcarSaidaPeloWhatsApp()
                escopo.launch { if (runCatching { registrarDestinoEscolhido(sessao, LocalDateTime.now()) }.getOrDefault(false)) salvo("Backup salvo.") }
            }
        }
        ciclo.addObserver(observador)
        onDispose { ciclo.removeObserver(observador) }
    }
    return { if (!compartilharBackup(context, sessao)) aoFalhar("Não há backup pronto para salvar.") }
}

// Bloco 8, etapas 4 e 6: tela de backup, só para a dona. Configura a chave de backup num app que já existia, mostra
// o último backup, faz um agora e troca a senha de backup.
@Composable
fun TelaBackup(sessao: SessaoDeAcesso, aoVoltar: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val escopo = rememberCoroutineScope()
    var versao by remember { mutableIntStateOf(0) }
    var configurado by remember { mutableStateOf(sessao.temChaveDeBackup()) }
    var ultimo by remember { mutableStateOf<UltimoBackup?>(null) }
    var senha by remember { mutableStateOf("") }
    var ocupado by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf<String?>(null) }
    var aviso by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(versao) { erro = tentar { ultimo = sessao.banco.configuracao().ultimoBackup() } }
    val salvar = rememberSalvarBackup(sessao, { aviso = it; versao++ }, { erro = it })

    TelaComRolagem("Backup", modifier) {
        MensagemErro(erro)
        MensagemOk(aviso)
        if (!configurado) {
            Text("O backup ainda não foi configurado neste celular. Digite a senha de backup (a anotada em papel) uma vez.", style = MaterialTheme.typography.titleMedium)
            CampoSenha(senha, { senha = it }, "Senha de backup")
            BotaoGrande(if (ocupado) "Conferindo…" else "Configurar backup", habilitado = !ocupado, aoClicar = {
                ocupado = true; erro = null
                escopo.launch {
                    val r = withContext(Dispatchers.Default) { runCatching { sessao.configurarChaveDeBackup(senha) }.getOrNull() }
                    ocupado = false
                    when (r) {
                        ResultadoPin.Ok -> { configurado = true; senha = ""; aviso = "Backup configurado." }
                        ResultadoPin.PinErrado -> erro = "Senha de backup errada."
                        is ResultadoPin.Espere -> erro = "Muitas tentativas erradas. Tente de novo mais tarde."
                        else -> erro = "Não consegui configurar o backup."
                    }
                }
            })
        } else {
            val u = ultimo
            if (u?.preparadoEm == null) Text("Nenhum backup feito ainda.", style = MaterialTheme.typography.titleMedium)
            else {
                Text("Último backup: ${dataBonita(u.preparadoEm.toLocalDate())} ${u.preparadoEm.format(HORA)}", style = MaterialTheme.typography.titleMedium)
                u.conferencia?.let {
                    Text(quantidadesEmTexto(it), style = MaterialTheme.typography.titleMedium)
                    Text("Código de conferência: ${it.codigo}", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    if (u.salvoEm != null && !u.salvoEm.isBefore(u.preparadoEm)) "Salvo em ${dataBonita(u.salvoEm.toLocalDate())} ${u.salvoEm.format(HORA)}."
                    else "Ainda não foi salvo no Drive.",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            BotaoGrande(if (ocupado) "Preparando…" else "Fazer backup agora", habilitado = !ocupado, aoClicar = {
                ocupado = true; erro = null
                escopo.launch {
                    erro = tentar { prepararBackupDoDia(context, sessao, LocalDateTime.now(), forcar = true) }
                    ocupado = false
                    versao++
                    if (erro == null) salvar()
                }
            })
            BotaoGrande("Salvar no Drive", salvar, habilitado = !ocupado && u?.preparadoEm != null)
        }
        // Senha trocada: o arquivo do celular (senha antiga) é apagado na hora e um novo é preparado com a nova.
        if (configurado) TrocarSenha(sessao, aoTrocar = {
            erro = null
            apagarArquivoDeBackup(context)
            ocupado = true
            escopo.launch {
                erro = tentar { prepararBackupDoDia(context, sessao, LocalDateTime.now(), forcar = true) }
                ocupado = false
                aviso = if (erro == null) "Senha trocada. Já fiz um backup novo com a senha nova." else null
                versao++
            }
        })
        Text("A senha de backup é a única proteção do arquivo no Drive: use uma frase longa, não uma palavra.", style = MaterialTheme.typography.bodyMedium)
        Text("Guarde a senha de backup em papel, com uma segunda cópia em outro lugar seguro. Sem ela, os dados não voltam.",
            style = MaterialTheme.typography.bodyMedium)
        TextButton(aoVoltar) { Text("Voltar", style = MaterialTheme.typography.titleMedium) }
    }
}

// Fechada até ela tocar no botão. Campos com remember: senhas não vão para o estado salvo do Android.
@Composable
private fun TrocarSenha(sessao: SessaoDeAcesso, aoTrocar: () -> Unit) {
    var aberta by remember { mutableStateOf(false) }
    var atual by remember { mutableStateOf("") }
    var nova by remember { mutableStateOf("") }
    var nova2 by remember { mutableStateOf("") }
    var erro by remember { mutableStateOf<String?>(null) }
    var ocupado by remember { mutableStateOf(false) }
    val escopo = rememberCoroutineScope()
    if (!aberta) {
        OutlinedButton({ aberta = true }, Modifier.fillMaxWidth()) { Text("Trocar senha de backup", style = MaterialTheme.typography.titleMedium) }
        return
    }
    Text("Trocar senha de backup", style = MaterialTheme.typography.titleLarge)
    Text("Os backups antigos continuam abrindo só com a senha antiga. Guarde a antiga junto com a nova até apagar os backups antigos do Drive.",
        style = MaterialTheme.typography.titleMedium)
    CampoSenha(atual, { atual = it }, "Senha de backup atual")
    CampoSenha(nova, { nova = it }, "Senha nova (frase com 16 ou mais caracteres)")
    CampoSenha(nova2, { nova2 = it }, "Repita a senha nova")
    MensagemErro(erro)
    BotaoGrande(if (ocupado) "Trocando…" else "Trocar a senha", habilitado = !ocupado, aoClicar = {
        erro = when {
            problemaDaSenhaBackup(nova) != null -> problemaDaSenhaBackup(nova)
            nova != nova2 -> "As duas senhas novas não são iguais."
            else -> null
        }
        if (erro == null) {
            ocupado = true
            escopo.launch {
                val r = withContext(Dispatchers.Default) { runCatching { sessao.trocarSenhaDeBackup(atual, nova) }.getOrNull() }
                ocupado = false
                when (r) {
                    ResultadoPin.Ok -> {
                        atual = ""; nova = ""; nova2 = ""; aberta = false
                        aoTrocar()
                    }
                    ResultadoPin.PinErrado -> erro = "Senha de backup atual errada."
                    is ResultadoPin.Espere -> erro = "Muitas tentativas erradas. Tente de novo mais tarde."
                    else -> erro = "Não consegui trocar a senha."
                }
            }
        }
    })
    TextButton({ aberta = false; atual = ""; nova = ""; nova2 = ""; erro = null }) { Text("Cancelar", style = MaterialTheme.typography.titleMedium) }
}
