package com.assistenteclinica.backup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.content.ComponentName
import android.os.Build
import androidx.core.content.FileProvider
import com.assistenteclinica.acesso.Estado
import com.assistenteclinica.acesso.SessaoDeAcesso
import com.assistenteclinica.dados.marcarBackupPreparado
import com.assistenteclinica.dados.marcarBackupSalvo
import com.assistenteclinica.dados.preparadoNoDia
import com.assistenteclinica.dados.ultimoBackup
import com.assistenteclinica.dominio.EXTENSAO_DO_BACKUP
import com.assistenteclinica.dominio.detalheDoDestino
import com.assistenteclinica.dominio.prazoSalvarBackup
import com.assistenteclinica.dominio.precisaPrepararBackup
import java.io.File
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Bloco 8, etapa 4: backup diário semiautomático (desenho §7). Na primeira entrada do dia o app prepara o arquivo
// cifrado e abre a pendência "Salvar backup"; ela toca, a tela de compartilhar abre e ela escolhe o Drive da clínica.

private const val PASTA = "backup" // dentro da pasta privada do app (outros apps não leem)
private const val AUTORIDADE = ".arquivos" // FileProvider: só esta pasta é oferecida, e só ao app escolhido

fun pastaDoBackup(context: Context) = File(context.filesDir, PASTA)

// Um preparo por vez (primeira entrada do dia e "Fazer backup agora" ao mesmo tempo): os dois usariam os mesmos
// arquivos temporários. A cópia em si roda no IO e não para no meio; o próximo espera ela terminar.
private val umPreparoPorVez = Mutex()

// Devolve true se preparou. Sem chave de backup (app de antes do Bloco 8), não faz nada: a dona configura na tela
// Backup. forcar: "Fazer backup agora", mesmo que já tenha um de hoje. Sem arquivo no celular (restaurado de outro
// aparelho, ou apagado ao trocar a senha), prepara mesmo que a marca diga que o de hoje já foi feito.
suspend fun prepararBackupDoDia(context: Context, sessao: SessaoDeAcesso, agora: LocalDateTime, forcar: Boolean = false): Boolean =
    umPreparoPorVez.withLock {
        if (!sessao.temChaveDeBackup()) return false
        val banco = sessao.banco
        val semArquivo = arquivoDeBackupAtual(context) == null
        if (!forcar && !semArquivo && !precisaPrepararBackup(banco.configuracao().ultimoBackup().preparadoNoDia(), agora.toLocalDate())) return false
        val (_, conferencia) = withContext(Dispatchers.IO) { sessao.prepararArquivoDeBackup(pastaDoBackup(context), agora) }
        // A pendência antes da marca de "preparado": se o app morrer entre as duas, prepara de novo e não duplica a pendência.
        banco.pendencias().backupPreparado(prazoSalvarBackup(agora), agora)
        banco.configuracao().marcarBackupPreparado(agora, conferencia)
        true
    }

// Depois de trocar a senha de backup: o arquivo do celular ainda abre com a senha antiga e não pode ser salvo.
fun apagarArquivoDeBackup(context: Context) {
    pastaDoBackup(context).listFiles()?.forEach { it.delete() }
}

fun arquivoDeBackupAtual(context: Context): File? =
    pastaDoBackup(context).listFiles()?.filter { it.name.endsWith(EXTENSAO_DO_BACKUP) }?.maxByOrNull { it.name }

// Abre a tela de compartilhar do Android com o arquivo. Usa a mesma tolerância de 3 min do WhatsApp (decidido pelo
// usuário em 2026-10-29). O app escolhido chega ao ReceptorDoCompartilhamento; registrarDestinoEscolhido fecha a
// pendência quando ela volta. Devolve false se não há arquivo ou se a tela não abriu.
fun compartilharBackup(context: Context, sessao: SessaoDeAcesso): Boolean {
    val arquivo = arquivoDeBackupAtual(context) ?: return false
    val uri = FileProvider.getUriForFile(context, context.packageName + AUTORIDADE, arquivo)
    val envio = Intent(Intent.ACTION_SEND).setType("application/octet-stream")
        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // O Android preenche o app escolhido neste PendingIntent: precisa ser mutável, e é explícito (só o nosso receptor).
    val aviso = PendingIntent.getBroadcast(
        context, 0, Intent(context, ReceptorDoCompartilhamento::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )
    DestinoEscolhido.limpar()
    sessao.marcarSaidaPeloWhatsApp()
    return runCatching {
        context.startActivity(Intent.createChooser(envio, "Salvar o backup", aviso.intentSender).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { sessao.desmarcarSaidaPeloWhatsApp() }.isSuccess
}

// Chamar quando o app volta para a frente. Devolve true se havia um destino escolhido e a pendência foi fechada.
suspend fun registrarDestinoEscolhido(sessao: SessaoDeAcesso, agora: LocalDateTime): Boolean {
    val e = sessao.estado.value as? Estado.Destravado ?: return false
    val pacote = DestinoEscolhido.pegar() ?: return false
    sessao.banco.pendencias().backupSalvo(detalheDoDestino(pacote), agora, e.usuaria.id)
    sessao.banco.configuracao().marcarBackupSalvo(agora)
    return true
}

// O app escolhido fica só na memória até ela voltar (com o app travado o banco não abre). Só o nome do pacote.
object DestinoEscolhido {
    @Volatile private var pacote: String? = null
    fun guardar(p: String) { pacote = p }
    fun pegar(): String? = pacote.also { pacote = null }
    fun limpar() { pacote = null }
}

class ReceptorDoCompartilhamento : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val escolhido = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT)
        }
        escolhido?.packageName?.let(DestinoEscolhido::guardar)
    }
}
