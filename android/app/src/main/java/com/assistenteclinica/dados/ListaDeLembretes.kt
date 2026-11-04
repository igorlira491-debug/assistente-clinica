package com.assistenteclinica.dados

import android.content.Context
import com.assistenteclinica.dominio.DIAS_DE_LEMBRETES
import com.assistenteclinica.dominio.DIA_DE_LANCAR_O_MES
import com.assistenteclinica.dominio.Disparo
import com.assistenteclinica.dominio.Lembrete
import com.assistenteclinica.dominio.ListaGravada
import com.assistenteclinica.dominio.PrazoGuardado
import com.assistenteclinica.dominio.jaDisponivel
import com.assistenteclinica.dominio.lembretes
import com.assistenteclinica.dominio.listaParaTexto
import com.assistenteclinica.dominio.textoParaLista
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

// Fica FORA do banco cifrado e no armazenamento protegido por dispositivo: o Android libera esse arquivo logo
// depois de ligar, antes do desbloqueio, para os avisos voltarem sem PIN (spec §6.2, §5.7).
// Guarda só horário, tipo e os textos sem nomes. Grava com commit() (síncrono), como o CadastroDeAcesso.
// Toda leitura seguida de gravação fica dentro de TRAVA: o alarme (thread da tela) e o recálculo (segundo plano)
// mexem no mesmo arquivo e um não pode apagar o que o outro acabou de gravar.
class ListaDeLembretes(context: Context, nomeArquivo: String = "lembretes") {
    private val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences(nomeArquivo, Context.MODE_PRIVATE)

    fun ler(): ListaGravada? = textoParaLista(prefs.getString(LISTA, null))

    // Um cálculo mais antigo que termina depois de um mais novo (ex.: o que passou dos 2 s) não volta a lista
    // para trás. Lista gravada "no futuro" (relógio do celular adiantado e depois corrigido) não segura nada.
    fun salvar(calculadaEm: LocalDateTime, lista: List<Lembrete>, agora: LocalDateTime = LocalDateTime.now()) = synchronized(TRAVA) {
        val atual = ler()
        if (atual != null && atual.calculadaEm > calculadaEm && atual.calculadaEm <= agora) return
        gravar(listaParaTexto(calculadaEm, lista))
    }

    // Quando o alarme toca: decide o que mostrar e tira da lista numa leitura e gravação só, mantendo a data do
    // cálculo. Devolve a lista lida (para saber se os números valem) e o disparo; null se não há lista.
    fun disparar(decidir: (ListaGravada) -> Disparo): Pair<ListaGravada, Disparo>? = synchronized(TRAVA) {
        val atual = ler() ?: return null
        val d = decidir(atual)
        gravar(listaParaTexto(atual.calculadaEm, d.restante))
        atual to d
    }

    // Recálculo que não terminou ou deu erro: os números da lista antiga podem estar errados, então ela passa a
    // valer como de outro dia (texto genérico) até o próximo cálculo.
    fun marcarDesatualizada() = synchronized(TRAVA) {
        val atual = ler() ?: return
        gravar(listaParaTexto(DESATUALIZADA, atual.lembretes))
    }

    private fun gravar(texto: String) {
        check(prefs.edit().putString(LISTA, texto).commit()) { "não consegui gravar a lista de lembretes" }
    }

    private companion object {
        const val LISTA = "lista"
        val DESATUALIZADA: LocalDateTime = LocalDateTime.of(2000, 1, 1, 0, 0) // qualquer dia que não seja hoje
        val TRAVA = Any() // uma só para o app inteiro: cada um cria a sua ListaDeLembretes
    }
}

// Lê o banco (aberto) e monta a lista dos próximos dias com as mesmas regras da tela "O que fazer".
suspend fun calcularLembretes(banco: BancoAssistente, agora: LocalDateTime): List<Lembrete> {
    val pendencias = banco.pendencias()
    // +4 dias: cobre a sexta que confirma a segunda, como a tela.
    val sessoes = pendencias.sessoesEmAberto(agora.plusDays(DIAS_DE_LEMBRETES + 4L)).map { it.paraDominio() }
    val guardadas = pendencias.abertas().map { PrazoGuardado(it.prazo, jaDisponivel(it.tipo, adiada = it.motivoAdiamento != null)) }
    val dias = (0 until DIAS_DE_LEMBRETES).map { agora.toLocalDate().plusDays(it.toLong()) }
    // A prévia é do mês seguinte, todo no futuro: não depende do dia, então uma consulta por mês, não por dia.
    val previaPorMes = mutableMapOf<YearMonth, Boolean>()
    val haMes: Map<LocalDate, Boolean> = dias.associateWith { d ->
        val mes = YearMonth.from(d).plusMonths(1)
        d.dayOfMonth >= DIA_DE_LANCAR_O_MES && previaPorMes.getOrPut(mes) { banco.agenda().previaDoMes(mes, d).isNotEmpty() }
    }
    return lembretes(sessoes, guardadas, { haMes[it] == true }, agora)
}

// "Já fiz" dos passos de bateria (instrução, não checagem): só guarda a data, no banco cifrado.
private const val BATERIA_CONFERIDA_EM = "bateria_conferida_em"

suspend fun ConfiguracaoDao.bateriaConferidaEm(): LocalDate? = valor(BATERIA_CONFERIDA_EM)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
suspend fun ConfiguracaoDao.marcarBateriaConferida(dia: LocalDate) = salvar(Configuracao(BATERIA_CONFERIDA_EM, dia.toString()))
