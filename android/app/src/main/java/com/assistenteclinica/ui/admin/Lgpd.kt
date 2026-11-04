package com.assistenteclinica.ui.admin

import android.content.Intent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.assistenteclinica.acesso.ResultadoPin
import com.assistenteclinica.acesso.SessaoDeAcesso
import com.assistenteclinica.dominio.PALAVRA_PARA_APAGAR
import com.assistenteclinica.dominio.confirmouApagar
import com.assistenteclinica.dominio.textoDaExportacao
import com.assistenteclinica.ui.acesso.BotaoGrande
import com.assistenteclinica.ui.acesso.CampoPin
import com.assistenteclinica.ui.acesso.CampoTexto
import com.assistenteclinica.ui.acesso.MensagemErro
import com.assistenteclinica.ui.cadastros.MensagemOk
import com.assistenteclinica.ui.cadastros.tentar
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Bloco 8, etapa 7: exportar e apagar a pedido (LGPD), só a dona. Aparece na ficha da criança e em Crianças inativas.
// aoApagar: a criança não existe mais, a tela sai dela.
@Composable
fun DadosDaCriancaLgpd(sessao: SessaoDeAcesso, criancaId: Long, aoApagar: () -> Unit) {
    val context = LocalContext.current
    val escopo = rememberCoroutineScope()
    var apagando by remember { mutableStateOf(false) }
    var confirmacao by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var ocupado by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf<String?>(null) }
    var aviso by remember { mutableStateOf<String?>(null) }

    Text("Dados da criança (LGPD)", style = MaterialTheme.typography.titleLarge)
    MensagemErro(erro)
    MensagemOk(aviso)
    // Abre a tela de compartilhar com o texto. Sem a tolerância de 3 min (decidido pelo usuário em 2026-10-29):
    // sair por aqui trava como qualquer saída, e a volta pede o PIN.
    OutlinedButton({
        erro = null; aviso = null
        escopo.launch {
            var texto: String? = null
            erro = tentar { texto = sessao.banco.lgpd().exportar(criancaId)?.let { textoDaExportacao(it, LocalDateTime.now()) } }
            val t = texto ?: return@launch
            val envio = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Dados guardados no Assistente").putExtra(Intent.EXTRA_TEXT, t)
            runCatching { context.startActivity(Intent.createChooser(envio, "Enviar os dados").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { erro = "Não consegui abrir a tela de compartilhar." }
        }
    }, Modifier.fillMaxWidth(), enabled = !ocupado) { Text("Exportar os dados (pedido da família)", style = MaterialTheme.typography.titleMedium) }

    if (!apagando) {
        OutlinedButton({ apagando = true; erro = null; aviso = null }, Modifier.fillMaxWidth()) {
            Text("Apagar a pedido", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
        }
        return
    }
    Text(
        "Isto apaga para sempre a criança, as sessões, os horários, as pendências e os responsáveis que não têm outra " +
            "criança. O registro de ações continua, mas passa a mostrar \"registro removido\". Os backups antigos no " +
            "Drive ainda têm esses dados até serem apagados.",
        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error,
    )
    CampoTexto(confirmacao, { confirmacao = it }, "Digite $PALAVRA_PARA_APAGAR para confirmar")
    CampoPin(pin, { pin = it }, "Seu PIN de novo")
    BotaoGrande(if (ocupado) "Apagando…" else "Apagar para sempre", habilitado = !ocupado && confirmouApagar(confirmacao) && pin.length == 6, aoClicar = {
        ocupado = true; erro = null
        val tentativa = pin
        escopo.launch {
            val r = withContext(Dispatchers.Default) { runCatching { sessao.conferirPinDaDona(tentativa) }.getOrNull() }
            pin = ""
            erro = when (r) {
                ResultadoPin.Ok -> tentar { sessao.banco.lgpd().apagarAPedido(criancaId) }
                ResultadoPin.PinErrado -> "PIN errado."
                is ResultadoPin.Espere -> "Muitos PINs errados. Tente de novo mais tarde."
                else -> "Não consegui conferir o PIN."
            }
            ocupado = false
            if (r == ResultadoPin.Ok && erro == null) aoApagar()
        }
    })
    TextButton({ apagando = false; confirmacao = ""; pin = ""; erro = null }) { Text("Cancelar", style = MaterialTheme.typography.titleMedium) }
}
