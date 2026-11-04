package com.assistenteclinica.ui.pendencias

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.assistenteclinica.acesso.Estado
import com.assistenteclinica.acesso.SessaoDeAcesso
import com.assistenteclinica.dados.Pendencia
import com.assistenteclinica.dados.Terapeuta
import com.assistenteclinica.dados.paraLinha
import com.assistenteclinica.dominio.AVISO_OBSERVACAO
import com.assistenteclinica.dominio.Origem
import com.assistenteclinica.dominio.Resposta
import com.assistenteclinica.dominio.Sessao
import com.assistenteclinica.dominio.TipoPendencia
import com.assistenteclinica.dominio.TipoSessao
import com.assistenteclinica.dominio.dataBonita
import com.assistenteclinica.ui.acesso.BotaoGrande
import com.assistenteclinica.ui.acesso.CampoTexto
import com.assistenteclinica.ui.acesso.MensagemErro
import com.assistenteclinica.ui.agenda.DialogoConflitos
import com.assistenteclinica.ui.agenda.DialogoData
import com.assistenteclinica.ui.agenda.DialogoHora
import com.assistenteclinica.ui.agenda.Escolha
import com.assistenteclinica.ui.agenda.HORA
import com.assistenteclinica.ui.agenda.conflitosEmTexto
import com.assistenteclinica.ui.cadastros.tentar
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.launch

const val MAX_MOTIVO = 100

// Quem está usando o app agora: vai em "quem resolveu".
fun SessaoDeAcesso.usuariaId(): Long = (estado.value as Estado.Destravado).usuaria.id

private enum class Modo { NENHUM, NOVA_DATA, ENCERRAR, ADIAR }

private val DIAS_PARA_LEMBRAR = listOf(3, 7, 15, 30)

