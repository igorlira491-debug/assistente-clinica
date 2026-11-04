package com.assistenteclinica.ui.pendencias

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.assistenteclinica.acesso.SessaoDeAcesso
import com.assistenteclinica.dados.Terapeuta
import com.assistenteclinica.dados.paraDominio
import com.assistenteclinica.dominio.clinicaFechada
import com.assistenteclinica.dominio.dataBonita
import com.assistenteclinica.dominio.indisponivel
import com.assistenteclinica.ui.acesso.BotaoGrande
import com.assistenteclinica.ui.acesso.MensagemErro
import com.assistenteclinica.ui.acesso.TelaComRolagem
import com.assistenteclinica.ui.agenda.DialogoData
import com.assistenteclinica.ui.agenda.Escolha
import com.assistenteclinica.ui.cadastros.MensagemOk
import com.assistenteclinica.ui.cadastros.tentar
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.launch

// "Terapeuta indisponível" e "Clínica fechada" (spec §5.5): as sessões do dia viram "Remarcar", com confirmação antes.
@Composable
fun TelaDiaDificil(sessao: SessaoDeAcesso, diaInicial: LocalDate, aoVoltar: () -> Unit, modifier: Modifier = Modifier) {
    val agenda = sessao.banco.agenda()
    val pendencias = sessao.banco.pendencias()
    var fechada by rememberSaveable { mutableStateOf(false) } // false = terapeuta indisponível
    var terapeutaId by rememberSaveable { mutableStateOf<Long?>(null) }
    var dia by rememberSaveable { mutableStateOf(maxOf(diaInicial, LocalDate.now())) }
    var terapeutas by remember { mutableStateOf(emptyList<Terapeuta>()) }
    var escolhendoData by remember { mutableStateOf(false) }
    var quantas by remember { mutableStateOf<Int?>(null) } // não null = confirmação aberta
    var erro by remember { mutableStateOf<String?>(null) }
    var aviso by remember { mutableStateOf<String?>(null) }
    val escopo = rememberCoroutineScope()

    LaunchedEffect(Unit) { erro = tentar { terapeutas = sessao.banco.cadastros().terapeutas().filter { it.ativa } } }
    val terapeuta = terapeutas.firstOrNull { it.id == terapeutaId }

    fun contar() {
        val t = terapeutaId
        if (!fechada && t == null) { erro = "Escolha a terapeuta."; return }
        aviso = null
        escopo.launch {
            var n = 0
            erro = tentar {
                val doDia = agenda.sessoesDominioEntre(dia.atStartOfDay(), dia.plusDays(1).atStartOfDay()).map { it.paraDominio() }
                val agora = LocalDateTime.now()
                n = if (fechada) clinicaFechada(doDia, dia, agora).size else indisponivel(doDia, checkNotNull(t), dia, agora).size
            }
            if (erro == null) { if (n == 0) aviso = "Nenhuma sessão para mudar nesse dia." else quantas = n }
        }
    }

    TelaComRolagem("Dia difícil", modifier) {
        Column {
            Opcao(!fechada, "Terapeuta indisponível") { fechada = false }
            Opcao(fechada, "Clínica fechada") { fechada = true }
        }
        if (!fechada) Escolha("Terapeuta", terapeuta, terapeutas, { it.nome }) { terapeutaId = it.id }
        OutlinedButton({ escolhendoData = true }, Modifier.fillMaxWidth()) { Text("Dia: ${dataBonita(dia)} ▾", style = MaterialTheme.typography.titleMedium) }
        MensagemErro(erro)
        MensagemOk(aviso)
        BotaoGrande("Continuar", ::contar)
        TextButton(aoVoltar) { Text("Voltar", style = MaterialTheme.typography.titleMedium) }
    }

    if (escolhendoData) DialogoData(dia, LocalDate.now(), { escolhendoData = false }) { dia = it; escolhendoData = false }
    quantas?.let { n ->
        val quem = if (fechada) "" else " de ${terapeuta?.nome}"
        AlertDialog(
            onDismissRequest = { quantas = null },
            title = { Text(if (fechada) "Clínica fechada" else "Terapeuta indisponível") },
            text = { Text("$n sessão(ões)$quem em ${dataBonita(dia)} vão ficar para remarcar.") },
            confirmButton = {
                TextButton({
                    quantas = null
                    escopo.launch {
                        val agora = LocalDateTime.now()
                        var feitas = 0
                        erro = tentar {
                            feitas = if (fechada) pendencias.fecharClinica(dia, agora) else pendencias.marcarIndisponivel(checkNotNull(terapeutaId), dia, agora)
                        }
                        if (erro == null) aviso = "Pronto: $feitas sessão(ões) foram para \"Remarcar\"."
                    }
                }) { Text("Confirmar") }
            },
            dismissButton = { TextButton({ quantas = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun Opcao(marcada: Boolean, texto: String, aoEscolher: () -> Unit) {
    Row(Modifier.selectable(marcada, role = Role.RadioButton, onClick = aoEscolher), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(marcada, null)
        Text(texto, style = MaterialTheme.typography.titleMedium)
    }
}
