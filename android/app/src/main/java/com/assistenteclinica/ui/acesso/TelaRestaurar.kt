package com.assistenteclinica.ui.acesso

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assistenteclinica.acesso.AberturaParaRestaurar
import com.assistenteclinica.acesso.SessaoDeAcesso
import com.assistenteclinica.dominio.LIMITE_DO_ARQUIVO_DE_BACKUP
import com.assistenteclinica.dominio.quantidadesEmTexto
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Bloco 8, etapa 5: restaurar num app vazio (celular novo ou trocado). Dois passos: escolher o arquivo e digitar a
// senha de backup (o app abre e confere o código); depois a dona escolhe o nome dela e cria um PIN novo.
// Campos com remember, não rememberSaveable: senha e PIN não vão para o estado salvo do Android.
@Composable
fun TelaRestaurar(sessao: SessaoDeAcesso, aoVoltar: () -> Unit, modifier: Modifier = Modifier) {
    var aberto by remember { mutableStateOf<AberturaParaRestaurar.Pronta?>(null) }
    val voltar = { sessao.cancelarRestauracao(); aoVoltar() }
    val a = aberto
    if (a == null) PassoAbrir(sessao, aoAbrir = { aberto = it }, aoVoltar = voltar, modifier)
    else PassoPin(sessao, a, aoRecomecar = { aberto = null }, aoVoltar = voltar, modifier)
}

