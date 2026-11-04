package com.assistenteclinica.dominio

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// Bloco 8: registro de ações (spec §6.3, plano 2026-10-29 parte 3). Cada linha é quem, o quê, quando e em quê, só com
// números internos e valores destas listas fechadas: não existe campo de texto livre, então nome, telefone,
// observação, diagnóstico e motivo digitado nunca entram. Os nomes são buscados no cadastro só na hora de mostrar.

enum class Acao(val texto: String) {
    CONFIGUROU_O_APP("configurou o app"),
    ENTROU("entrou"),
    ENTROU_PELA_SENHA_DE_BACKUP("entrou pela senha de backup"),
    SAIU("saiu"),
    ERROS_DE_PIN("errou o PIN"), // quantidade = quantos erros desde a última entrada daquela usuária
    CRIOU("criou"),
    ALTEROU("alterou"),
    DESATIVOU("desativou"),
    REATIVOU("reativou"),
    REMOVEU("removeu"),
    VINCULOU("vinculou"),
    DESVINCULOU("desvinculou"),
    REGISTROU_CONSENTIMENTO("registrou o consentimento de"),
    REDEFINIU_PIN("redefiniu o PIN de"),
    LANCOU_O_MES("lançou o mês"), // quantidade = sessões criadas
    REMARCOU("remarcou"),
    MARCOU_RESPOSTA("marcou a resposta de"),
    REGISTROU_COMO_FOI("registrou como foi"),
    RESOLVEU("resolveu"),
    ADIOU("adiou"),
    LEMBRAR_DEPOIS("deixou para lembrar depois"), // quantidade = dias
    MARCOU_TERAPEUTA_INDISPONIVEL("marcou indisponível"), // quantidade = sessões afetadas
    FECHOU_A_CLINICA("marcou a clínica fechada"), // quantidade = sessões afetadas
    CONFIGUROU_O_BACKUP("configurou o backup"),
    PREPAROU_O_BACKUP("preparou o backup"),
    SALVOU_O_BACKUP("salvou o backup"), // detalhe = para onde (o Android só diz o app escolhido)
    RESTAUROU_O_BACKUP("restaurou o backup"), // quantidade = data do backup como número (dataComoNumero)
    TROCOU_A_SENHA_DE_BACKUP("trocou a senha de backup"),
    EXPORTOU_OS_DADOS("exportou os dados de"),
    APAGOU_A_PEDIDO("apagou a pedido"), // quantidade = sessões apagadas
}

enum class Alvo(val texto: String) {
    USUARIA("a usuária"),
    TERAPIA("a terapia"),
    TERAPEUTA("a terapeuta"),
    CRIANCA("a criança"),
    RESPONSAVEL("o responsável"),
    SESSAO("a sessão"),
    HORARIO("o horário fixo"),
    PENDENCIA("a pendência"),
}

enum class Detalhe(val texto: String) {
    // Saída (SAIU)
    POR_TEMPO("por tempo sem uso"),
    AO_SAIR_DA_TELA("ao sair do app"),
    NO_BOTAO("no botão Travar agora"),
    USUARIA_DESATIVADA("porque a usuária foi desativada"),
    // Erros (ERROS_DE_PIN)
    SENHA_DE_BACKUP("na senha de backup"),
    // Remarcação: quem pediu
    FAMILIA("pedido da família"),
    TERAPEUTA("pedido da terapeuta"),
    CLINICA("pedido da clínica"),
    // Respostas
    FAMILIA_SIM("família: sim"),
    FAMILIA_NAO("família: não"),
    TERAPEUTA_SIM("terapeuta: sim"),
    TERAPEUTA_NAO("terapeuta: não"),
    // Desfechos ("Como foi?")
    REALIZADA("realizada"),
    FALTOU("faltou"),
    NAO_ACONTECEU("não aconteceu"),
    CANCELADA_PELA_CLINICA("cancelada pela clínica"),
    // Sessão criada
    LANCADA_DEPOIS_DO_DIA("lançada depois do dia"),
    NOVA_DATA("nova data de uma remarcação"),
    // Backup: o app escolhido na tela de compartilhar
    DRIVE("no Google Drive"),
    OUTRO_APP("em outro app"),
}

// Uma linha do registro como o domínio a vê (a do banco tem os mesmos campos mais o id).
data class Registro(
    val momento: LocalDateTime,
    val usuariaId: Long?, // vazio quando é o próprio app
    val acao: Acao,
    val alvo: Alvo? = null,
    val alvoId: Long? = null,
    val criancaId: Long? = null, // a criança a que a ação se refere, quando há uma
    val detalhe: Detalhe? = null,
    val quantidade: Int? = null,
)

// Salvar um cadastro (terapia, terapeuta, criança): id 0 é criar; senão compara o "ativa" de antes com o de depois.
fun acaoAoSalvar(novo: Boolean, ativaAntes: Boolean?, ativaDepois: Boolean): Acao = when {
    novo -> Acao.CRIOU
    ativaAntes == true && !ativaDepois -> Acao.DESATIVOU
    ativaAntes == false && ativaDepois -> Acao.REATIVOU
    else -> Acao.ALTEROU
}

