package com.assistenteclinica.ui.pendencias

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.assistenteclinica.acesso.SessaoDeAcesso
import com.assistenteclinica.dados.Pendencia
import com.assistenteclinica.dados.PendenciasDao
import com.assistenteclinica.dados.paraDominio
import com.assistenteclinica.dominio.DIA_DE_LANCAR_O_MES
import com.assistenteclinica.dominio.Desfecho
import com.assistenteclinica.dominio.Grupo
import com.assistenteclinica.dominio.PendenciaCalculada
import com.assistenteclinica.dominio.Sessao
import com.assistenteclinica.dominio.TipoPendencia
import com.assistenteclinica.dominio.dataBonita
import com.assistenteclinica.dominio.grupo
import com.assistenteclinica.dominio.jaDisponivel
import com.assistenteclinica.dominio.pendenciasCalculadas
import com.assistenteclinica.ui.acesso.BotaoGrande
import com.assistenteclinica.ui.acesso.MensagemErro
import com.assistenteclinica.ui.acesso.TelaComRolagem
import com.assistenteclinica.ui.agenda.HORA
import com.assistenteclinica.ui.agenda.TelaLancarMes
import com.assistenteclinica.ui.backup.rememberSalvarBackup
import com.assistenteclinica.ui.cadastros.MensagemOk
import com.assistenteclinica.ui.cadastros.tentar
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Uma linha da lista: calculada (a partir das sessões) ou guardada (tabela Pendencia).
private sealed interface Item { val prazo: LocalDateTime; val jaDisponivel: Boolean }
private data class Calculada(val p: PendenciaCalculada) : Item {
    override val prazo get() = p.prazo
    override val jaDisponivel get() = true // só aparecem quando já é hora
}
private data class Guardada(val p: Pendencia, val origem: Sessao?) : Item {
    override val prazo get() = p.prazo
    override val jaDisponivel get() = jaDisponivel(p.tipo, adiada = p.motivoAdiamento != null)
}

private class Nomes(val criancas: Map<Long, String>, val terapias: Map<Long, String>, val terapeutas: Map<Long, String>) {
    fun sessao(s: Sessao) = "${criancas[s.criancaId]} · ${dataBonita(s.inicio.toLocalDate())} ${s.inicio.format(HORA)} · ${terapias[s.terapiaId]}"
}

private val PRAZO = DateTimeFormatter.ofPattern("dd/MM HH:mm")
private val MES = DateTimeFormatter.ofPattern("MMMM", Locale.forLanguageTag("pt-BR"))
private const val DIAS_A_FRENTE = 4L // cobre a sexta que confirma a segunda

