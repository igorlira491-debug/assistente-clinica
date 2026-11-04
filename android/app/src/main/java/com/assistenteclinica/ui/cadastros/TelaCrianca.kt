package com.assistenteclinica.ui.cadastros

import com.assistenteclinica.acesso.Estado
import com.assistenteclinica.dominio.Papel
import com.assistenteclinica.ui.admin.DadosDaCriancaLgpd
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import com.assistenteclinica.dados.CadastrosDao
import com.assistenteclinica.dados.Crianca
import com.assistenteclinica.dados.CriancaTerapia
import com.assistenteclinica.dados.Responsavel
import com.assistenteclinica.dados.ResponsavelDaCrianca
import com.assistenteclinica.dados.Terapeuta
import com.assistenteclinica.dados.Terapia
import com.assistenteclinica.dominio.AVISO_OBSERVACAO
import com.assistenteclinica.dominio.MAX_OBSERVACAO
import com.assistenteclinica.dominio.TipoCliente
import com.assistenteclinica.dominio.consentimentoValido
import com.assistenteclinica.dominio.nomeValido
import com.assistenteclinica.dominio.telefoneWhatsApp
import com.assistenteclinica.ui.acesso.BotaoGrande
import com.assistenteclinica.ui.acesso.CampoTexto
import com.assistenteclinica.ui.acesso.LocalAtividade
import com.assistenteclinica.ui.acesso.MensagemErro
import com.assistenteclinica.ui.acesso.TelaComRolagem
import com.assistenteclinica.ui.agenda.HorariosFixos
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private val DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy")