@Composable
private fun PassoAbrir(sessao: SessaoDeAcesso, aoAbrir: (AberturaParaRestaurar.Pronta) -> Unit, aoVoltar: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    var arquivo by remember { mutableStateOf<ByteArray?>(null) }
    var senha by remember { mutableStateOf("") }
    var erro by remember { mutableStateOf<String?>(null) }
    var ocupado by remember { mutableStateOf(false) }
    val escopo = rememberCoroutineScope()
    // O seletor de arquivos do Android (Drive, Downloads...). Sair para ele trava o app, mas aqui nada está aberto.
    val escolher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) escopo.launch {
            val lido = withContext(Dispatchers.IO) { runCatching { lerComLimite(context, uri) }.getOrNull() }
            arquivo = lido
            erro = if (lido == null) "Não consegui ler esse arquivo. Escolha o arquivo do backup (termina em .assistente)." else null
        }
    }

    TelaComRolagem("Restaurar backup", modifier) {
        Text("Use quando o celular foi trocado ou o app foi instalado de novo. Só funciona com o app vazio.",
            style = MaterialTheme.typography.titleMedium)
        OutlinedButton({ escolher.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = !ocupado) {
            Text(if (arquivo == null) "Escolher o arquivo do backup" else "Arquivo escolhido ✓ (trocar)", fontSize = 20.sp)
        }
        CampoSenha(senha, { senha = it }, "Senha de backup")
        MensagemErro(erro)
        BotaoGrande(if (ocupado) "Abrindo e conferindo…" else "Abrir o backup", habilitado = !ocupado && arquivo != null && senha.isNotEmpty(), aoClicar = {
            ocupado = true
            val bytes = arquivo!!
            val tentativa = senha
            escopo.launch {
                val r = withContext(Dispatchers.Default) { runCatching { sessao.abrirBackupParaRestaurar(bytes, tentativa) } }.getOrNull()
                ocupado = false
                erro = when (r) {
                    is AberturaParaRestaurar.Pronta -> { senha = ""; aoAbrir(r); null }
                    AberturaParaRestaurar.SenhaErrada -> "Senha de backup errada."
                    is AberturaParaRestaurar.Espere ->
                        "Muitas tentativas erradas. Tente de novo depois das ${HORA.format(r.ate.atZone(ZoneId.systemDefault()))}."
                    AberturaParaRestaurar.NaoEBackup -> "Este arquivo não é um backup do Assistente."
                    AberturaParaRestaurar.Danificado -> "O arquivo está danificado (cortado ou alterado). Nada foi gravado. Tente um backup de outro dia."
                    is AberturaParaRestaurar.VersaoMaisNova -> "Este backup foi feito por uma versão mais nova do app. Atualize o app antes."
                    AberturaParaRestaurar.ConferenciaDiferente -> "O código de conferência não bate. Nada foi gravado. Tente um backup de outro dia."
                    AberturaParaRestaurar.Interrompida -> "O app saiu da tela no meio. Toque em \"Abrir o backup\" de novo."
                    null -> ERRO_INESPERADO
                }
            }
        })
        TextButton(aoVoltar) { Text("Voltar", style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun PassoPin(sessao: SessaoDeAcesso, a: AberturaParaRestaurar.Pronta, aoRecomecar: () -> Unit, aoVoltar: () -> Unit, modifier: Modifier) {
    var escolhida by remember { mutableLongStateOf(a.donas.singleOrNull()?.id ?: -1L) }
    var pin by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var erro by remember { mutableStateOf<String?>(null) }
    var ocupado by remember { mutableStateOf(false) }
    val escopo = rememberCoroutineScope()
    val iguais = a.codigoRestaurado == a.conferenciaDoArquivo.codigo

    TelaComRolagem("Restaurar backup", modifier) {
        Text("Backup de ${DATA_HORA.format(a.criadoEm)}", style = MaterialTheme.typography.titleLarge)
        Text(quantidadesEmTexto(a.conferenciaDoArquivo), style = MaterialTheme.typography.titleMedium)
        // Os dois códigos lado a lado: o app já comparou, e a pessoa também confere de olho.
        Text("Código no arquivo: ${a.conferenciaDoArquivo.codigo}\nCódigo restaurado: ${a.codigoRestaurado}" +
            if (iguais) "\nOs dois são iguais ✓" else "", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Quem é você? Só a dona cria o PIN agora. As secretárias recebem um PIN novo depois, em Usuárias → Redefinir PIN.",
            style = MaterialTheme.typography.titleMedium)
        a.donas.forEach { u ->
            FilterChip(
                selected = u.id == escolhida, onClick = { escolhida = u.id; erro = null },
                label = { Text(u.nome, fontSize = 22.sp) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            )
        }
        CampoPin(pin, { pin = it }, "PIN novo")
        CampoPin(pin2, { pin2 = it }, "Repita o PIN novo")
        MensagemErro(erro)
        BotaoGrande(if (ocupado) "Restaurando…" else "Restaurar e entrar", habilitado = !ocupado && escolhida != -1L, aoClicar = {
            erro = erroDoPin(pin, pin2)
            if (erro == null) {
                ocupado = true
                val id = escolhida
                val novo = pin
                escopo.launch {
                    val r = withContext(Dispatchers.Default) { runCatching { sessao.concluirRestauracao(id, novo) } }
                    ocupado = false // se deu certo, a tela some sozinha: o estado vira Destravado
                    // A restauração pendente some se o app saiu da tela no meio: volta ao primeiro passo.
                    if (r.exceptionOrNull() is IllegalStateException) aoRecomecar() else if (r.isFailure) erro = ERRO_INESPERADO
                }
            }
        })
        TextButton(aoVoltar) { Text("Cancelar", style = MaterialTheme.typography.titleMedium) }
    }
}

private val HORA = DateTimeFormatter.ofPattern("HH:mm:ss")
private val DATA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH'h'mm")

// Lê o arquivo escolhido inteiro, mas desiste se passar do limite (alguém escolheu um vídeo por engano).
private fun lerComLimite(context: Context, uri: Uri): ByteArray? = context.contentResolver.openInputStream(uri)?.use { entrada ->
    val saida = ByteArrayOutputStream()
    val pedaco = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = entrada.read(pedaco)
        if (n < 0) break
        total += n
        if (total > LIMITE_DO_ARQUIVO_DE_BACKUP) return null
        saida.write(pedaco, 0, n)
    }
    saida.toByteArray()
}
