package com.assistenteclinica.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

// Revisão de segurança de 2026-10-29 (docs/2026-10-29-revisao-de-seguranca.md), itens A e C.

// A: pede a todo teclado o modo "sem aprendizado" (opção oficial do Android) em todos os campos do app. Nomes de
// crianças, responsáveis e o texto da observação não entram no dicionário do teclado nem na conta Google.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TecladoSemAprendizado(conteudo: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { pedido, proximo ->
            proximo.startInputMethod(object : PlatformTextInputMethodRequest by pedido {
                override fun createInputConnection(outAttributes: EditorInfo) = pedido.createInputConnection(outAttributes).also {
                    outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                }
            })
        },
        content = conteudo,
    )
}

fun celularTemBloqueioDeTela(context: Context): Boolean = context.getSystemService(KeyguardManager::class.java).isDeviceSecure

// C: sem bloqueio de tela (padrão, PIN ou digital do Android), o resto do celular fica aberto e a chave do aparelho
// do app não funciona como deveria. Não impede o uso: deixa o risco visível em todas as telas, até ser resolvido.
// Relido sempre que o app volta para a frente (ela pode ter ligado o bloqueio nas Configurações).
@Composable
fun FaixaSemBloqueioDeTela() {
    val context = LocalContext.current
    var temBloqueio by remember { mutableStateOf(celularTemBloqueioDeTela(context)) }
    val ciclo = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(ciclo) {
        val observador = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) temBloqueio = celularTemBloqueioDeTela(context) }
        ciclo.addObserver(observador)
        onDispose { ciclo.removeObserver(observador) }
    }
    if (temBloqueio) return
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            "⚠ Este celular não tem bloqueio de tela. Ligue um padrão, PIN ou digital para proteger os dados das crianças.",
            color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.titleMedium,
        )
        TextButton({ runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) } }) {
            Text("Abrir as Configurações", style = MaterialTheme.typography.titleMedium)
        }
    }
}
