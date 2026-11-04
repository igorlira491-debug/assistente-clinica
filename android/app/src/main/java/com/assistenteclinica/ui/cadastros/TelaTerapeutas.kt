package com.assistenteclinica.ui.cadastros

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.assistenteclinica.acesso.SessaoDeAcesso
import com.assistenteclinica.dados.Terapeuta
import com.assistenteclinica.dados.Terapia
import com.assistenteclinica.dominio.nomeValido
import com.assistenteclinica.dominio.telefoneWhatsApp
import com.assistenteclinica.ui.acesso.BotaoGrande
import com.assistenteclinica.ui.acesso.CampoTexto
import com.assistenteclinica.ui.acesso.MensagemErro
import com.assistenteclinica.ui.acesso.TelaComRolagem
import kotlinx.coroutines.launch

@Composable
fun TelaTerapeutas(sessao: SessaoDeAcesso, aoVoltar: () -> Unit, modifier: Modifier = Modifier) {
    val dao = sessao.banco.cadastros()
    var versao by remember { mutableIntStateOf(0) } // muda depois de cada ação, para reler a lista
    var terapeutas by remember { mutableStateOf(emptyList<Terapeuta>()) }
    var terapias by remember { mutableStateOf(emptyList<Terapia>()) }
    var vinculos by remember { mutableStateOf(emptyMap<Long, Set<Long>>()) } // terapeuta -> terapias
    var editando by remember { mutableStateOf<Terapeuta?>(null) } // null = terapeuta nova
    var desativando by remember { mutableStateOf<Pair<Terapeuta, Int>?>(null) } // terapeuta e quantas crianças atende
    var erro by remember { mutableStateOf<String?>(null) }
    var aviso by remember { mutableStateOf<String?>(null) }
    var ocupado by remember { mutableStateOf(false) } // evita gravar duas vezes com dois toques
    var formVersao by remember { mutableIntStateOf(0) } // muda só quando o formulário é salvo, para limpá-lo
    val escopo = rememberCoroutineScope()
    LaunchedEffect(versao) {
        tentar {
            terapeutas = dao.terapeutas()
            terapias = dao.terapias()
            vinculos = terapeutas.associate { it.id to dao.terapiasDaTerapeuta(it.id).toSet() }
        }?.let { erro = it }
    }

    // doFormulario = false para os botões dos cartões: não apagam o que está sendo digitado no formulário.
    fun salvar(t: Terapeuta, ids: Set<Long>, ok: String, doFormulario: Boolean = false) {
        if (ocupado) return
        ocupado = true
        escopo.launch {
            erro = tentar { dao.salvarTerapeuta(t, ids) }
            ocupado = false
            if (erro == null) { aviso = ok; versao++; if (doFormulario) { editando = null; formVersao++ } }
        }
    }

    desativando?.let { (t, n) ->
        AlertDialog(
            onDismissRequest = { desativando = null },
            title = { Text("Desativar ${t.nome}?") },
            text = { Text("Ela atende $n criança(s). Elas vão aparecer com o aviso \"Revisar terapeuta\".") },
            confirmButton = {
                TextButton({ desativando = null; salvar(t.copy(ativa = false), vinculos[t.id].orEmpty(), "${t.nome} foi desativada.") }) {
                    Text("Desativar")
                }
            },
            dismissButton = { TextButton({ desativando = null }) { Text("Cancelar") } },
        )
    }

    TelaComRolagem("Terapeutas", modifier) {
        val nomesTerapias = terapias.associate { it.id to it.nome }
        terapeutas.forEach { t ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t.nome, style = MaterialTheme.typography.titleLarge)
                    Text(telefoneBonito(t.telefone) + if (t.ativa) "" else " · desativada", style = MaterialTheme.typography.titleMedium)
                    Text(vinculos[t.id].orEmpty().mapNotNull(nomesTerapias::get).sorted().joinToString(", "))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ editando = t; erro = null; aviso = null }) { Text("Editar") }
                        OutlinedButton({
                            if (t.ativa) escopo.launch { erro = tentar { desativando = t to dao.criancasAtendidasPor(t.id) } }
                            else salvar(t.copy(ativa = true), vinculos[t.id].orEmpty(), "${t.nome} foi reativada.")
                        }) { Text(if (t.ativa) "Desativar" else "Reativar") }
                    }
                }
            }
        }
        MensagemErro(erro)
        MensagemOk(aviso)

        HorizontalDivider()
        val atual = editando
        key(atual, formVersao) { // recria o formulário (limpo) ao trocar de terapeuta ou depois de salvá-lo
            var nome by rememberSaveable { mutableStateOf(atual?.nome ?: "") }
            var telefone by rememberSaveable { mutableStateOf(atual?.telefone?.let(::telefoneBonito) ?: "") }
            var marcadas by remember { mutableStateOf(atual?.let { vinculos[it.id] }.orEmpty()) }
            val escolherContato = rememberSeletorDeContato(sessao) { n, num ->
                if (nome.isBlank()) nome = n
                telefone = num
            }
            Text(if (atual == null) "Nova terapeuta" else "Editar ${atual.nome}", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(escolherContato, Modifier.fillMaxWidth()) { Text("Escolher dos contatos") }
            CampoTexto(nome, { nome = it }, "Nome")
            CampoTexto(telefone, { telefone = it }, "WhatsApp com DDD", KeyboardType.Phone)
            Text("Terapias que atende", style = MaterialTheme.typography.titleMedium)
            terapias.filter { it.ativa || it.id in marcadas }.forEach { tp ->
                // A linha inteira marca e desmarca: alvo de toque grande.
                Row(
                    Modifier.fillMaxWidth().toggleable(tp.id in marcadas, role = Role.Checkbox) {
                        marcadas = if (it) marcadas + tp.id else marcadas - tp.id
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(tp.id in marcadas, null)
                    Text(tp.nome, style = MaterialTheme.typography.titleMedium)
                }
            }
            BotaoGrande("Salvar", {
                aviso = null
                val tel = telefoneWhatsApp(telefone)
                when {
                    !nomeValido(nome) -> erro = "Digite o nome da terapeuta."
                    tel == null -> erro = ERRO_TELEFONE
                    terapeutas.any { it.telefone == tel && it.id != atual?.id } -> erro = "Esse número já é de outra terapeuta."
                    marcadas.isEmpty() -> erro = "Marque pelo menos uma terapia."
                    else -> salvar(
                        (atual ?: Terapeuta(nome = "", telefone = "")).copy(nome = nome.trim(), telefone = tel),
                        marcadas, "${nome.trim()} foi salva.", doFormulario = true,
                    )
                }
            })
            if (atual != null) TextButton({ editando = null }) { Text("Cancelar", style = MaterialTheme.typography.titleMedium) }
        }
        TextButton(aoVoltar) { Text("Voltar", style = MaterialTheme.typography.titleMedium) }
    }
}