@Composable
fun TelaPendencias(sessao: SessaoDeAcesso, aoAbrirAgenda: () -> Unit, aoAbrirMenu: () -> Unit, modifier: Modifier = Modifier) {
    var lancando by rememberSaveable { mutableStateOf(false) }
    var versao by remember { mutableIntStateOf(0) } // muda depois de cada ação: relê tudo
    var minuto by remember { mutableIntStateOf(0) } // muda a cada minuto: só recalcula o que depende da hora
    var sessoes by remember { mutableStateOf(emptyList<Sessao>()) }
    var guardadas by remember { mutableStateOf(emptyList<Guardada>()) }
    var haMes by remember { mutableStateOf(false) }
    var ocupado by remember { mutableStateOf(false) } // dois toques seguidos não registram duas vezes
    var itens by remember { mutableStateOf(emptyList<Item>()) }
    var nomes by remember { mutableStateOf(Nomes(emptyMap(), emptyMap(), emptyMap())) }
    var agora by remember { mutableStateOf(LocalDateTime.now()) }
    var erro by remember { mutableStateOf<String?>(null) }
    var aviso by remember { mutableStateOf<String?>(null) }
    val escopo = rememberCoroutineScope()

    if (lancando) {
        val fechar: () -> Unit = { lancando = false; versao++ }
        BackHandler(onBack = fechar)
        TelaLancarMes(sessao, fechar, modifier)
        return
    }

    val dao = sessao.banco.pendencias()
    val agenda = sessao.banco.agenda()
    val cadastros = sessao.banco.cadastros()
    // As calculadas dependem da hora: relê a cada minuto, além de depois de cada ação.
    LaunchedEffect(Unit) { while (true) { delay(60_000); minuto++ } }
    LaunchedEffect(versao) {
        val hoje = LocalDateTime.now()
        erro = tentar {
            nomes = Nomes(
                cadastros.criancas().associate { it.id to it.nome },
                cadastros.terapias().associate { it.id to it.nome },
                cadastros.terapeutas().associate { it.id to it.nome },
            )
            sessoes = dao.sessoesEmAberto(hoje.plusDays(DIAS_A_FRENTE)).map { it.paraDominio() }
            haMes = hoje.dayOfMonth >= DIA_DE_LANCAR_O_MES &&
                agenda.previaDoMes(YearMonth.from(hoje).plusMonths(1), hoje.toLocalDate()).isNotEmpty()
            guardadas = dao.abertas().map { p -> Guardada(p, p.sessaoId?.let { agenda.sessao(it)?.sessao?.paraDominio() }) }
        }
    }
    // As sessões são relidas depois de cada ação e sempre que a tela abre (o app trava após 3 min parado e, ao
    // destravar, a tela é criada de novo); o relógio de 1 min só recalcula prazos e grupos, sem tocar no banco.
    LaunchedEffect(sessoes, guardadas, haMes, minuto) {
        agora = LocalDateTime.now()
        itens = (pendenciasCalculadas(sessoes, agora, haMes).map(::Calculada) + guardadas).sortedBy { it.prazo }
    }

    fun executar(ok: String, acao: suspend PendenciasDao.() -> Unit) {
        if (ocupado) return
        ocupado = true
        escopo.launch {
            aviso = null
            erro = tentar { dao.acao() }
            ocupado = false
            if (erro == null) { aviso = ok; versao++ }
        }
    }
    val aoMudar: (String) -> Unit = { aviso = it; versao++ }
    val salvarBackup = rememberSalvarBackup(sessao, aoMudar) { erro = it }

    val porGrupo = itens.groupBy { grupo(it.prazo, agora, it.jaDisponivel) }
    val atrasadas = porGrupo[Grupo.ATRASADA].orEmpty().size
    TelaComRolagem("O que fazer", modifier) {
        Text(
            if (itens.isEmpty()) "Tudo em dia! 🎉" else "${itens.size} tarefa(s)" + if (atrasadas > 0) " ($atrasadas atrasada(s))" else "",
            style = MaterialTheme.typography.titleLarge,
        )
        MensagemErro(erro)
        MensagemOk(aviso)
        listOf(Grupo.ATRASADA to "🔴 Atrasadas", Grupo.HOJE to "🟠 Para hoje", Grupo.PROXIMA to "⚪ Próximas").forEach { (g, titulo) ->
            val doGrupo = porGrupo[g].orEmpty()
            if (doGrupo.isNotEmpty()) Text(titulo, style = MaterialTheme.typography.titleLarge)
            doGrupo.forEach { item ->
                key(chave(item)) { Cartao(g == Grupo.ATRASADA) {
                    when (item) {
                        is Calculada -> AcoesCalculada(item.p, g == Grupo.ATRASADA, nomes, sessao, ::executar) { lancando = true }
                        is Guardada -> if (item.p.tipo == TipoPendencia.SALVAR_BACKUP) {
                            Text(tituloGuardada(item.p.tipo), style = MaterialTheme.typography.titleLarge)
                            Text("Toque e escolha o Google Drive da clínica.")
                            BotaoGrande("Salvar no Drive", salvarBackup)
                        } else {
                            Text(tituloGuardada(item.p.tipo), style = MaterialTheme.typography.titleLarge)
                            item.origem?.let { Text(nomes.sessao(it)) } ?: item.p.criancaId?.let { Text(nomes.criancas[it].orEmpty()) }
                            Text("Prazo: ${item.p.prazo.format(PRAZO)}")
                            item.p.motivoAdiamento?.let { Text("Adiada: $it", color = MaterialTheme.colorScheme.secondary) }
                            AcoesGuardadas(sessao, item.p, item.origem, aoMudar)
                        }
                    }
                } }
            }
        }
        BotaoGrande("Agenda", aoAbrirAgenda)
        OutlinedButton(aoAbrirMenu, Modifier.fillMaxWidth()) { Text("Menu", style = MaterialTheme.typography.titleMedium) }
    }
}

