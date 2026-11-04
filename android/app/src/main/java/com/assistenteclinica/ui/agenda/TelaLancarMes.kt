package com.assistenteclinica.ui.agenda

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.assistenteclinica.dados.paraDominio
import com.assistenteclinica.dados.paraLinha
import com.assistenteclinica.dominio.Sessao
import com.assistenteclinica.dominio.TipoConflito
import com.assistenteclinica.dominio.conflitos
import com.assistenteclinica.dominio.dataBonita
import com.assistenteclinica.dominio.mesParaLancar
import com.assistenteclinica.dominio.sessoesEmTexto
import com.assistenteclinica.ui.acesso.BotaoGrande
import com.assistenteclinica.ui.acesso.MensagemErro
import com.assistenteclinica.ui.acesso.TelaComRolagem
import com.assistenteclinica.ui.cadastros.MensagemOk
import com.assistenteclinica.ui.cadastros.tentar
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

private val MES = DateTimeFormatter.ofPattern("MMMM 'de' yyyy", Locale.forLanguageTag("pt-BR"))

private data class Previa(val sessao: Sessao, val aviso: String?)

// Mostra o que o lançamento vai criar; só grava depois que ela confere e toca em "Criar".
@Composable
fun TelaLancarMes(sessao: SessaoDeAcesso, aoVoltar: () -> Unit, modifier: Modifier = Modifier) {
    val agenda = sessao.banco.agenda()
    val cadastros = sessao.banco.cadastros()
    val hoje = LocalDate.now()
    var mes by rememberSaveable { mutableStateOf(mesParaLancar(hoje)) }
    var versao by remember { mutableIntStateOf(0) }
    var previa by remember { mutableStateOf(emptyList<Previa>()) }
    var criancas by remember { mutableStateOf(emptyMap<Long, String>()) }
    var terapias by remember { mutableStateOf(emptyMap<Long, String>()) }
    var terapeutas by remember { mutableStateOf(emptyMap<Long, String>()) }
    var erro by remember { mutableStateOf<String?>(null) }
    var aviso by remember { mutableStateOf<String?>(null) }
    var salvando by remember { mutableStateOf(false) }
    val escopo = rememberCoroutineScope()

    LaunchedEffect(mes, versao) {
        erro = tentar {
            criancas = cadastros.criancas().associate { it.id to it.nome }
            terapias = cadastros.terapias().associate { it.id to it.nome }
            terapeutas = cadastros.terapeutas().associate { it.id to it.nome }
            val existentes = agenda.sessoesDominioEntre(mes.atDay(1).atStartOfDay(), mes.plusMonths(1).atDay(1).atStartOfDay()).map { it.paraDominio() }
            val novas = agenda.previaDoMes(mes, hoje)
            // As novas ainda não têm id: cada uma confere com as existentes e com as outras novas, não consigo mesma.
            previa = novas.mapIndexed { i, s ->
                val c = conflitos(s, existentes + novas.filterIndexed { j, _ -> j != i }).firstOrNull()
                Previa(s, c?.let { if (it.tipo == TipoConflito.MESMA_TERAPEUTA) "⚠ A terapeuta já tem sessão nesse horário" else "⚠ A criança já tem outra sessão nesse horário" })
            }
        }
    }

    TelaComRolagem("Lançar agenda", modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ mes = mes.minusMonths(1); aviso = null }) { Text("‹", style = MaterialTheme.typography.titleLarge) }
            Text(mes.format(MES), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleLarge)
            OutlinedButton({ mes = mes.plusMonths(1); aviso = null }) { Text("›", style = MaterialTheme.typography.titleLarge) }
        }
        MensagemErro(erro)
        MensagemOk(aviso)
        if (previa.isEmpty()) Text("Nada novo para lançar em ${mes.format(MES)}.", style = MaterialTheme.typography.titleMedium)
        else {
            Text("Confira as sessões que serão criadas:", style = MaterialTheme.typography.titleMedium)
            previa.groupBy { it.sessao.criancaId }.toList().sortedBy { criancas[it.first] }.forEach { (criancaId, itens) ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(criancas[criancaId].orEmpty(), style = MaterialTheme.typography.titleLarge)
                        itens.sortedBy { it.sessao.inicio }.forEach { p ->
                            val s = p.sessao
                            Text("${dataBonita(s.inicio.toLocalDate())} · ${s.inicio.format(HORA)} · ${terapias[s.terapiaId]} · ${terapeutas[s.terapeutaId]}")
                            p.aviso?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
            BotaoGrande(if (salvando) "Criando…" else "Criar ${sessoesEmTexto(previa.size)}", habilitado = !salvando, aoClicar = {
                val criar = previa.map { it.sessao.paraLinha() }
                salvando = true
                escopo.launch {
                    erro = tentar { agenda.lancar(criar) }
                    salvando = false
                    if (erro == null) { aviso = "${sessoesEmTexto(criar.size, criada = true)}."; versao++ }
                }
            })
        }
        TextButton(aoVoltar) { Text("Voltar", style = MaterialTheme.typography.titleMedium) }
    }
}
