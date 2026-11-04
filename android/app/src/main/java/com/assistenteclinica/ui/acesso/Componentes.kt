package com.assistenteclinica.ui.acesso

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assistenteclinica.dominio.pinValido

// Peças repetidas das telas de acesso: letras grandes e botões altos (spec §9).

// Digitar no teclado não passa pelo toque da tela: os campos avisam a trava automática que há alguém usando.
val LocalAtividade = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
fun TelaComRolagem(titulo: String, modifier: Modifier = Modifier, conteudo: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(titulo, style = MaterialTheme.typography.headlineMedium)
        conteudo()
    }
}

@Composable
fun CampoTexto(valor: String, aoMudar: (String) -> Unit, rotulo: String, teclado: KeyboardType = KeyboardType.Text) {
    val atividade = LocalAtividade.current
    OutlinedTextField(
        valor, { atividade(); aoMudar(it) }, Modifier.fillMaxWidth(),
        label = { Text(rotulo) }, singleLine = true, textStyle = MaterialTheme.typography.titleLarge,
        keyboardOptions = KeyboardOptions(keyboardType = teclado),
    )
}

// PIN: só números, escondido, sem sugestões nem correção do teclado.
@Composable
fun CampoPin(valor: String, aoMudar: (String) -> Unit, rotulo: String = "PIN (6 números)") {
    val atividade = LocalAtividade.current
    OutlinedTextField(
        valor, { novo -> atividade(); aoMudar(novo.filter { it in '0'..'9' }.take(6)) }, Modifier.fillMaxWidth(),
        label = { Text(rotulo) }, singleLine = true, textStyle = MaterialTheme.typography.titleLarge,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, autoCorrectEnabled = false),
    )
}

@Composable
fun CampoSenha(valor: String, aoMudar: (String) -> Unit, rotulo: String) {
    val atividade = LocalAtividade.current
    OutlinedTextField(
        valor, { atividade(); aoMudar(it) }, Modifier.fillMaxWidth(),
        label = { Text(rotulo) }, singleLine = true, textStyle = MaterialTheme.typography.titleLarge,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
    )
}

@Composable
fun BotaoGrande(texto: String, aoClicar: () -> Unit, habilitado: Boolean = true) {
    Button(aoClicar, Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = habilitado) {
        Text(texto, fontSize = 20.sp)
    }
}

@Composable
fun MensagemErro(texto: String?) {
    if (texto != null) Text(texto, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
}

const val REGRA_PIN = "O PIN precisa ter 6 números e não pode ser óbvio (como 111111 ou 123456)."
const val ERRO_INESPERADO = "Algo deu errado. Tente de novo; se repetir, chame o suporte."

// null = PIN aceito.
fun erroDoPin(pin: String, repetido: String): String? = when {
    !pinValido(pin) -> REGRA_PIN
    pin != repetido -> "Os dois PINs não são iguais."
    else -> null
}
