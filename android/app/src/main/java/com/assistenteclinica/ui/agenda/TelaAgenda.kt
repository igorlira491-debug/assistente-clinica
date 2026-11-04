package com.assistenteclinica.ui.agenda

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.assistenteclinica.acesso.SessaoDeAcesso
import com.assistenteclinica.dados.SessaoNaAgenda
import com.assistenteclinica.dados.paraDominio
import com.assistenteclinica.dominio.TipoSessao
import com.assistenteclinica.dominio.dataBonita
import com.assistenteclinica.dominio.semanaDe
import com.assistenteclinica.ui.acesso.BotaoGrande
import com.assistenteclinica.ui.acesso.MensagemErro
import com.assistenteclinica.ui.acesso.TelaComRolagem
import com.assistenteclinica.ui.cadastros.tentar
import com.assistenteclinica.ui.pendencias.TelaDiaDificil
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class Visao { DIA, SEMANA, MES }

private val MES: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM 'de' yyyy", Locale.forLanguageTag("pt-BR"))

private const val NOVA = 0L // valor de `aberta` para sessão nova (ids salvos começam em 1)

@Composable
fun TelaAgenda(sessao: SessaoDeAcesso, aoAbrirMenu: () -> Unit, modifier: Modifier = Modifier) {
    // Tudo aqui em cima do `if`: abrir e fechar uma sessão não perde o dia escolhido. Saveable: sobrevive a girar a tela.
    var aberta by rememberSaveable { mutableStateOf<Long?>(null) } // null = agenda; NOVA = sessão nova; senão o id
    var dia by rememberSaveable { mutableStateOf(LocalDate.now()) }
    var visao by rememberSaveable { mutableStateOf(Visao.DIA) }
    var lancando by rememberSaveable { mutableStateOf(false) }
    var diaDificil by rememberSaveable { mutableStateOf(false) }
    var versao by remember { mutableIntStateOf(0) } // muda ao voltar de uma sessão, para reler
    var sessoes by remember { mutableStateOf(emptyList<SessaoNaAgenda>()) }
    var erro by remember { mutableStateOf<String?>(null) }

    val a = aberta
    if (a != null) {
        val fechar: () -> Unit = { aberta = null; versao++ }
        BackHandler(onBack = fechar)
        TelaSessao(sessao, a.takeIf { it != NOVA }, dia, fechar, aoMudarDia = { dia = it }, modifier = modifier)
        return
    }
    if (lancando) {
        val fechar: () -> Unit = { lancando = false; versao++ }
        BackHandler(onBack = fechar)
        TelaLancarMes(sessao, fechar, modifier)
        return
    }
    if (diaDificil) {
        val fechar: () -> Unit = { diaDificil = false; versao++ }
        BackHandler(onBack = fechar)
        TelaDiaDificil(sessao, dia, fechar, modifier)
        return
    }

    val dao = sessao.banco.agenda()
    val mes = YearMonth.from(dia)
    val dias = when (visao) {
        Visao.DIA -> listOf(dia)
        Visao.SEMANA -> semanaDe(dia)
        Visao.MES -> (1..mes.lengthOfMonth()).map(mes::atDay)
    }
    LaunchedEffect(dias, versao) {
        erro = tentar { sessoes = dao.sessoesEntre(dias.first().atStartOfDay(), dias.last().plusDays(1).atStartOfDay()) }
    }
    fun andar(n: Long) {
        dia = when (visao) {
            Visao.DIA -> dia.plusDays(n)
            Visao.SEMANA -> dia.plusWeeks(n)
            Visao.MES -> dia.plusMonths(n)
        }
    }

    TelaComRolagem("Agenda", modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Modo("Dia", visao == Visao.DIA) { visao = Visao.DIA }
            Modo("Semana", visao == Visao.SEMANA) { visao = Visao.SEMANA }
            Modo("Mês", visao == Visao.MES) { visao = Visao.MES }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ andar(-1) }) { Text("‹", style = MaterialTheme.typography.titleLarge) }
            OutlinedButton({ dia = LocalDate.now() }) { Text("Hoje", style = MaterialTheme.typography.titleMedium) }
            OutlinedButton({ andar(1) }) { Text("›", style = MaterialTheme.typography.titleLarge) }
        }
        MensagemErro(erro)
        val porDia = sessoes.groupBy { it.sessao.inicio.toLocalDate() }
        if (visao == Visao.MES) {
            // No mês aparecem só os dias com sessão: 30 linhas de "—" esconderiam o que importa.
            Text("${mes.format(MES)} · ${sessoes.size} sessão(ões)", style = MaterialTheme.typography.titleLarge)
            if (sessoes.isEmpty()) Text("Nenhuma sessão neste mês.", style = MaterialTheme.typography.titleMedium)
            dias.filter { it in porDia }.forEach { d ->
                TextButton({ dia = d; visao = Visao.DIA }) { Text(dataBonita(d), style = MaterialTheme.typography.titleLarge) }
                porDia.getValue(d).forEach { s -> CartaoSessao(s, curto = true) { aberta = s.sessao.id } }
            }
        } else if (visao == Visao.SEMANA) dias.forEach { d ->
            TextButton({ dia = d; visao = Visao.DIA }) { Text(dataBonita(d), style = MaterialTheme.typography.titleLarge) }
            val doDia = porDia[d].orEmpty()
            if (doDia.isEmpty()) Text("—")
            doDia.forEach { s -> CartaoSessao(s, curto = true) { aberta = s.sessao.id } }
        } else {
            Text(dataBonita(dia), style = MaterialTheme.typography.titleLarge)
            val doDia = porDia[dia].orEmpty()
            if (doDia.isEmpty()) Text("Nenhuma sessão neste dia.", style = MaterialTheme.typography.titleMedium)
            doDia.forEach { s -> CartaoSessao(s, curto = false) { aberta = s.sessao.id } }
        }
        BotaoGrande("Nova sessão", { aberta = NOVA })
        OutlinedButton({ lancando = true }, Modifier.fillMaxWidth()) { Text("Lançar agenda do mês", style = MaterialTheme.typography.titleMedium) }
        OutlinedButton({ diaDificil = true }, Modifier.fillMaxWidth()) {
            Text("Dia difícil (terapeuta faltou, clínica fechada)", style = MaterialTheme.typography.titleMedium)
        }
        OutlinedButton(aoAbrirMenu, Modifier.fillMaxWidth()) { Text("Menu", style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun Modo(texto: String, ativo: Boolean, aoClicar: () -> Unit) {
    if (ativo) Button(aoClicar) { Text(texto, style = MaterialTheme.typography.titleMedium) }
    else OutlinedButton(aoClicar) { Text(texto, style = MaterialTheme.typography.titleMedium) }
}

@Composable
private fun CartaoSessao(s: SessaoNaAgenda, curto: Boolean, aoAbrir: () -> Unit) {
    val d = s.sessao.paraDominio()
    val tipo = if (d.tipo == TipoSessao.COMUM) "" else " · ${textoTipo(d.tipo)}"
    Card(Modifier.fillMaxWidth().clickable(onClick = aoAbrir)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${horario(d)} · ${s.crianca}", style = MaterialTheme.typography.titleMedium)
            if (!curto) Text("${s.terapia} · ${s.nomeTerapeuta}$tipo")
            Text(textoSituacao(d), color = MaterialTheme.colorScheme.secondary)
        }
    }
}
