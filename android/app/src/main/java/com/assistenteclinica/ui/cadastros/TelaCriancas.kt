package com.assistenteclinica.ui.cadastros

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.assistenteclinica.acesso.SessaoDeAcesso
import com.assistenteclinica.dados.Crianca
import com.assistenteclinica.dominio.TipoCliente
import com.assistenteclinica.dominio.combinaComBusca
import com.assistenteclinica.dominio.precisaAvisoDeConsentimento
import com.assistenteclinica.ui.acesso.BotaoGrande
import com.assistenteclinica.ui.acesso.CampoTexto
import com.assistenteclinica.ui.acesso.MensagemErro
import com.assistenteclinica.ui.acesso.TelaComRolagem

private data class Linha(val crianca: Crianca, val semConsentimento: Boolean, val revisarTerapeuta: Boolean)

private const val NOVA = 0L // valor de `ficha` para criança nova (ids salvos começam em 1)

@Composable
fun TelaCriancas(sessao: SessaoDeAcesso, aoVoltar: () -> Unit, modifier: Modifier = Modifier) {
    // Tudo aqui em cima do `if`: abrir e fechar uma ficha não zera a busca. Saveable: sobrevive a girar a tela.
    var ficha by rememberSaveable { mutableStateOf<Long?>(null) } // null = lista; NOVA = criança nova; senão o id
    var busca by rememberSaveable { mutableStateOf("") }
    var verInativas by rememberSaveable { mutableStateOf(false) }
    var versao by remember { mutableIntStateOf(0) } // muda ao voltar da ficha, para reler a lista
    var linhas by remember { mutableStateOf(emptyList<Linha>()) }
    var erro by remember { mutableStateOf<String?>(null) }
    val f = ficha
    if (f != null) {
        val fechar: () -> Unit = { ficha = null; versao++ }
        BackHandler(onBack = fechar)
        TelaCrianca(sessao, f.takeIf { it != NOVA }, fechar, modifier)
        return
    }

    val dao = sessao.banco.cadastros()
    LaunchedEffect(versao) {
        erro = tentar {
            val revisar = dao.criancasParaRevisarTerapeuta().toSet()
            linhas = dao.criancas().map { c ->
                Linha(c, precisaAvisoDeConsentimento(dao.responsaveisDaCrianca(c.id).map { it.responsavel.consentiuEm }), c.id in revisar)
            }
        }
    }

    TelaComRolagem("Crianças", modifier) {
        BotaoGrande("Nova criança", { ficha = NOVA })
        CampoTexto(busca, { busca = it }, "Buscar pelo nome")
        MensagemErro(erro)
        val achadas = linhas.filter { combinaComBusca(it.crianca.nome, busca) }
        achadas.filter { it.crianca.ativa }.forEach { CartaoCrianca(it) { ficha = it.crianca.id } }
        val inativas = achadas.filter { !it.crianca.ativa }
        if (inativas.isNotEmpty()) {
            TextButton({ verInativas = !verInativas }) {
                Text((if (verInativas) "Esconder" else "Mostrar") + " inativas (${inativas.size})", style = MaterialTheme.typography.titleMedium)
            }
            if (verInativas) inativas.forEach { CartaoCrianca(it) { ficha = it.crianca.id } }
        }
        TextButton(aoVoltar) { Text("Voltar", style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun CartaoCrianca(l: Linha, aoAbrir: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = aoAbrir)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(l.crianca.nome, style = MaterialTheme.typography.titleLarge)
            Text(
                (if (l.crianca.tipo == TipoCliente.FIXO) "Fixo" else "Avulso") + if (l.crianca.ativa) "" else " · inativa",
                style = MaterialTheme.typography.titleMedium,
            )
            val cor = MaterialTheme.colorScheme.error
            if (l.semConsentimento) Text("⚠ Sem consentimento", color = cor, style = MaterialTheme.typography.titleMedium)
            if (l.revisarTerapeuta) Text(AVISO_REVISAR_TERAPEUTA, color = cor, style = MaterialTheme.typography.titleMedium)
        }
    }
}
