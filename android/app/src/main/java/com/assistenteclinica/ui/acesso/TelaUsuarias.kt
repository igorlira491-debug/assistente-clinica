package com.assistenteclinica.ui.acesso

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.assistenteclinica.acesso.SessaoDeAcesso
import com.assistenteclinica.dominio.Papel
import com.assistenteclinica.dominio.Usuaria
import com.assistenteclinica.dominio.podeDesativar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Só a dona chega aqui (a SessaoDeAcesso também recusa se não for a dona).
@Composable
fun TelaUsuarias(sessao: SessaoDeAcesso, aoVoltar: () -> Unit, modifier: Modifier = Modifier) {
    var versao by remember { mutableIntStateOf(0) } // muda depois de cada ação, para reler a lista
    val usuarias = remember(versao) { sessao.usuarias() }
    var redefinindo by remember { mutableStateOf<Usuaria?>(null) }
    var erro by remember { mutableStateOf<String?>(null) }
    var aviso by remember { mutableStateOf<String?>(null) }
    var ocupado by remember { mutableStateOf(false) }
    val escopo = rememberCoroutineScope()

    // Roda a ação fora da tela (PBKDF2 é lento) e mostra o resultado.
    fun executar(ok: String, acao: () -> Unit) {
        ocupado = true; erro = null; aviso = null
        escopo.launch {
            val falha = withContext(Dispatchers.Default) { runCatching(acao).exceptionOrNull() }
            ocupado = false
            // versao++ também recria o formulário de nova secretária, limpando nome e PINs só depois de dar certo.
            if (falha == null) { aviso = ok; redefinindo = null; versao++ } else erro = falha.message ?: ERRO_INESPERADO
        }
    }

    TelaComRolagem("Usuárias", modifier) {
        usuarias.forEach { u ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(u.nome, style = MaterialTheme.typography.titleLarge)
                    Text(
                        (if (u.papel == Papel.DONA) "Dona" else "Secretária") + if (u.ativa) "" else " · desativada",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (u.ativa) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ redefinindo = u; erro = null; aviso = null }, enabled = !ocupado) { Text("Redefinir PIN") }
                        if (podeDesativar(u, usuarias)) OutlinedButton(
                            { executar("${u.nome} foi desativada.") { sessao.desativar(u.id) } }, enabled = !ocupado,
                        ) { Text("Desativar") }
                    }
                }
            }
        }
        MensagemErro(erro)
        aviso?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }

        HorizontalDivider()
        val u = redefinindo
        if (u != null) {
            FormularioPin("PIN novo de ${u.nome}", ocupado, aoCancelar = { redefinindo = null }) { pin ->
                executar("PIN de ${u.nome} trocado.") { sessao.redefinirPin(u.id, pin) }
            }
        } else key(versao) {
            var nome by remember { mutableStateOf("") }
            Text("Nova secretária", style = MaterialTheme.typography.titleLarge)
            CampoTexto(nome, { nome = it }, "Nome")
            FormularioPin("PIN da secretária", ocupado, aoCancelar = null) { pin ->
                val n = nome.trim()
                if (n.isEmpty()) erro = "Digite o nome."
                else executar("$n foi cadastrada.") { sessao.criarUsuaria(n, Papel.SECRETARIA, pin) }
            }
        }
        TextButton(aoVoltar) { Text("Voltar", style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun FormularioPin(rotulo: String, ocupado: Boolean, aoCancelar: (() -> Unit)?, aoConfirmar: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var erro by remember { mutableStateOf<String?>(null) }
    CampoPin(pin, { pin = it }, rotulo)
    CampoPin(pin2, { pin2 = it }, "Repita o PIN")
    MensagemErro(erro)
    BotaoGrande(if (ocupado) "Salvando…" else "Salvar", habilitado = !ocupado, aoClicar = {
        erro = erroDoPin(pin, pin2)
        if (erro == null) aoConfirmar(pin)
    })
    aoCancelar?.let { TextButton(it) { Text("Cancelar", style = MaterialTheme.typography.titleMedium) } }
}
