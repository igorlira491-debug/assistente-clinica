package com.assistenteclinica.ui.lembretes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.assistenteclinica.dominio.EstadoDosAvisos
import com.assistenteclinica.dominio.dataBonita
import com.assistenteclinica.dominio.guiaDeBateria
import com.assistenteclinica.ui.acesso.BotaoGrande
import com.assistenteclinica.ui.acesso.TelaComRolagem
import java.time.LocalDate

// Sem acesso ao Android aqui dentro (estado e ações chegam prontos): dá para testar a tela isolada.
// Notificações e alarme exato são checados (o Android informa); bateria é só instrução (spec §5.7).
@Composable
fun TelaAvisos(
    estado: EstadoDosAvisos,
    fabricante: String,
    bateriaConferidaEm: LocalDate?,
    notificacoesRecusadas: Boolean, // o Android não pergunta mais: só pelas configurações
    aoPedirNotificacoes: () -> Unit,
    aoAbrirNotificacoes: () -> Unit,
    aoAbrirAlarmes: () -> Unit,
    aoAbrirBateria: () -> Unit,
    aoMarcarBateria: () -> Unit,
    aoVoltar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TelaComRolagem("Avisos do celular", modifier) {
        if (estado.notificacoes) Text("✅ Notificações ligadas", style = MaterialTheme.typography.titleMedium)
        else {
            Text("⚠ Notificações desligadas: o celular não vai avisar das tarefas.", style = MaterialTheme.typography.titleMedium)
            if (notificacoesRecusadas) {
                Text(
                    "O celular não pergunta mais. Toque abaixo e ligue as notificações do Assistente. " +
                        "O app vai travar ao sair; depois é só entrar com o PIN.",
                    style = MaterialTheme.typography.titleMedium,
                )
                BotaoGrande("Abrir configurações de notificação", aoAbrirNotificacoes)
            } else BotaoGrande("Ligar notificações", aoPedirNotificacoes)
        }
        if (estado.alarmeExato) Text("✅ Avisos no horário certo", style = MaterialTheme.typography.titleMedium)
        else {
            Text("⚠ Os avisos podem atrasar alguns minutos.", style = MaterialTheme.typography.titleMedium)
            BotaoGrande("Permitir alarmes no horário", aoAbrirAlarmes)
        }
        Text("Bateria", style = MaterialTheme.typography.titleLarge)
        Text("O celular pode desligar os avisos para economizar bateria. Siga estes passos uma vez:", style = MaterialTheme.typography.titleMedium)
        guiaDeBateria(fabricante).forEachIndexed { i, passo -> Text("${i + 1}. $passo", style = MaterialTheme.typography.titleMedium) }
        BotaoGrande("Abrir as configurações do Assistente", aoAbrirBateria)
        BotaoGrande("Já fiz", aoMarcarBateria)
        bateriaConferidaEm?.let { Text("Passos da bateria feitos em ${dataBonita(it)}.", style = MaterialTheme.typography.titleMedium) }
        TextButton(aoVoltar) { Text("Voltar", style = MaterialTheme.typography.titleMedium) }
    }
}

// Faixa fixa no topo de todas as telas do app destravado enquanto faltar algo que o Android informa
// (alarme exato ou notificações). Não é tarefa: não entra em "O que fazer" nem nas contagens.
@Composable
fun FaixaDeAvisos(estado: EstadoDosAvisos, aoArrumarNotificacoes: () -> Unit, aoPermitirAlarmes: () -> Unit) {
    if (estado.tudoPronto) return
    val texto = if (!estado.notificacoes) "⚠ Notificações desligadas: o celular não vai avisar das tarefas."
    else "⚠ Os avisos podem atrasar: permita alarmes no horário."
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(texto, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.titleMedium)
        if (!estado.notificacoes) TextButton(aoArrumarNotificacoes) { Text("Arrumar", style = MaterialTheme.typography.titleMedium) }
        else TextButton(aoPermitirAlarmes) { Text("Permitir", style = MaterialTheme.typography.titleMedium) }
    }
}
