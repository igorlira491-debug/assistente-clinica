package com.assistenteclinica.ui.agenda

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.assistenteclinica.acesso.SessaoDeAcesso
import com.assistenteclinica.dados.Horario
import com.assistenteclinica.dados.HorarioNaFicha
import com.assistenteclinica.dados.Terapia
import com.assistenteclinica.ui.acesso.BotaoGrande
import com.assistenteclinica.ui.acesso.MensagemErro
import com.assistenteclinica.ui.cadastros.MensagemOk
import com.assistenteclinica.ui.cadastros.tentar
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.launch

fun nomeDoDia(d: DayOfWeek): String = d.getDisplayName(TextStyle.FULL, Locale.forLanguageTag("pt-BR"))

private sealed interface Confirmacao {
    val quantas: Int
    val agora: LocalDateTime // o mesmo momento da contagem, para o número do aviso bater com o que acontece
    data class Editar(val novo: Horario, override val quantas: Int, override val agora: LocalDateTime) : Confirmacao
    data class Desativar(val h: Horario, override val quantas: Int, override val agora: LocalDateTime) : Confirmacao
}

// Parte "Horários fixos" da ficha de uma criança Fixo. terapias: as que a criança faz.
@Composable
fun HorariosFixos(sessao: SessaoDeAcesso, criancaId: Long, terapias: List<Terapia>, versaoFicha: Int) {
    val agenda = sessao.banco.agenda()
    var versao by remember { mutableIntStateOf(0) }
    var horarios by remember { mutableStateOf(emptyList<HorarioNaFicha>()) }
    var editando by rememberSaveable { mutableStateOf<Long?>(null) } // id do horário sendo editado; null = adicionando
    var terapiaId by rememberSaveable { mutableStateOf<Long?>(null) }
    var dia by rememberSaveable { mutableStateOf<DayOfWeek?>(null) }
    var hora by rememberSaveable { mutableStateOf<LocalTime?>(null) }
    var escolhendoHora by remember { mutableStateOf(false) }
    var confirmacao by remember { mutableStateOf<Confirmacao?>(null) }
    var erro by remember { mutableStateOf<String?>(null) }
    var aviso by remember { mutableStateOf<String?>(null) }
    var salvando by remember { mutableStateOf(false) }
    val escopo = rememberCoroutineScope()

    LaunchedEffect(versao, versaoFicha) { erro = tentar { horarios = agenda.horariosDaCrianca(criancaId) } }

    fun limpar() { editando = null; terapiaId = null; dia = null; hora = null }

    fun executar(ok: String, acao: suspend () -> Unit) = escopo.launch {
        salvando = true
        aviso = null
        erro = tentar(acao)
        salvando = false
        if (erro == null) { aviso = ok; limpar(); versao++ }
    }

    // Pergunta antes quantas sessões futuras sem resposta o horário leva junto.
    fun confirmar(criar: (Int, LocalDateTime) -> Confirmacao, id: Long) = escopo.launch {
        val agora = LocalDateTime.now()
        var n = 0
        erro = tentar { n = agenda.contarParaTirar(id, agora) }
        if (erro == null) confirmacao = criar(n, agora)
    }

    Text("Horários fixos", style = MaterialTheme.typography.titleLarge)
    horarios.forEach { item ->
        val h = item.horario
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${item.terapia} · ${nomeDoDia(h.diaDaSemana)} ${h.hora.format(HORA)}", style = MaterialTheme.typography.titleMedium)
                if (item.semTerapeuta) Text("⚠ Sem terapeuta: não entra no lançamento do mês", color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Sem terapeuta não dá para recriar as sessões no horário novo: primeiro acertar a terapeuta.
                    if (!item.semTerapeuta) OutlinedButton({ editando = h.id; terapiaId = h.terapiaId; dia = h.diaDaSemana; hora = h.hora }) { Text("Editar") }
                    OutlinedButton({ confirmar({ n, agora -> Confirmacao.Desativar(h, n, agora) }, h.id) }) { Text("Desativar") }
                }
            }
        }
    }
    if (horarios.isEmpty()) Text("Nenhum horário fixo.")

    val emEdicao = horarios.firstOrNull { it.horario.id == editando }?.horario
    Text(if (emEdicao != null) "Editar horário" else "Adicionar horário", style = MaterialTheme.typography.titleMedium)
    if (emEdicao == null) Escolha("Terapia", terapias.firstOrNull { it.id == terapiaId }, terapias, { it.nome }) { terapiaId = it.id }
    Escolha("Dia", dia, DayOfWeek.entries, ::nomeDoDia) { dia = it }
    OutlinedButton({ escolhendoHora = true }, Modifier.fillMaxWidth()) {
        Text("Hora: ${hora?.format(HORA) ?: "escolher"} ▾", style = MaterialTheme.typography.titleMedium)
    }
    MensagemErro(erro)
    MensagemOk(aviso)
    BotaoGrande(if (emEdicao != null) "Salvar novo horário" else "Adicionar horário", habilitado = !salvando, aoClicar = {
        val t = terapiaId; val d = dia; val hr = hora
        val repetido = horarios.any { it.horario.id != editando && it.horario.terapiaId == t && it.horario.diaDaSemana == d && it.horario.hora == hr }
        erro = when {
            t == null || d == null || hr == null -> "Escolha a terapia, o dia e a hora."
            repetido -> "Esse horário já existe."
            else -> null
        }
        if (erro == null && t != null && d != null && hr != null) {
            if (emEdicao != null) confirmar({ n, agora -> Confirmacao.Editar(emEdicao.copy(diaDaSemana = d, hora = hr), n, agora) }, emEdicao.id)
            else executar("Horário adicionado.") { agenda.salvarHorario(Horario(criancaId = criancaId, terapiaId = t, diaDaSemana = d, hora = hr)) }
        }
    })
    if (emEdicao != null) TextButton(::limpar) { Text("Cancelar edição", style = MaterialTheme.typography.titleMedium) }

    if (escolhendoHora) DialogoHora(hora, { escolhendoHora = false }) { hora = it; escolhendoHora = false }
    confirmacao?.let { c ->
        val (titulo, texto) = when (c) {
            is Confirmacao.Editar -> "Mudar horário" to "${c.quantas} sessão(ões) futura(s) ainda sem resposta vão mudar para o novo horário."
            is Confirmacao.Desativar -> "Desativar horário" to "${c.quantas} sessão(ões) futura(s) ainda sem resposta vão sair da agenda."
        }
        AlertDialog(
            onDismissRequest = { confirmacao = null },
            title = { Text(titulo) },
            text = { Text(texto) },
            confirmButton = {
                TextButton({
                    confirmacao = null
                    when (c) {
                        is Confirmacao.Editar -> executar("Horário mudado.") { check(agenda.editarHorario(c.novo, c.agora) >= 0) }
                        is Confirmacao.Desativar -> executar("Horário desativado.") { agenda.desativarHorario(c.h.id, c.agora) }
                    }
                }) { Text("Confirmar") }
            },
            dismissButton = { TextButton({ confirmacao = null }) { Text("Cancelar") } },
        )
    }
}