fun detalheDaResposta(daFamilia: Boolean, sim: Boolean): Detalhe = when {
    daFamilia && sim -> Detalhe.FAMILIA_SIM
    daFamilia -> Detalhe.FAMILIA_NAO
    sim -> Detalhe.TERAPEUTA_SIM
    else -> Detalhe.TERAPEUTA_NAO
}

fun detalheDoDesfecho(d: Desfecho): Detalhe = when (d) {
    Desfecho.REALIZADA -> Detalhe.REALIZADA
    Desfecho.FALTOU -> Detalhe.FALTOU
    Desfecho.NAO_ACONTECEU -> Detalhe.NAO_ACONTECEU
    Desfecho.CANCELADA_PELA_CLINICA -> Detalhe.CANCELADA_PELA_CLINICA
}

fun detalheDeQuemPediu(q: QuemPediu): Detalhe = when (q) {
    QuemPediu.FAMILIA -> Detalhe.FAMILIA
    QuemPediu.TERAPEUTA -> Detalhe.TERAPEUTA
    QuemPediu.CLINICA -> Detalhe.CLINICA
}

// O texto da linha sem nomes ("alterou a sessão 381 (família: sim)"). A tela da dona (etapa 6) troca os números
// pelos nomes do cadastro; o que foi apagado a pedido (LGPD) aparece como "registro removido".
fun textoDoRegistro(r: Registro): String {
    val alvo = r.alvo?.let { a -> " ${a.texto}" + (r.alvoId?.let { " $it" } ?: "") } ?: ""
    return r.acao.texto + alvo + extras(r)
}

private fun extras(r: Registro): String {
    val lista = listOfNotNull(r.detalhe?.texto, r.quantidade?.let { quantidadeEmTexto(r.acao, it) })
    return if (lista.isEmpty()) "" else " (${lista.joinToString(", ")})"
}

// ---- Tela da dona (etapa 6) ----

// Os nomes do cadastro, buscados só na hora de mostrar, com o app destravado. Um número que não está mais no
// cadastro (apagado a pedido, LGPD) aparece como "registro removido".
class NomesDoRegistro(
    val usuarias: Map<Long, String> = emptyMap(),
    val criancas: Map<Long, String> = emptyMap(),
    val terapeutas: Map<Long, String> = emptyMap(),
    val terapias: Map<Long, String> = emptyMap(),
    val responsaveis: Map<Long, String> = emptyMap(),
)

private const val REMOVIDO = "(registro removido)"
private val HORA_DO_REGISTRO = DateTimeFormatter.ofPattern("dd/MM HH'h'mm")

// "05/11 14h05 · Ana · remarcou a sessão de João (pedido da família)". Sessão, horário e pendência levam o nome da
// criança; o resto, o nome do próprio cadastro.
fun textoComNomes(r: Registro, n: NomesDoRegistro): String {
    val quem = r.usuariaId?.let { n.usuarias[it] ?: "usuária $it" } ?: "o app"
    val nome = when (r.alvo) {
        null -> null
        Alvo.USUARIA -> nomeOuRemovido(n.usuarias, r.alvoId)
        Alvo.CRIANCA -> nomeOuRemovido(n.criancas, r.alvoId)
        Alvo.TERAPEUTA -> nomeOuRemovido(n.terapeutas, r.alvoId)
        Alvo.TERAPIA -> nomeOuRemovido(n.terapias, r.alvoId)
        Alvo.RESPONSAVEL -> nomeOuRemovido(n.responsaveis, r.alvoId)
        Alvo.SESSAO, Alvo.HORARIO, Alvo.PENDENCIA -> r.criancaId?.let { id -> n.criancas[id]?.let { "de $it" } ?: REMOVIDO }
    }
    val alvo = r.alvo?.let { " ${it.texto}" + (nome?.let { " $it" } ?: "") } ?: ""
    // "redefiniu o PIN de a usuária" vira "redefiniu o PIN da usuária".
    val frase = (r.acao.texto + alvo).replace(" de a ", " da ").replace(" de o ", " do ")
    return "${HORA_DO_REGISTRO.format(r.momento)} · $quem · $frase" + extras(r)
}

private fun nomeOuRemovido(nomes: Map<Long, String>, id: Long?): String? = id?.let { nomes[it] ?: REMOVIDO }

// Crianças inativas: a data vem da última linha "desativou" do registro. As desativadas antes do registro existir
// (Bloco 8) não têm data e vêm primeiro, porque são as mais antigas.
fun inativaDesde(desde: LocalDateTime?): String =
    desde?.let { "inativa desde %02d/%02d/%d".format(it.dayOfMonth, it.monthValue, it.year) } ?: "inativa desde antes do registro de ações"

fun <T> maisAntigasPrimeiro(lista: List<Pair<T, LocalDateTime?>>): List<Pair<T, LocalDateTime?>> =
    lista.sortedWith(compareBy(nullsFirst()) { it.second })

private fun quantidadeEmTexto(a: Acao, n: Int): String = when (a) {
    Acao.ERROS_DE_PIN -> if (n == 1) "1 vez" else "$n vezes"
    Acao.LEMBRAR_DEPOIS -> if (n == 1) "1 dia" else "$n dias"
    Acao.RESTAUROU_O_BACKUP -> dataDoNumero(n)?.let { "de %02d/%02d/%d".format(it.dayOfMonth, it.monthValue, it.year) } ?: "de data desconhecida"
    else -> if (n == 1) "1 sessão" else "$n sessões"
}
