package com.assistenteclinica.ui.agenda

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.assistenteclinica.dados.AgendaDao
import com.assistenteclinica.dados.paraDominio
import com.assistenteclinica.dominio.Origem
import com.assistenteclinica.dominio.Sessao
import com.assistenteclinica.dominio.Situacao
import com.assistenteclinica.dominio.TipoConflito
import com.assistenteclinica.dominio.TipoSessao
import com.assistenteclinica.dominio.conflitos
import com.assistenteclinica.dominio.situacao
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

val HORA: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

fun horario(s: Sessao) = "${s.inicio.format(HORA)}–${s.fim.format(HORA)}"

fun textoSituacao(s: Sessao) = when (situacao(s)) {
    Situacao.AGUARDANDO -> "Aguardando confirmação"
    Situacao.CONFIRMADA -> "Confirmada"
    Situacao.NAO_VAI_ACONTECER -> "Não vai acontecer"
    Situacao.ENCERRADA -> "Encerrada"
}

fun textoTipo(t: TipoSessao) = when (t) {
    TipoSessao.COMUM -> "Comum"
    TipoSessao.ANAMNESE -> "Anamnese"
    TipoSessao.DEVOLUTIVA -> "Devolutiva"
}

fun textoOrigem(o: Origem) = when (o) {
    Origem.FIXA -> "Fixa"
    Origem.AVULSA -> "Avulsa"
    Origem.REMARCACAO -> "Remarcação"
}

// Conflitos da sessão com as do mesmo dia, já em frases para o diálogo "Salvar mesmo assim?".
// ignorar: a sessão que está sendo remarcada (ela vai deixar de ocupar o horário).
suspend fun conflitosEmTexto(dao: AgendaDao, s: Sessao, ignorar: Long = 0): List<String> {
    val dia = s.inicio.toLocalDate()
    val doDia = dao.sessoesEntre(dia.atStartOfDay(), dia.plusDays(1).atStartOfDay()).filter { it.sessao.id != ignorar }.associateBy { it.sessao.id }
    return conflitos(s, doDia.values.map { it.sessao.paraDominio() }).map { c ->
        val outra = doDia.getValue(c.com.id)
        when (c.tipo) {
            TipoConflito.MESMA_TERAPEUTA -> "${outra.nomeTerapeuta} já tem sessão às ${c.com.inicio.format(HORA)} com ${outra.crianca}."
            TipoConflito.MESMA_CRIANCA -> "${outra.crianca} já tem sessão às ${c.com.inicio.format(HORA)} (${outra.terapia})."
        }
    }
}

@Composable
fun DialogoConflitos(conflitos: List<String>, aoVoltar: () -> Unit, aoSalvar: () -> Unit) {
    AlertDialog(
        onDismissRequest = aoVoltar,
        title = { Text("Atenção: horário ocupado") },
        text = { Text(conflitos.joinToString("\n")) },
        confirmButton = { TextButton(aoSalvar) { Text("Salvar mesmo assim") } },
        dismissButton = { TextButton(aoVoltar) { Text("Voltar") } },
    )
}

// Botão "Rótulo: escolhido ▾" que abre a lista de opções.
@Composable
fun <T> Escolha(rotulo: String, atual: T?, opcoes: List<T>, nome: (T) -> String, aoEscolher: (T) -> Unit) {
    var aberto by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ aberto = true }, Modifier.fillMaxWidth()) {
            Text("$rotulo: ${atual?.let(nome) ?: "escolher"} ▾", style = MaterialTheme.typography.titleMedium)
        }
        DropdownMenu(aberto, { aberto = false }) {
            opcoes.forEach { o -> DropdownMenuItem({ Text(nome(o)) }, { aberto = false; aoEscolher(o) }) }
        }
    }
}

// minimo: primeiro dia que pode ser escolhido (sessão nova não vai para o passado).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialogoData(inicial: LocalDate, minimo: LocalDate?, aoFechar: () -> Unit, aoEscolher: (LocalDate) -> Unit) {
    val estado = rememberDatePickerState(
        initialSelectedDateMillis = inicial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = minimo == null || !utc(utcTimeMillis).isBefore(minimo)
        },
    )
    DatePickerDialog(
        onDismissRequest = aoFechar,
        confirmButton = { TextButton({ estado.selectedDateMillis?.let { aoEscolher(utc(it)) } }) { Text("OK") } },
        dismissButton = { TextButton(aoFechar) { Text("Cancelar") } },
    ) { DatePicker(estado) }
}

private fun utc(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()

// Abre digitando (mais rápido); o relógio fica a um toque para quem preferir.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialogoHora(inicial: LocalTime?, aoFechar: () -> Unit, aoEscolher: (LocalTime) -> Unit) {
    val estado = rememberTimePickerState(inicial?.hour ?: 8, inicial?.minute ?: 0, is24Hour = true)
    var digitando by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = aoFechar,
        title = {
            TextButton({ digitando = !digitando }) {
                Text(if (digitando) "Usar o relógio" else "Digitar a hora", style = MaterialTheme.typography.titleMedium)
            }
        },
        text = { if (digitando) TimeInput(estado) else TimePicker(estado) },
        confirmButton = { TextButton({ aoEscolher(LocalTime.of(estado.hour, estado.minute)) }) { Text("OK") } },
        dismissButton = { TextButton(aoFechar) { Text("Cancelar") } },
    )
}
