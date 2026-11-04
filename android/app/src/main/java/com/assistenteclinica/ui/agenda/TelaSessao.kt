package com.assistenteclinica.ui.agenda

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import com.assistenteclinica.dados.AgendaDao
import com.assistenteclinica.dados.PendenciasDao
import com.assistenteclinica.dados.Crianca
import com.assistenteclinica.dados.CriancaTerapia
import com.assistenteclinica.dados.SessaoNaAgenda
import com.assistenteclinica.dados.Terapeuta
import com.assistenteclinica.dados.Terapia
import com.assistenteclinica.dados.paraDominio
import com.assistenteclinica.dados.paraLinha
import com.assistenteclinica.dominio.Origem
import com.assistenteclinica.dominio.QuemPediu
import com.assistenteclinica.dominio.Resposta
import com.assistenteclinica.dominio.Sessao
import com.assistenteclinica.dominio.Situacao
import com.assistenteclinica.dominio.TipoSessao
import com.assistenteclinica.dominio.combinaComBusca
import com.assistenteclinica.dominio.dataBonita
import com.assistenteclinica.dominio.momentoComoFoi
import com.assistenteclinica.dominio.duracaoValida
import com.assistenteclinica.dominio.marcarSeLancadaDepois
import com.assistenteclinica.dominio.podeApagar
import com.assistenteclinica.dominio.remarcar
import com.assistenteclinica.dominio.situacao
import com.assistenteclinica.ui.acesso.BotaoGrande
import com.assistenteclinica.ui.acesso.CampoTexto
import com.assistenteclinica.ui.acesso.MensagemErro
import com.assistenteclinica.ui.acesso.TelaComRolagem
import com.assistenteclinica.ui.cadastros.MensagemOk
import com.assistenteclinica.ui.cadastros.tentar
import com.assistenteclinica.ui.pendencias.ComoFoi
import com.assistenteclinica.ui.pendencias.usuariaId
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.launch

private const val HORA_PASSADA = "Esse horário já passou. Escolha um horário daqui para a frente."

// sessaoId null = sessão avulsa nova; depois de salvar, a tela passa a mostrar a sessão criada.
@Composable
fun TelaSessao(
    sessao: SessaoDeAcesso,
    sessaoId: Long?,
    diaInicial: LocalDate,
    aoVoltar: () -> Unit,
    aoMudarDia: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    var id by rememberSaveable { mutableStateOf(sessaoId) }
    val abrir: (Long, LocalDate) -> Unit = { novo, dia -> aoMudarDia(dia); id = novo }
    when (val i = id) {
        null -> NovaSessao(sessao, diaInicial, abrir, aoVoltar, modifier)
        else -> key(i) { SessaoExistente(sessao, i, abrir, aoVoltar, modifier) }
    }
}

// Confere conflitos (a não ser que ela já tenha dito "salvar mesmo assim") e salva. Devolve (erro, conflitos).
private suspend fun conferirESalvar(
    agenda: AgendaDao, s: Sessao, forcar: Boolean, ignorar: Long = 0, salvar: suspend () -> Unit,
): Pair<String?, List<String>> {
    var avisos = emptyList<String>()
    val erro = tentar {
        if (!forcar) avisos = conflitosEmTexto(agenda, s, ignorar)
        if (avisos.isEmpty()) salvar()
    }
    return erro to avisos
}

