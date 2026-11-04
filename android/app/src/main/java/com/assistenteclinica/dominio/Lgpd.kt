package com.assistenteclinica.dominio

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// Bloco 8, etapa 7: direitos do titular (LGPD), só a dona. Exportar gera um texto com tudo o que o app guarda de uma
// criança (sem nada de outras crianças); apagar a pedido remove a criança e o que é só dela (plano, parte 4).
// As classes abaixo são o resultado das consultas (o Room preenche pelos nomes das colunas).

data class TerapiaExportada(val terapia: String, val terapeuta: String)
data class ResponsavelExportado(val nome: String, val telefone: String, val principal: Boolean, val consentiuEm: LocalDate?, val versaoTermo: String?)
data class HorarioExportado(val dia: DayOfWeek, val hora: LocalTime, val terapia: String, val ativo: Boolean)
data class SessaoExportada(val inicio: LocalDateTime, val terapia: String, val terapeuta: String, val tipo: TipoSessao, val desfecho: Desfecho?)
data class PendenciaExportada(val tipo: TipoPendencia, val prazo: LocalDateTime, val resolvidaEm: LocalDateTime?, val motivoAdiamento: String?)

class DadosDaCrianca(
    val nome: String,
    val tipo: TipoCliente,
    val observacao: String,
    val ativa: Boolean,
    val terapias: List<TerapiaExportada>,
    val responsaveis: List<ResponsavelExportado>,
    val horarios: List<HorarioExportado>,
    val sessoes: List<SessaoExportada>,
    val pendencias: List<PendenciaExportada>,
)

// Apagar só com a palavra digitada (e o PIN da dona de novo, conferido na sessão).
const val PALAVRA_PARA_APAGAR = "APAGAR"
fun confirmouApagar(digitado: String): Boolean = digitado.trim() == PALAVRA_PARA_APAGAR

private val DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy")
private val DATA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
private val HORA = DateTimeFormatter.ofPattern("HH:mm")
private val PORTUGUES = Locale.forLanguageTag("pt-BR")

private fun tipoDaPendencia(t: TipoPendencia): String = when (t) {
    TipoPendencia.ENVIAR_CONFIRMACAO -> "confirmar com a família"
    TipoPendencia.AGUARDANDO_RESPOSTA -> "aguardar a resposta da família"
    TipoPendencia.AVISAR_TERAPEUTA -> "avisar a terapeuta"
    TipoPendencia.REMARCAR -> "remarcar"
    TipoPendencia.DEFINIR_PROXIMO_PASSO -> "definir o próximo passo"
    TipoPendencia.LANCAR_AGENDA_DO_MES -> "lançar a agenda do mês"
    TipoPendencia.ENTRAR_EM_CONTATO -> "entrar em contato"
    TipoPendencia.REGISTRAR_COMO_FOI -> "registrar como foi"
    TipoPendencia.SALVAR_BACKUP -> "salvar o backup"
}

fun textoDaExportacao(d: DadosDaCrianca, geradoEm: LocalDateTime): String = buildString {
    appendLine("Dados de ${d.nome} guardados no app Assistente")
    appendLine("Gerado em ${geradoEm.format(DATA_HORA)}")
    appendLine()
    appendLine("CADASTRO")
    appendLine("- Nome: ${d.nome}")
    appendLine("- Tipo: ${if (d.tipo == TipoCliente.FIXO) "fixo" else "avulso"}")
    appendLine("- Situação: ${if (d.ativa) "ativa" else "inativa"}")
    appendLine("- Observação: ${d.observacao.ifBlank { "(nenhuma)" }}")
    secao("TERAPIAS", d.terapias) { "${it.terapia}, com ${it.terapeuta}" }
    secao("RESPONSÁVEIS", d.responsaveis) { r ->
        val consentimento = r.consentiuEm?.let { "consentimento em ${it.format(DATA)}" + (r.versaoTermo?.let { v -> ", termo $v" } ?: "") }
            ?: "consentimento não registrado"
        "${r.nome}, ${r.telefone}${if (r.principal) " (principal)" else ""}; $consentimento"
    }
    secao("HORÁRIOS FIXOS", d.horarios) {
        "${it.dia.getDisplayName(TextStyle.FULL, PORTUGUES)} às ${it.hora.format(HORA)}, ${it.terapia}${if (it.ativo) "" else " (desativado)"}"
    }
    secao("SESSÕES", d.sessoes) { s ->
        "${s.inicio.format(DATA_HORA)}, ${s.terapia} com ${s.terapeuta}, ${s.tipo.name.lowercase()}: " +
            (s.desfecho?.let { detalheDoDesfecho(it).texto } ?: "sem registro de como foi")
    }
    secao("PENDÊNCIAS", d.pendencias) { p ->
        "${tipoDaPendencia(p.tipo)}, prazo ${p.prazo.format(DATA_HORA)}, " +
            (p.resolvidaEm?.let { "resolvida em ${it.format(DATA_HORA)}" } ?: "aberta") +
            (p.motivoAdiamento?.let { "; motivo do adiamento: $it" } ?: "")
    }
}.trimEnd() + "\n"

private fun <T> StringBuilder.secao(titulo: String, itens: List<T>, linha: (T) -> String) {
    appendLine()
    appendLine("$titulo (${itens.size})")
    if (itens.isEmpty()) appendLine("- (nenhum)") else itens.forEach { appendLine("- ${linha(it)}") }
}
