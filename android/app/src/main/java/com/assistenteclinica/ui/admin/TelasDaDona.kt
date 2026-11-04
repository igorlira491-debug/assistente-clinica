package com.assistenteclinica.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.assistenteclinica.acesso.SessaoDeAcesso
import com.assistenteclinica.dados.Crianca
import com.assistenteclinica.dados.nomesDoRegistro
import com.assistenteclinica.dados.paraDominio
import com.assistenteclinica.dominio.dataBonita
import com.assistenteclinica.dominio.inativaDesde
import com.assistenteclinica.dominio.maisAntigasPrimeiro
import com.assistenteclinica.dominio.textoComNomes
import com.assistenteclinica.ui.acesso.BotaoGrande
import com.assistenteclinica.ui.acesso.MensagemErro
import com.assistenteclinica.ui.acesso.TelaComRolagem
import com.assistenteclinica.ui.cadastros.tentar
import java.time.LocalDate
import java.time.LocalDateTime

// Bloco 8, etapa 6: telas só da dona (plano 2026-10-29, parte 4). A secretária nem vê o botão "Administração".

@Composable
fun TelaAdministracao(
    aoUsuarias: () -> Unit, aoRegistro: () -> Unit, aoBackup: () -> Unit, aoInativas: () -> Unit,
    aoVoltar: () -> Unit, modifier: Modifier = Modifier,
) {
    TelaComRolagem("Administração", modifier) {
        BotaoGrande("Usuárias", aoUsuarias)
        BotaoGrande("Registro de ações", aoRegistro)
        BotaoGrande("Backup", aoBackup)
        BotaoGrande("Crianças inativas", aoInativas)
        TextButton(aoVoltar) { Text("Voltar", style = MaterialTheme.typography.titleMedium) }
    }
}

// Mostra no máximo isto por dia (100 a 300 linhas é o esperado): o resto fica no banco e no backup.
private const val LINHAS_POR_DIA = 1_000

// Só leitura: do mais novo para o mais antigo, um dia por vez, com filtro por usuária.
@Composable
fun TelaRegistro(sessao: SessaoDeAcesso, aoVoltar: () -> Unit, modifier: Modifier = Modifier, hoje: LocalDate = LocalDate.now()) {
    val usuarias = remember { sessao.usuarias() }
    var dia by remember { mutableStateOf(hoje) }
    var usuariaId by remember { mutableStateOf<Long?>(null) }
    var linhas by remember { mutableStateOf<List<String>?>(null) }
    var erro by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(dia, usuariaId) {
        linhas = null
        erro = tentar {
            val b = sessao.banco
            val nomes = b.nomesDoRegistro(usuarias)
            linhas = b.registro().doPeriodo(dia.atStartOfDay(), dia.plusDays(1).atStartOfDay(), usuariaId, LINHAS_POR_DIA)
                .map { textoComNomes(it.paraDominio(), nomes) }
        }
    }

    TelaComRolagem("Registro de ações", modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ dia = dia.minusDays(1) }) { Text("◀ Anterior") }
            OutlinedButton({ dia = dia.plusDays(1) }, enabled = dia < hoje) { Text("Próximo ▶") }
        }
        Text(if (dia == hoje) "Hoje, ${dataBonita(dia)}" else dataBonita(dia), style = MaterialTheme.typography.titleLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(usuariaId == null, { usuariaId = null }, label = { Text("Todas") })
            usuarias.forEach { u -> FilterChip(usuariaId == u.id, { usuariaId = u.id }, label = { Text(u.nome) }) }
        }
        MensagemErro(erro)
        val l = linhas
        when {
            l == null -> if (erro == null) Text("Carregando…", style = MaterialTheme.typography.titleMedium)
            l.isEmpty() -> Text("Nenhuma ação neste dia.", style = MaterialTheme.typography.titleMedium)
            else -> l.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
        }
        TextButton(aoVoltar) { Text("Voltar", style = MaterialTheme.typography.titleMedium) }
    }
}

// Da mais antiga para a mais nova. Cada uma abre "Exportar ou apagar" (LGPD, etapa 7).
@Composable
fun TelaCriancasInativas(sessao: SessaoDeAcesso, aoVoltar: () -> Unit, modifier: Modifier = Modifier) {
    var lista by remember { mutableStateOf<List<Pair<Crianca, LocalDateTime?>>?>(null) }
    var erro by remember { mutableStateOf<String?>(null) }
    var aberta by remember { mutableStateOf<Long?>(null) }
    var versao by remember { mutableIntStateOf(0) }
    LaunchedEffect(versao) {
        erro = tentar {
            val b = sessao.banco
            val desde = b.registro().criancasDesativadasEm().associate { it.id to it.desde }
            lista = maisAntigasPrimeiro(b.cadastros().criancas().filter { !it.ativa }.map { it to desde[it.id] })
        }
    }

    TelaComRolagem("Crianças inativas", modifier) {
        MensagemErro(erro)
        val l = lista
        when {
            l == null -> if (erro == null) Text("Carregando…", style = MaterialTheme.typography.titleMedium)
            l.isEmpty() -> Text("Nenhuma criança inativa.", style = MaterialTheme.typography.titleMedium)
            else -> l.forEach { (c, desde) ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(c.nome, style = MaterialTheme.typography.titleLarge)
                        Text(inativaDesde(desde), style = MaterialTheme.typography.titleMedium)
                        if (aberta == c.id) DadosDaCriancaLgpd(sessao, c.id, aoApagar = { aberta = null; versao++ })
                        else TextButton({ aberta = c.id }) { Text("Exportar ou apagar (LGPD)", style = MaterialTheme.typography.titleMedium) }
                    }
                }
            }
        }
        TextButton(aoVoltar) { Text("Voltar", style = MaterialTheme.typography.titleMedium) }
    }
}