@Composable
private fun NovaSessao(sessao: SessaoDeAcesso, diaInicial: LocalDate, aoSalvar: (Long, LocalDate) -> Unit, aoVoltar: () -> Unit, modifier: Modifier) {
    val cadastros = sessao.banco.cadastros()
    val agenda = sessao.banco.agenda()
    val hoje = LocalDate.now()
    var criancas by remember { mutableStateOf(emptyList<Crianca>()) }
    var terapias by remember { mutableStateOf(emptyList<Terapia>()) }
    var vinculos by remember { mutableStateOf(emptyList<CriancaTerapia>()) }
    var opcoes by remember { mutableStateOf(emptyList<Terapeuta>()) }
    var busca by rememberSaveable { mutableStateOf("") }
    var criancaId by rememberSaveable { mutableStateOf<Long?>(null) }
    var terapiaId by rememberSaveable { mutableStateOf<Long?>(null) }
    var terapeutaId by rememberSaveable { mutableStateOf<Long?>(null) }
    var data by rememberSaveable { mutableStateOf(maxOf(diaInicial, hoje)) }
    var hora by rememberSaveable { mutableStateOf<LocalTime?>(null) }
    var tipo by rememberSaveable { mutableStateOf(TipoSessao.COMUM) }
    var duracao by rememberSaveable { mutableStateOf("") }
    var escolhendoData by remember { mutableStateOf(false) }
    var escolhendoHora by remember { mutableStateOf(false) }
    var conflitos by remember { mutableStateOf<List<String>?>(null) }
    var confirmandoPassado by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf<String?>(null) }
    var salvando by remember { mutableStateOf(false) } // dois toques em Salvar não criam duas sessões
    val escopo = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        erro = tentar { criancas = cadastros.criancas().filter { it.ativa }; terapias = cadastros.terapias() }
    }
    LaunchedEffect(criancaId) { criancaId?.let { c -> erro = tentar { vinculos = cadastros.terapiasDaCrianca(c) } } }
    LaunchedEffect(terapiaId) { terapiaId?.let { t -> erro = tentar { opcoes = cadastros.terapeutasQueAtendem(t) } } }

    // passadoOk: ela já confirmou que a sessão foi mesmo nesse dia (encaixe que ficou sem registro).
    fun salvar(forcar: Boolean, passadoOk: Boolean = false) {
        val c = criancaId; val t = terapiaId; val h = hora; val d = duracao.toIntOrNull()
        val p = terapeutaId?.takeIf { id -> opcoes.any { it.id == id } } // a da ficha pode estar desativada
        erro = when {
            c == null -> "Escolha a criança."
            t == null -> "Escolha a terapia."
            p == null -> "Escolha a terapeuta."
            h == null -> "Escolha a hora."
            d == null || !duracaoValida(d) -> "A duração precisa ser de 5 a 240 minutos."
            else -> null
        }
        if (erro != null || c == null || t == null || p == null || h == null || d == null) return
        val agora = LocalDateTime.now()
        if (!passadoOk && data.atTime(h) < agora) { confirmandoPassado = true; return }
        val nova = marcarSeLancadaDepois(Sessao(0, c, t, p, data.atTime(h), d, origem = Origem.AVULSA, tipo = tipo), agora, sessao.usuariaId())
        salvando = true
        escopo.launch {
            var novoId = 0L
            val (e, avisos) = conferirESalvar(agenda, nova, forcar) { novoId = agenda.salvarSessao(nova.paraLinha()) }
            erro = e
            conflitos = avisos.ifEmpty { null }
            salvando = false
            if (e == null && avisos.isEmpty()) aoSalvar(novoId, data)
        }
    }

    TelaComRolagem("Nova sessão", modifier) {
        val crianca = criancas.firstOrNull { it.id == criancaId }
        if (crianca == null) CampoTexto(busca, { busca = it }, "Buscar criança")
        Escolha("Criança", crianca, criancas.filter { combinaComBusca(it.nome, busca) }, { it.nome }) {
            criancaId = it.id; terapiaId = null; terapeutaId = null; vinculos = emptyList()
        }
        if (crianca != null) {
            val daCrianca = terapias.filter { tp -> tp.ativa && vinculos.any { it.terapiaId == tp.id } }
            if (daCrianca.isEmpty()) Text("Essa criança ainda não tem terapia na ficha (menu Crianças).")
            else Escolha("Terapia", daCrianca.firstOrNull { it.id == terapiaId }, daCrianca, { it.nome }) { tp ->
                terapiaId = tp.id
                terapeutaId = vinculos.first { it.terapiaId == tp.id }.terapeutaId
                duracao = tp.duracaoMin.toString()
            }
        }
        if (terapiaId != null) {
            Escolha("Terapeuta", opcoes.firstOrNull { it.id == terapeutaId }, opcoes, { it.nome }) { terapeutaId = it.id }
            OutlinedButton({ escolhendoData = true }, Modifier.fillMaxWidth()) { Text("Data: ${dataBonita(data)} ▾", style = MaterialTheme.typography.titleMedium) }
            OutlinedButton({ escolhendoHora = true }, Modifier.fillMaxWidth()) {
                Text("Hora: ${hora?.format(HORA) ?: "escolher"} ▾", style = MaterialTheme.typography.titleMedium)
            }
            EscolhaTipo(tipo) { tipo = it }
            CampoTexto(duracao, { novo -> duracao = novo.filter { it.isDigit() }.take(3) }, "Duração (minutos)", KeyboardType.Number)
        }
        MensagemErro(erro)
        BotaoGrande(if (salvando) "Salvando…" else "Salvar sessão", { salvar(forcar = false) }, habilitado = !salvando)
        TextButton(aoVoltar) { Text("Voltar", style = MaterialTheme.typography.titleMedium) }
    }

    // Sem data mínima: dá para lançar um encaixe que já aconteceu (pede confirmação e fica marcado).
    if (escolhendoData) DialogoData(data, null, { escolhendoData = false }) { data = it; escolhendoData = false }
    if (escolhendoHora) DialogoHora(hora, { escolhendoHora = false }) { hora = it; escolhendoHora = false }
    if (confirmandoPassado) AlertDialog(
        onDismissRequest = { confirmandoPassado = false },
        title = { Text("Esse horário já passou") },
        text = { Text("Tem certeza que a sessão foi em ${dataBonita(data)} às ${hora?.format(HORA)}? Ela fica marcada como lançada depois do dia.") },
        confirmButton = { TextButton({ confirmandoPassado = false; salvar(forcar = false, passadoOk = true) }) { Text("Sim, foi nesse dia") } },
        dismissButton = { TextButton({ confirmandoPassado = false }) { Text("Voltar") } },
    )
    // "Salvar mesmo assim" depois de um conflito não pergunta de novo sobre o passado.
    conflitos?.let { DialogoConflitos(it, { conflitos = null }) { conflitos = null; salvar(forcar = true, passadoOk = true) } }
}

