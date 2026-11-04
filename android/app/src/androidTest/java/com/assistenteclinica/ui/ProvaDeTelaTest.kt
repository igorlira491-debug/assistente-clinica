package com.assistenteclinica.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.assistenteclinica.ui.acesso.BotaoGrande
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Tarefa 0 do Bloco 7: prova de que o teste de tela roda no celular de teste com a depuração de segurança
// desligada. Isolado: abre uma tela vazia, sem o app de verdade (não toca no banco nem no cadastro).
@RunWith(AndroidJUnit4::class)
class ProvaDeTelaTest {
    @get:Rule val regra = createAndroidComposeRule<ComponentActivity>()

    @Test fun botaoGrandeResponde() {
        var tocou = 0
        regra.setContent { BotaoGrande("Testar", { tocou++ }) }
        regra.onNodeWithText("Testar").performClick()
        assertEquals(1, tocou)
    }
}