// Identidade estável do cartão: a lista se relê a cada minuto e um cartão novo pode entrar acima.
private fun chave(item: Item): String = when (item) {
    is Calculada -> "${item.p.tipo}:${item.p.terapeutaId}:${item.p.sessoes.joinToString(",") { it.id.toString() }}"
    is Guardada -> "guardada:${item.p.id}"
}

private fun tituloGuardada(t: TipoPendencia) = when (t) {
    TipoPendencia.REMARCAR -> "Remarcar"
    TipoPendencia.DEFINIR_PROXIMO_PASSO -> "Definir próximo passo"
    TipoPendencia.ENTRAR_EM_CONTATO -> "Entrar em contato"
    TipoPendencia.SALVAR_BACKUP -> "Salvar backup"
    else -> t.name
}

@Composable
private fun Cartao(atrasada: Boolean, conteudo: @Composable () -> Unit) {
    val cores = if (atrasada) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.cardColors()
    Card(Modifier.fillMaxWidth(), colors = cores) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { conteudo() }
    }
}

@Composable
private fun AcoesCalculada(
    p: PendenciaCalculada,
    atrasada: Boolean,
    nomes: Nomes,
    sessao: SessaoDeAcesso,
    executar: (String, suspend PendenciasDao.() -> Unit) -> Unit,
    aoLancar: () -> Unit,
) {
    val usuaria = sessao.usuariaId()
    fun agora() = LocalDateTime.now()
    when (p.tipo) {
        TipoPendencia.ENVIAR_CONFIRMACAO -> {
            val s = p.sessoes.single()
            Text(if (atrasada) "Ligar para a família" else "Confirmar com a família", style = MaterialTheme.typography.titleLarge)
            Text(nomes.sessao(s))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ executar("Confirmada pela família.") { responder(s.id, true, true, agora(), usuaria) } }) { Text("✓ Confirmou") }
                OutlinedButton({ executar("Vai para Remarcar.") { responder(s.id, true, false, agora(), usuaria) } }) { Text("✗ Não vem") }
            }
        }
        TipoPendencia.AVISAR_TERAPEUTA -> {
            Text("Avisar ${nomes.terapeutas[p.terapeutaId]}", style = MaterialTheme.typography.titleLarge)
            p.sessoes.forEach { s ->
                Text(nomes.sessao(s))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ executar("Terapeuta confirmou.") { responder(s.id, false, true, agora(), usuaria) } }) { Text("✓ Confirmou") }
                    OutlinedButton({ executar("Vai para Remarcar.") { responder(s.id, false, false, agora(), usuaria) } }) { Text("✗ Não pode") }
                }
            }
        }
        TipoPendencia.REGISTRAR_COMO_FOI -> {
            val s = p.sessoes.single()
            Text("Como foi a sessão?", style = MaterialTheme.typography.titleLarge)
            Text(nomes.sessao(s))
            ComoFoi { d -> executar("Registrado.") { encerrar(s.id, d, agora()) } }
        }
        TipoPendencia.LANCAR_AGENDA_DO_MES -> {
            Text("Lançar a agenda de ${YearMonth.from(agora()).plusMonths(1).format(MES)}", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(aoLancar) { Text("Abrir lançamento", style = MaterialTheme.typography.titleMedium) }
        }
        else -> Text(p.tipo.name)
    }
}

// Os três desfechos que a secretária registra (Cancelada pela clínica vem do "Clínica fechada" ou do Remarcar).
@Composable
fun ComoFoi(aoEscolher: (Desfecho) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ aoEscolher(Desfecho.REALIZADA) }) { Text("Realizada") }
        OutlinedButton({ aoEscolher(Desfecho.FALTOU) }) { Text("Faltou") }
        OutlinedButton({ aoEscolher(Desfecho.NAO_ACONTECEU) }) { Text("Não aconteceu") }
    }
}
