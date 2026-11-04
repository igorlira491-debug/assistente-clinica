package com.assistenteclinica.ui.pendencias

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.assistenteclinica.acesso.SessaoDeAcesso
import com.assistenteclinica.dados.SessaoNaAgenda
import com.assistenteclinica.dados.paraDominio
import com.assistenteclinica.dominio.QuemFaltou
import com.assistenteclinica.dominio.atendimentosPorTerapeuta
import com.assistenteclinica.dominio.dataBonita
import com.assistenteclinica.dominio.faltasDoMes
import com.assistenteclinica.ui.acesso.MensagemErro
import com.assistenteclinica.ui.acesso.TelaComRolagem
import com.assistenteclinica.ui.agenda.HORA
import com.assistenteclinica.ui.cadastros.tentar
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val MES = DateTimeFormatter.ofPattern("MMMM 'de' yyyy", Locale.forLanguageTag("pt-BR"))

fun textoQuemFaltou(q: QuemFaltou) = when (q) {
    QuemFaltou.FAMILIA -> "Família"
    QuemFaltou.TERAPEUTA -> "Terapeuta"
    QuemFaltou.CLINICA -> "Clínica"
    QuemFaltou.NAO_ACONTECEU -> "Não aconteceu"
}

// Substitui as abas "Faltas do mês" e o controle de atendimentos por terapeuta da planilha (spec §5.4).
@Composable
fun TelaResumos(sessao: SessaoDeAcesso, aoVoltar: () -> Unit, modifier: Modifier = Modifier) {
    var mes by rememberSaveable { mutableStateOf(YearMonth.now()) }
    var doMes by remember { mutableStateOf(emptyList<SessaoNaAgenda>()) }
    var erro by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(mes) {
        erro = tentar { doMes = sessao.banco.agenda().sessoesEntre(mes.atDay(1).atStartOfDay(), mes.plusMonths(1).atDay(1).atStartOfDay()) }
    }
    val porId = doMes.associateBy { it.sessao.id }
    val sessoes = doMes.map { it.sessao.paraDominio() }
    val faltas = faltasDoMes(sessoes)
    val atendimentos = atendimentosPorTerapeuta(sessoes)

    TelaComRolagem("Resumos do mês", modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ mes = mes.minusMonths(1) }) { Text("‹", style = MaterialTheme.typography.titleLarge) }
            Text(mes.format(MES), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleLarge)
            OutlinedButton({ mes = mes.plusMonths(1) }) { Text("›", style = MaterialTheme.typography.titleLarge) }
        }
        MensagemErro(erro)

        Text("Faltas (${faltas.size})", style = MaterialTheme.typography.titleLarge)
        if (faltas.isEmpty()) Text("Nenhuma falta.")
        else Text(
            faltas.groupingBy { it.second }.eachCount().entries.joinToString(" · ") { "${textoQuemFaltou(it.key)}: ${it.value}" },
            style = MaterialTheme.typography.titleMedium,
        )
        faltas.forEach { (s, quem) ->
            val l = porId.getValue(s.id)
            Text("${dataBonita(s.inicio.toLocalDate())} · ${s.inicio.format(HORA)} · ${l.crianca} · ${l.nomeTerapeuta} · faltou: ${textoQuemFaltou(quem)}")
        }

        HorizontalDivider()
        Text("Atendimentos por terapeuta", style = MaterialTheme.typography.titleLarge)
        if (atendimentos.isEmpty()) Text("Nenhum atendimento registrado como realizado.")
        atendimentos.forEach { (_, lista) ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${porId.getValue(lista.first().id).nomeTerapeuta}: ${lista.size}", style = MaterialTheme.typography.titleMedium)
                    lista.forEach { s -> Text("${dataBonita(s.inicio.toLocalDate())} · ${s.inicio.format(HORA)} · ${porId.getValue(s.id).crianca}") }
                }
            }
        }
        TextButton(aoVoltar) { Text("Voltar", style = MaterialTheme.typography.titleMedium) }
    }
}
