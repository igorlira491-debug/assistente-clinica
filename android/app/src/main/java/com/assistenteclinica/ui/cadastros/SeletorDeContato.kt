package com.assistenteclinica.ui.cadastros

import android.content.Intent
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.assistenteclinica.acesso.SessaoDeAcesso

// Abre a agenda do celular escolhendo um TELEFONE: o app de Contatos libera só a linha escolhida,
// sem a permissão READ_CONTACTS. Cancelar ou contato sem número não chama aoEscolher.
@Composable
fun rememberSeletorDeContato(sessao: SessaoDeAcesso, aoEscolher: (nome: String, numero: String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val atual by rememberUpdatedState(aoEscolher)
    val lancador = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        // O seletor do Android 17 é translúcido: o app só pausa e a marca sobraria para a próxima saída (que não travaria).
        sessao.desmarcarSaidaPeloWhatsApp()
        val uri = r.data?.data ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.query(uri, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0).orEmpty() to c.getString(1).orEmpty() else null
            }
        }.getOrNull()?.let { (nome, numero) -> if (numero.isNotBlank()) atual(nome, numero) }
    }
    return {
        // Mesma tolerância de 3 min do botão do WhatsApp (decidido pelo usuário em 2026-10-28).
        sessao.marcarSaidaPeloWhatsApp()
        runCatching { lancador.launch(Intent(Intent.ACTION_PICK, Phone.CONTENT_URI)) }
            .onFailure { sessao.desmarcarSaidaPeloWhatsApp() }
    }
}