// Ações de uma pendência guardada. origem: a sessão de onde ela nasceu (base da nova sessão).
@Composable
fun AcoesGuardadas(sessao: SessaoDeAcesso, p: Pendencia, origem: Sessao?, aoMudar: (String) -> Unit) {
    val dao = sessao.banco.pendencias()
    val agenda = sessao.banco.agenda()
    var modo by remember(p.id) { mutableStateOf(Modo.NENHUM) }
    var data by remember(p.id) { mutableStateOf<LocalDate?>(null) }
    var hora by remember(p.id) { mutableStateOf<LocalTime?>(null) }
    var motivo by remember(p.id) { mutableStateOf("") }
    var terapeutaId by remember(p.id) { mutableStateOf(origem?.terapeutaId) }
    var terapeutas by remember { mutableStateOf(emptyList<Terapeuta>()) }
    var escolhendoData by remember { mutableStateOf(false) }
    var escolhendoHora by remember { mutableStateOf(false) }
    var conflitos by remember { mutableStateOf<List<String>?>(null) }
    var erro by remember { mutableStateOf<String?>(null) }
    var salvando by remember { mutableStateOf(false) }
    val escopo = rememberCoroutineScope()
    val remarcar = p.tipo == TipoPendencia.REMARCAR

    LaunchedEffect(modo) {
        if (modo == Modo.NOVA_DATA && origem != null) erro = tentar { terapeutas = sessao.banco.cadastros().terapeutasQueAtendem(origem.terapiaId) }
    }

    fun executar(ok: String, acao: suspend () -> Unit) {
        salvando = true
        escopo.launch {
            erro = tentar(acao)
            salvando = false
            if (erro == null) aoMudar(ok)
        }
    }

    fun salvarNovaData(forcar: Boolean) {
        val o = origem ?: return
        val d = data; val h = hora
        val t = terapeutaId?.takeIf { id -> terapeutas.any { it.id == id } } // a anterior pode ter sido desativada
        erro = when {
            t == null -> "Escolha a terapeuta."
            d == null || h == null -> "Escolha a data e a hora."
            !d.atTime(h).isAfter(LocalDateTime.now()) -> "Esse horário já passou. Escolha um horário daqui para a frente."
            else -> null
        }
        if (erro != null || t == null || d == null || h == null) return
        // Remarcar repete a mesma sessão (mesmo tipo); a próxima de um avulso é uma sessão comum (anamnese não se repete).
        val nova = o.copy(
            id = 0, terapeutaId = t, inicio = d.atTime(h), familia = Resposta.AGUARDANDO, terapeuta = Resposta.AGUARDANDO, desfecho = null,
            origem = if (remarcar) Origem.REMARCACAO else Origem.AVULSA, tipo = if (remarcar) o.tipo else TipoSessao.COMUM,
            familiaConfirmouEm = null, lancadaDepoisEm = null, lancadaPor = null,
        )
        salvando = true
        escopo.launch {
            var avisos = emptyList<String>()
            erro = tentar {
                if (!forcar) avisos = conflitosEmTexto(agenda, nova)
                if (avisos.isEmpty()) dao.marcarNovaData(p.id, nova.paraLinha(), LocalDateTime.now(), sessao.usuariaId())
            }
            salvando = false
            if (avisos.isNotEmpty()) conflitos = avisos
            else if (erro == null) aoMudar("Sessão marcada para ${dataBonita(d)} às ${h.format(HORA)}.")
        }
    }

    fun motivoOk(): String? = motivo.trim().takeIf { it.isNotEmpty() } ?: run { erro = "Escreva o motivo."; null }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (modo) {
            Modo.NENHUM -> {
                if (origem != null) OutlinedButton({ modo = Modo.NOVA_DATA }, Modifier.fillMaxWidth()) {
                    Text(if (remarcar) "Marcar nova data" else "Marcar próxima sessão", style = MaterialTheme.typography.titleMedium)
                }
                if (!remarcar) Escolha<Int>("Lembrar de entrar em contato em", null, DIAS_PARA_LEMBRAR, { "$it dias" }) { dias ->
                    executar("Vou lembrar em $dias dias.") { dao.lembrarDepois(p.id, dias, LocalDateTime.now(), sessao.usuariaId()) }
                }
                OutlinedButton({ modo = Modo.ENCERRAR }, Modifier.fillMaxWidth()) {
                    Text(if (remarcar) "Não vai remarcar" else "Não vai continuar", style = MaterialTheme.typography.titleMedium)
                }
                TextButton({ modo = Modo.ADIAR }) { Text("Adiar", style = MaterialTheme.typography.titleMedium) }
            }
            Modo.NOVA_DATA -> {
                Escolha("Terapeuta", terapeutas.firstOrNull { it.id == terapeutaId }, terapeutas, { it.nome }) { terapeutaId = it.id }
                OutlinedButton({ escolhendoData = true }, Modifier.fillMaxWidth()) {
                    Text("Data: ${data?.let(::dataBonita) ?: "escolher"} ▾", style = MaterialTheme.typography.titleMedium)
                }
                OutlinedButton({ escolhendoHora = true }, Modifier.fillMaxWidth()) {
                    Text("Hora: ${hora?.format(HORA) ?: "escolher"} ▾", style = MaterialTheme.typography.titleMedium)
                }
                BotaoGrande(if (salvando) "Salvando…" else "Marcar", { salvarNovaData(forcar = false) }, habilitado = !salvando)
            }
            Modo.ENCERRAR, Modo.ADIAR -> {
                if (modo == Modo.ADIAR) OutlinedButton({ escolhendoData = true }, Modifier.fillMaxWidth()) {
                    Text("Lembrar de novo em: ${data?.let(::dataBonita) ?: "escolher"} ▾", style = MaterialTheme.typography.titleMedium)
                }
                Text(AVISO_OBSERVACAO, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
                CampoTexto(motivo, { motivo = it.take(MAX_MOTIVO) }, "Motivo")
                BotaoGrande("Confirmar", habilitado = !salvando, aoClicar = {
                    val m = motivoOk()
                    val d = data
                    if (m != null) when {
                        modo == Modo.ENCERRAR ->
                            executar("Resolvida.") { dao.resolver(p.id, (if (remarcar) "Não vai remarcar: " else "Não vai continuar: ") + m, LocalDateTime.now(), sessao.usuariaId()) }
                        d == null -> erro = "Escolha a data."
                        else -> executar("Adiada para ${dataBonita(d)}.") { dao.adiar(p.id, d.atTime(9, 0), m) }
                    }
                })
            }
        }
        if (modo != Modo.NENHUM) TextButton({ modo = Modo.NENHUM; erro = null }) { Text("Cancelar", style = MaterialTheme.typography.titleMedium) }
        MensagemErro(erro)
    }

    val hoje = LocalDate.now()
    // Adiar é sempre para depois de hoje; nova sessão pode ser hoje mais tarde.
    if (escolhendoData) DialogoData(data ?: hoje.plusDays(1), if (modo == Modo.ADIAR) hoje.plusDays(1) else hoje, { escolhendoData = false }) {
        data = it; escolhendoData = false
    }
    if (escolhendoHora) DialogoHora(hora ?: origem?.inicio?.toLocalTime(), { escolhendoHora = false }) { hora = it; escolhendoHora = false }
    conflitos?.let { DialogoConflitos(it, { conflitos = null }) { conflitos = null; salvarNovaData(forcar = true) } }
}
