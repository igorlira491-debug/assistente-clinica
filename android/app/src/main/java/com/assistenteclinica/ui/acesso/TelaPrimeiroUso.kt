package com.assistenteclinica.ui.acesso

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.assistenteclinica.acesso.SessaoDeAcesso
import com.assistenteclinica.dominio.problemaDaSenhaBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Só aparece uma vez, no aparelho novo: cadastra a dona e cria a chave dos dados.
// Os campos usam remember (não rememberSaveable) de propósito: PIN e senha não vão para o estado salvo do Android.
@Composable
fun TelaPrimeiroUso(sessao: SessaoDeAcesso, modifier: Modifier = Modifier) {
    var nome by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var senha by remember { mutableStateOf("") }
    var senha2 by remember { mutableStateOf("") }
    var erro by remember { mutableStateOf<String?>(null) }
    var ocupado by remember { mutableStateOf(false) }
    var restaurando by remember { mutableStateOf(false) }
    val escopo = rememberCoroutineScope()

    if (restaurando) {
        TelaRestaurar(sessao, aoVoltar = { restaurando = false }, modifier)
        return
    }

    TelaComRolagem("Bem-vinda ao Assistente", modifier) {
        Text("Primeiro uso: cadastre a dona da clínica.", style = MaterialTheme.typography.titleMedium)
        CampoTexto(nome, { nome = it }, "Nome da dona")
        CampoPin(pin, { pin = it })
        CampoPin(pin2, { pin2 = it }, "Repita o PIN")
        CampoSenha(senha, { senha = it }, "Senha de backup (frase com 16 ou mais caracteres)")
        CampoSenha(senha2, { senha2 = it }, "Repita a senha de backup")
        Text(
            "Anote a senha de backup em papel e guarde em lugar seguro. Sem ela não é possível recuperar os dados.",
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error,
        )
        MensagemErro(erro)
        BotaoGrande(if (ocupado) "Preparando…" else "Começar", habilitado = !ocupado, aoClicar = {
            erro = when {
                nome.isBlank() -> "Digite o nome da dona."
                erroDoPin(pin, pin2) != null -> erroDoPin(pin, pin2)
                problemaDaSenhaBackup(senha) != null -> problemaDaSenhaBackup(senha)
                senha != senha2 -> "As duas senhas de backup não são iguais."
                else -> null
            }
            if (erro == null) {
                ocupado = true
                escopo.launch {
                    val ok = withContext(Dispatchers.Default) { runCatching { sessao.configurarPrimeiroUso(nome.trim(), pin, senha) } }
                    ocupado = false // se deu certo, a tela some sozinha: o estado vira Destravado
                    if (ok.isFailure) erro = ERRO_INESPERADO
                }
            }
        })
        // Celular trocado: em vez de começar do zero, traz os dados de um backup (Bloco 8, etapa 5).
        TextButton({ restaurando = true }, enabled = !ocupado) {
            Text("Já usava o Assistente? Restaurar backup", style = MaterialTheme.typography.titleMedium)
        }
    }
}
