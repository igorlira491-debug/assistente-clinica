package com.assistenteclinica.ui.cadastros

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.assistenteclinica.acesso.SessaoDeAcesso
import com.assistenteclinica.dados.Terapia
import com.assistenteclinica.dominio.duracaoValida
import com.assistenteclinica.dominio.nomeValido
import com.assistenteclinica.ui.acesso.BotaoGrande
import com.assistenteclinica.ui.acesso.CampoTexto
import com.assistenteclinica.ui.acesso.MensagemErro
import com.assistenteclinica.ui.acesso.TelaComRolagem
import kotlinx.coroutines.launch

@Composable
fun TelaTerapias(sessao: SessaoDeAcesso, aoVoltar: () -> Unit, modifier: Modifier = Modifier) {
    val dao = sessao.banco.cadastros()
    var versao by remember { mutableIntStateOf(0) } // muda depois de cada ação, para reler a lista
    var terapias by remember { mutableStateOf(emptyList<Terapia>()) }
    var editando by remember { mutableStateOf<Terapia?>(null) } // null = terapia nova
    var erro by remember { mutableStateOf<String?>(null) }
    var aviso by remember { mutableStateOf<String?>(null) }
    var ocupado by remember { mutableStateOf(false) } // evita gravar duas vezes com dois toques
    var formVersao by remember { mutableIntStateOf(0) } // muda só quando o formulário é salvo, para limpá-lo
    val escopo = rememberCoroutineScope()
    LaunchedEffect(versao) { tentar { terapias = dao.terapias() }?.let { erro = it } }

    // doFormulario = false para os botões dos cartões: não apagam o que está sendo digitado no formulário.
    fun salvar(t: Terapia, ok: String, doFormulario: Boolean = false) {
        if (ocupado) return
        ocupado = true
        escopo.launch {
            erro = tentar { dao.salvarTerapia(t) }
            ocupado = false
            if (erro == null) { aviso = ok; versao++; if (doFormulario) { editando = null; formVersao++ } }
        }
    }

    TelaComRolagem("Terapias", modifier) {
        terapias.forEach { t ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t.nome, style = MaterialTheme.typography.titleLarge)
                    Text("${t.duracaoMin} min" + if (t.ativa) "" else " · desativada", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ editando = t; erro = null; aviso = null }) { Text("Editar") }
                        OutlinedButton({
                            salvar(t.copy(ativa = !t.ativa), if (t.ativa) "${t.nome} foi desativada." else "${t.nome} foi reativada.")
                        }) { Text(if (t.ativa) "Desativar" else "Reativar") }
                    }
                }
            }
        }
        MensagemErro(erro)
        MensagemOk(aviso)

        HorizontalDivider()
        val atual = editando
        key(atual, formVersao) { // recria o formulário (limpo) ao trocar de terapia ou depois de salvá-lo
            var nome by rememberSaveable { mutableStateOf(atual?.nome ?: "") }
            var duracao by rememberSaveable { mutableStateOf(atual?.duracaoMin?.toString() ?: "") }
            Text(if (atual == null) "Nova terapia" else "Editar ${atual.nome}", style = MaterialTheme.typography.titleLarge)
            CampoTexto(nome, { nome = it }, "Nome (ex.: Fonoaudiologia)")
            CampoTexto(duracao, { duracao = it.filter(Char::isDigit).take(3) }, "Duração da sessão (minutos)", KeyboardType.Number)
            BotaoGrande("Salvar", {
                aviso = null
                val min = duracao.toIntOrNull() ?: 0
                when {
                    !nomeValido(nome) -> erro = "Digite o nome da terapia."
                    !duracaoValida(min) -> erro = "A duração precisa ser de 5 a 240 minutos."
                    else -> salvar((atual ?: Terapia(nome = "", duracaoMin = 0)).copy(nome = nome.trim(), duracaoMin = min), "${nome.trim()} foi salva.", doFormulario = true)
                }
            })
            if (atual != null) TextButton({ editando = null }) { Text("Cancelar", style = MaterialTheme.typography.titleMedium) }
        }
        TextButton(aoVoltar) { Text("Voltar", style = MaterialTheme.typography.titleMedium) }
    }
}