@Composable
private fun SessaoExistente(sessao: SessaoDeAcesso, id: Long, aoAbrir: (Long, LocalDate) -> Unit, aoVoltar: () -> Unit, modifier: Modifier) {
    val cadastros = sessao.banco.cadastros()
    val agenda = sessao.banco.agenda()
    var versao by remember { mutableIntStateOf(0) }
    var lida by remember { mutableStateOf<SessaoNaAgenda?>(null) }
    var opcoes by remember { mutableStateOf(emptyList<Terapeuta>()) }
    var terapeutaId by rememberSaveable { mutableStateOf<Long?>(null) }
    var tipo by rememberSaveable { mutableStateOf<TipoSessao?>(null) }
    var remarcando by rememberSaveable { mutableStateOf(false) }
    var novaData by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    var novaHora by rememberSaveable { mutableStateOf<LocalTime?>(null) }
    var quem by rememberSaveable { mutableStateOf<QuemPediu?>(null) }
    var escolhendoData by remember { mutableStateOf(false) }
    var escolhendoHora by remember { mutableStateOf(false) }
    var apagando by remember { mutableStateOf(false) }
    var conflitos by remember { mutableStateOf<List<String>?>(null) }
    var aoConfirmarConflito by remember { mutableStateOf<() -> Unit>({}) }
    var erro by remember { mutableStateOf<String?>(null) }
    var aviso by remember { mutableStateOf<String?>(null) }
    var salvando by remember { mutableStateOf(false) }
    var remarcarAberto by remember { mutableStateOf(false) }
    val escopo = rememberCoroutineScope()

    LaunchedEffect(versao) {
        erro = tentar {
            val l = agenda.sessao(id)
            lida = l
            remarcarAberto = sessao.banco.pendencias().temRemarcarAberto(id)
            if (l != null) {
                val atual = cadastros.terapeutas().filter { it.id == l.sessao.terapeutaId }
                opcoes = (cadastros.terapeutasQueAtendem(l.sessao.terapiaId) + atual).distinctBy { it.id }
                if (terapeutaId == null) terapeutaId = l.sessao.terapeutaId
                if (tipo == null) tipo = l.sessao.tipo
                if (novaHora == null) novaHora = l.sessao.inicio.toLocalTime()
                if (novaData == null) novaData = maxOf(l.sessao.inicio.toLocalDate(), LocalDate.now())
            }
        }
    }

    val l = lida
    if (l == null) {
        TelaComRolagem("Sessão", modifier) {
            MensagemErro(erro)
            TextButton(aoVoltar) { Text("Voltar", style = MaterialTheme.typography.titleMedium) }
        }
        return
    }
    val s = l.sessao.paraDominio()

    // Roda o salvamento; se houver conflito, guarda o mesmo salvamento para o "Salvar mesmo assim".
    fun executar(conferir: Sessao, ignorar: Long, forcar: Boolean, salvar: suspend () -> Unit, depois: () -> Unit) {
        salvando = true
        aviso = null
        escopo.launch {
            val (e, avisos) = conferirESalvar(agenda, conferir, forcar, ignorar, salvar)
            erro = e
            salvando = false
            if (avisos.isNotEmpty()) {
                conflitos = avisos
                aoConfirmarConflito = { executar(conferir, ignorar, true, salvar, depois) }
            } else if (e == null) depois()
        }
    }

    TelaComRolagem(l.crianca, modifier) {
        Text("${dataBonita(s.inicio.toLocalDate())} · ${horario(s)}", style = MaterialTheme.typography.titleLarge)
        Text("${l.terapia} · ${textoOrigem(s.origem)}", style = MaterialTheme.typography.titleMedium)
        Text(textoSituacao(s), color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.titleMedium)
        s.lancadaDepoisEm?.let { em ->
            val quem = sessao.usuarias().firstOrNull { it.id == s.lancadaPor }?.nome ?: "usuária removida"
            Text("Lançada depois do dia: em ${dataBonita(em.toLocalDate())} às ${em.format(HORA)}, por $quem.", style = MaterialTheme.typography.titleMedium)
        }

        // Sessão encerrada ou que não vai acontecer é registro: não muda mais (quem atendeu conta no resumo do mês).
        val aberta = situacao(s) == Situacao.AGUARDANDO || situacao(s) == Situacao.CONFIRMADA
        if (aberta) {
            HorizontalDivider()
            Escolha("Terapeuta", opcoes.firstOrNull { it.id == terapeutaId }, opcoes, { it.nome }) { terapeutaId = it.id }
            tipo?.let { t -> EscolhaTipo(t) { tipo = it } }
            val editada = s.copy(terapeutaId = terapeutaId ?: s.terapeutaId, tipo = tipo ?: s.tipo)
            if (editada != s) BotaoGrande("Salvar alterações", {
                executar(editada, 0, false, { agenda.salvarSessao(editada.paraLinha()) }) { aviso = "Alterações salvas."; versao++ }
            }, habilitado = !salvando)
        } else Text("Terapeuta: ${l.nomeTerapeuta} · ${textoTipo(s.tipo)}", style = MaterialTheme.typography.titleMedium)

        // Respostas: também depois de um "não", enquanto o Remarcar dele estiver aberto (corrigir um toque errado).
        // Depois de remarcada, a original não volta: ela já tem uma sessão nova no lugar.
        if (aberta || (s.desfecho == null && remarcarAberto)) {
            HorizontalDivider()
            // As mesmas ações da tela de pendências, para gerar as mesmas pendências (Remarcar, próximo passo).
            fun registrar(acao: suspend PendenciasDao.() -> Unit) {
                if (salvando) return
                salvando = true
                aviso = null
                escopo.launch {
                    erro = tentar { sessao.banco.pendencias().acao() }
                    salvando = false
                    if (erro == null) { aviso = "Registrado."; versao++ }
                }
            }
            val usuaria = sessao.usuariaId()
            LinhaResposta("Família", s.familia, { registrar { responder(s.id, true, it, LocalDateTime.now(), usuaria) } })
            LinhaResposta("Terapeuta", s.terapeuta, { registrar { responder(s.id, false, it, LocalDateTime.now(), usuaria) } })
            if (aberta && momentoComoFoi(s) <= LocalDateTime.now()) {
                Text("Como foi?", style = MaterialTheme.typography.titleMedium)
                ComoFoi { d -> registrar { encerrar(s.id, d, LocalDateTime.now()) } }
            }
        }
        MensagemErro(erro)
        MensagemOk(aviso)

        if (aberta) {
            HorizontalDivider()
            if (!remarcando) OutlinedButton({ remarcando = true }, Modifier.fillMaxWidth()) {
                Text("Remarcar", style = MaterialTheme.typography.titleMedium)
            } else {
                Text("Remarcar", style = MaterialTheme.typography.titleLarge)
                OutlinedButton({ escolhendoData = true }, Modifier.fillMaxWidth()) {
                    Text("Nova data: ${novaData?.let(::dataBonita) ?: "escolher"} ▾", style = MaterialTheme.typography.titleMedium)
                }
                OutlinedButton({ escolhendoHora = true }, Modifier.fillMaxWidth()) {
                    Text("Nova hora: ${novaHora?.format(HORA) ?: "escolher"} ▾", style = MaterialTheme.typography.titleMedium)
                }
                Text("Quem pediu?", style = MaterialTheme.typography.titleMedium)
                Column {
                    QuemPediu.entries.forEach { q ->
                        Opcao(quem == q, textoQuem(q)) { quem = q }
                    }
                }
                BotaoGrande("Confirmar remarcação", habilitado = !salvando, aoClicar = {
                    val h = novaHora; val q = quem; val nd = novaData
                    if (h == null || q == null || nd == null) erro = "Escolha a nova data, a hora e quem pediu."
                    else if (!nd.atTime(h).isAfter(LocalDateTime.now())) erro = HORA_PASSADA
                    else {
                        val (antiga, nova) = remarcar(s, nd.atTime(h), q)
                        var novoId = 0L
                        executar(nova, s.id, false, { novoId = agenda.remarcarSessao(antiga.paraLinha(), nova.paraLinha(), q) }) {
                            aoAbrir(novoId, nd)
                        }
                    }
                })
                TextButton({ remarcando = false }) { Text("Cancelar remarcação", style = MaterialTheme.typography.titleMedium) }
            }
        }

        if (podeApagar(s)) OutlinedButton({ apagando = true }, Modifier.fillMaxWidth()) {
            Text("Apagar (marcada por engano)", style = MaterialTheme.typography.titleMedium)
        }
        TextButton(aoVoltar) { Text("Voltar", style = MaterialTheme.typography.titleMedium) }
    }

    if (escolhendoData) DialogoData(novaData ?: LocalDate.now(), LocalDate.now(), { escolhendoData = false }) { novaData = it; escolhendoData = false }
    if (escolhendoHora) DialogoHora(novaHora, { escolhendoHora = false }) { novaHora = it; escolhendoHora = false }
    conflitos?.let { DialogoConflitos(it, { conflitos = null }) { conflitos = null; aoConfirmarConflito() } }
    if (apagando) AlertDialog(
        onDismissRequest = { apagando = false },
        title = { Text("Apagar sessão") },
        text = { Text("Apagar a sessão de ${l.crianca} em ${dataBonita(s.inicio.toLocalDate())} às ${s.inicio.format(HORA)}?") },
        confirmButton = {
            TextButton({
                apagando = false
                escopo.launch { erro = tentar { agenda.apagarSessao(s.id) }; if (erro == null) aoVoltar() }
            }) { Text("Apagar") }
        },
        dismissButton = { TextButton({ apagando = false }) { Text("Cancelar") } },
    )
}

