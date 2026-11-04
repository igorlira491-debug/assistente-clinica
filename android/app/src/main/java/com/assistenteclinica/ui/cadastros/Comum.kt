package com.assistenteclinica.ui.cadastros

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.assistenteclinica.ui.acesso.ERRO_INESPERADO
import kotlin.coroutines.cancellation.CancellationException

// "5581999990001" -> "(81) 99999-0001"
fun telefoneBonito(t: String): String {
    val n = t.removePrefix("55")
    return if (n.length < 10) t else "(${n.take(2)}) ${n.drop(2).dropLast(4)}-${n.takeLast(4)}"
}

const val AVISO_REVISAR_TERAPEUTA = "⚠ Revisar terapeuta"

const val ERRO_TELEFONE = "Número inválido: use o celular com DDD (ex.: 81 99999-0001)."

// Roda uma ação no banco; devolve null se deu certo ou a mensagem para a tela. Sem log de propósito: a exceção
// pode trazer dados de criança. Cancelamento (saiu da tela) não é erro e segue adiante.
suspend fun tentar(acao: suspend () -> Unit): String? =
    try { acao(); null } catch (e: CancellationException) { throw e } catch (e: Exception) { ERRO_INESPERADO }

@Composable
fun MensagemOk(texto: String?) {
    if (texto != null) Text(texto, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}