// criancaId null = criança nova: terapias e responsáveis só aparecem depois de salvar (precisam do id).
@Composable
fun TelaCrianca(sessao: SessaoDeAcesso, criancaId: Long?, aoVoltar: () -> Unit, modifier: Modifier = Modifier) {
    val dao = sessao.banco.cadastros()
    var id by rememberSaveable { mutableStateOf(criancaId) } // sobrevive a girar a tela
    var versao by rememberSaveable { mutableIntStateOf(0) } // muda depois de cada ação, para reler a ficha (saveable: identifica o formulário)
    var crianca by remember { mutableStateOf<Crianca?>(null) }
    var terapias by remember { mutableStateOf(emptyList<Terapia>()) }
    var vinculos by remember { mutableStateOf(emptyList<CriancaTerapia>()) }
    var terapeutas by remember { mutableStateOf(emptyList<Terapeuta>()) }
    var opcoes by remember { mutableStateOf(emptyMap<Long, List<Terapeuta>>()) } // terapia -> terapeutas ativas
    var responsaveis by remember { mutableStateOf(emptyList<ResponsavelDaCrianca>()) }
    var erro by remember { mutableStateOf<String?>(null) }
    var aviso by remember { mutableStateOf<String?>(null) }
    var salvando by remember { mutableStateOf(false) } // dois toques em Salvar não criam duas crianças
    val escopo = rememberCoroutineScope()

    LaunchedEffect(id, versao) {
        val c = id ?: return@LaunchedEffect
        tentar {
            crianca = dao.crianca(c)
            terapias = dao.terapias()
            terapeutas = dao.terapeutas()
            vinculos = dao.terapiasDaCrianca(c)
            opcoes = terapias.associate { it.id to dao.terapeutasQueAtendem(it.id) }
            responsaveis = dao.responsaveisDaCrianca(c)
        }?.let { erro = it }
    }

    // Roda uma ação no banco, mostra o resultado e relê a ficha.
    fun executar(ok: String?, acao: suspend CadastrosDao.() -> Unit) = escopo.launch {
        aviso = null
        erro = tentar { dao.acao() }
        if (erro == null) { aviso = ok; versao++ }
    }

    val c = crianca
    TelaComRolagem(c?.nome ?: "Nova criança", modifier) {
        if (id == null || c != null) key(c) {
            FormularioDados(c, salvando) { nova ->
                aviso = null
                salvando = true
                escopo.launch {
                    erro = tentar { id = dao.salvarCrianca(nova) }
                    salvando = false
                    if (erro == null) { aviso = "Dados salvos."; versao++ }
                }
            }
        }
        if (c != null) {
            OutlinedButton({ executar(if (c.ativa) "Criança marcada como inativa." else "Criança reativada.") { salvarCrianca(c.copy(ativa = !c.ativa)) } }) {
                Text(if (c.ativa) "Marcar como inativa (saiu da clínica)" else "Reativar")
            }
        }
        MensagemErro(erro)
        MensagemOk(aviso)

        val cid = id
        if (cid != null && c != null) {
            HorizontalDivider()
            Text("Terapias", style = MaterialTheme.typography.titleLarge)
            val porTerapia = vinculos.associateBy { it.terapiaId }
            terapias.filter { it.ativa || it.id in porTerapia }.forEach { tp ->
                val atual = porTerapia[tp.id]?.let { v -> terapeutas.firstOrNull { it.id == v.terapeutaId } }
                LinhaTerapia(tp, atual, opcoes[tp.id].orEmpty()) { escolhida ->
                    executar(null) {
                        if (escolhida == null) removerTerapia(cid, tp.id) else definirTerapia(CriancaTerapia(cid, tp.id, escolhida.id))
                    }
                }
            }
            if (terapias.isEmpty()) Text("Cadastre as terapias primeiro (menu Terapias).")

            if (c.tipo == TipoCliente.FIXO) {
                HorizontalDivider()
                HorariosFixos(sessao, cid, terapias.filter { tp -> tp.ativa && vinculos.any { it.terapiaId == tp.id } }, versao)
            }

            HorizontalDivider()
            Text("Responsáveis", style = MaterialTheme.typography.titleLarge)
            responsaveis.forEach { r -> CartaoResponsavel(r, cid, ::executar) }
            key(versao) { FormularioResponsavel(sessao, dao, cid, responsaveis, ::executar) { erro = it } }

            // LGPD (Bloco 8, etapa 7): só a dona vê.
            if ((sessao.estado.value as? Estado.Destravado)?.usuaria?.papel == Papel.DONA) {
                HorizontalDivider()
                DadosDaCriancaLgpd(sessao, cid, aoApagar = aoVoltar)
            }
        }
        TextButton(aoVoltar) { Text("Voltar", style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun FormularioDados(c: Crianca?, salvando: Boolean, aoSalvar: (Crianca) -> Unit) {
    var nome by rememberSaveable { mutableStateOf(c?.nome ?: "") }
    var tipo by rememberSaveable { mutableStateOf(c?.tipo ?: TipoCliente.FIXO) }
    var obs by rememberSaveable { mutableStateOf(c?.observacao ?: "") }
    var erro by remember { mutableStateOf<String?>(null) }
    val atividade = LocalAtividade.current
    CampoTexto(nome, { nome = it }, "Nome da criança")
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        TipoCliente.entries.forEach { t ->
            Row(
                Modifier.selectable(tipo == t, role = Role.RadioButton) { tipo = t },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(tipo == t, null)
                Text(if (t == TipoCliente.FIXO) "Fixo" else "Avulso", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
    Text(AVISO_OBSERVACAO, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        obs, { atividade(); obs = it.take(MAX_OBSERVACAO) }, Modifier.fillMaxWidth(),
        label = { Text("Observação (ex.: mãe só responde à noite)") },
        supportingText = { Text("${obs.length}/$MAX_OBSERVACAO") },
        textStyle = MaterialTheme.typography.titleMedium,
    )
    MensagemErro(erro)
    BotaoGrande(if (salvando) "Salvando…" else "Salvar dados", habilitado = !salvando, aoClicar = {
        if (!nomeValido(nome)) erro = "Digite o nome da criança."
        else { erro = null; aoSalvar((c ?: Crianca(nome = "", tipo = tipo)).copy(nome = nome.trim(), tipo = tipo, observacao = obs.trim())) }
    })
}

@Composable
private fun LinhaTerapia(terapia: Terapia, atual: Terapeuta?, ativas: List<Terapeuta>, aoEscolher: (Terapeuta?) -> Unit) {
    var aberto by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ aberto = true }, Modifier.fillMaxWidth()) {
            val quem = when {
                atual == null -> "Não faz"
                !atual.ativa -> "${atual.nome} (desativada)"
                ativas.none { it.id == atual.id } -> "${atual.nome} (não atende mais)"
                else -> atual.nome
            }
            Text("${terapia.nome}: $quem ▾", style = MaterialTheme.typography.titleMedium)
        }
        DropdownMenu(aberto, { aberto = false }) {
            ativas.forEach { t -> DropdownMenuItem({ Text(t.nome) }, { aberto = false; aoEscolher(t) }) }
            DropdownMenuItem({ Text("Não faz") }, { aberto = false; aoEscolher(null) })
        }
    }
}

@Composable
private fun CartaoResponsavel(r: ResponsavelDaCrianca, criancaId: Long, executar: (String?, suspend CadastrosDao.() -> Unit) -> Unit) {
    val p = r.responsavel
    var consentindo by remember { mutableStateOf(false) }
    var removendo by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(p.nome + if (r.principal) " · Principal" else "", style = MaterialTheme.typography.titleLarge)
            Text(telefoneBonito(p.telefone), style = MaterialTheme.typography.titleMedium)
            val consentiu = p.consentiuEm
            if (consentiu == null) Text("Sem consentimento", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
            else Text("Consentiu em ${consentiu.format(DATA)}" + (p.versaoTermo?.let { " · termo $it" } ?: ""))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!r.principal) OutlinedButton({ executar("${p.nome} agora é a principal.") { vincularResponsavel(criancaId, p, true) } }) {
                    Text("Tornar principal")
                }
                OutlinedButton({ removendo = true }) { Text("Remover") }
            }
            if (consentiu == null) OutlinedButton({ consentindo = true }) { Text("Registrar consentimento") }
        }
    }
    if (removendo) AlertDialog(
        onDismissRequest = { removendo = false },
        title = { Text("Remover responsável") },
        text = {
            Text(
                if (r.criancas <= 1) "Remover ${p.nome}? Ela não é responsável por mais nenhuma criança, então o nome e o telefone serão apagados."
                else "Remover ${p.nome} desta criança? Ela continua como responsável de outra(s) criança(s).",
            )
        },
        confirmButton = {
            TextButton({ removendo = false; executar("${p.nome} foi removida desta criança.") { desvincularResponsavel(criancaId, p.id) } }) {
                Text("Remover")
            }
        },
        dismissButton = { TextButton({ removendo = false }) { Text("Cancelar") } },
    )
    if (consentindo) DialogoConsentimento(aoFechar = { consentindo = false }) { data, termo ->
        consentindo = false
        executar("Consentimento registrado.") { salvarResponsavel(p.copy(consentiuEm = data, versaoTermo = termo)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DialogoConsentimento(aoFechar: () -> Unit, aoConfirmar: (LocalDate, String?) -> Unit) {
    val hoje = LocalDate.now()
    val estado = rememberDatePickerState(
        initialSelectedDateMillis = hoje.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = consentimentoValido(utc(utcTimeMillis), hoje)
        },
    )
    var termo by remember { mutableStateOf("") }
    var escolhendoData by remember { mutableStateOf(false) }
    if (escolhendoData) {
        DatePickerDialog(
            onDismissRequest = { escolhendoData = false },
            confirmButton = {
                TextButton({
                    val ms = estado.selectedDateMillis ?: return@TextButton
                    if (consentimentoValido(utc(ms), hoje)) aoConfirmar(utc(ms), termo.trim().ifEmpty { null })
                }) { Text("Salvar") }
            },
            dismissButton = { TextButton({ escolhendoData = false }) { Text("Voltar") } },
        ) { DatePicker(estado) }
    } else {
        AlertDialog(
            onDismissRequest = aoFechar,
            title = { Text("Registrar consentimento") },
            text = { CampoTexto(termo, { termo = it.take(20) }, "Versão do termo (opcional)") },
            confirmButton = { TextButton({ escolhendoData = true }) { Text("Escolher a data") } },
            dismissButton = { TextButton(aoFechar) { Text("Cancelar") } },
        )
    }
}

private fun utc(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()

@Composable
private fun FormularioResponsavel(
    sessao: SessaoDeAcesso,
    dao: CadastrosDao,
    criancaId: Long,
    atuais: List<ResponsavelDaCrianca>,
    executar: (String?, suspend CadastrosDao.() -> Unit) -> Unit,
    aoErro: (String?) -> Unit,
) {
    var nome by rememberSaveable { mutableStateOf("") }
    var telefone by rememberSaveable { mutableStateOf("") }
    var mesmaPessoa by remember { mutableStateOf<Responsavel?>(null) } // número já cadastrado: pedir confirmação
    val escopo = rememberCoroutineScope()
    val escolherContato = rememberSeletorDeContato(sessao) { n, num -> if (nome.isBlank()) nome = n; telefone = num }

    Text("Adicionar responsável", style = MaterialTheme.typography.titleMedium)
    OutlinedButton(escolherContato, Modifier.fillMaxWidth()) { Text("Escolher dos contatos") }
    CampoTexto(nome, { nome = it }, "Nome do responsável")
    CampoTexto(telefone, { telefone = it }, "WhatsApp com DDD", KeyboardType.Phone)
    BotaoGrande("Adicionar", {
        val tel = telefoneWhatsApp(telefone)
        when {
            !nomeValido(nome) -> aoErro("Digite o nome do responsável.")
            tel == null -> aoErro(ERRO_TELEFONE)
            atuais.any { it.responsavel.telefone == tel } -> aoErro("Esse número já é de um responsável desta criança.")
            else -> escopo.launch {
                aoErro(null)
                val existente = runCatching { dao.responsavelPorTelefone(tel) }.getOrNull()
                if (existente != null) mesmaPessoa = existente
                else executar("${nome.trim()} foi adicionada.") { vincularResponsavel(criancaId, Responsavel(nome = nome.trim(), telefone = tel), false) }
            }
        }
    })

    mesmaPessoa?.let { r ->
        AlertDialog(
            onDismissRequest = { mesmaPessoa = null },
            title = { Text("Número já cadastrado") },
            text = { Text("Este número já é de ${r.nome} (responsável de outra criança). Vincular a mesma pessoa?") },
            confirmButton = {
                TextButton({ mesmaPessoa = null; executar("${r.nome} foi vinculada.") { vincularResponsavel(criancaId, r, false) } }) {
                    Text("Vincular")
                }
            },
            dismissButton = { TextButton({ mesmaPessoa = null }) { Text("Cancelar") } },
        )
    }
}