@Composable
private fun LinhaResposta(quem: String, atual: Resposta, aoResponder: (Boolean) -> Unit) {
    val texto = when (atual) {
        Resposta.AGUARDANDO -> "aguardando"
        Resposta.SIM -> "✓ confirmou"
        Resposta.NAO -> "✗ não vem"
    }
    Text("$quem: $texto", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ aoResponder(true) }) { Text("✓") }
        OutlinedButton({ aoResponder(false) }) { Text("✗") }
    }
}

private fun textoQuem(q: QuemPediu) = when (q) {
    QuemPediu.FAMILIA -> "Família"
    QuemPediu.TERAPEUTA -> "Terapeuta"
    QuemPediu.CLINICA -> "Clínica"
}

@Composable
private fun EscolhaTipo(atual: TipoSessao, aoEscolher: (TipoSessao) -> Unit) {
    Text("Tipo", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TipoSessao.entries.forEach { t -> Opcao(atual == t, textoTipo(t)) { aoEscolher(t) } }
    }
}

@Composable
private fun Opcao(marcada: Boolean, texto: String, aoEscolher: () -> Unit) {
    Row(Modifier.selectable(marcada, role = Role.RadioButton, onClick = aoEscolher), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(marcada, null)
        Text(texto, style = MaterialTheme.typography.titleMedium)
    }
}
