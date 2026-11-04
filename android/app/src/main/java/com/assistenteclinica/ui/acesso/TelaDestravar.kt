package com.assistenteclinica.ui.acesso

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assistenteclinica.acesso.ResultadoPin
import com.assistenteclinica.acesso.SessaoDeAcesso
import com.assistenteclinica.dominio.Papel
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TelaDestravar(sessao: SessaoDeAcesso, modifier: Modifier = Modifier) {
    val usuarias = remember { sessao.usuarias().filter { it.ativa } }
    var escolhida by remember { mutableLongStateOf(usuarias.singleOrNull()?.id ?: -1L) }
    var pin by remember { mutableStateOf("") }
    var erro by remember { mutableStateOf<String?>(null) }
    var esperarAte by remember { mutableStateOf<Instant?>(null) }
    var ocupado by remember { mutableStateOf(false) }
    var recuperando by remember { mutableStateOf(false) }
    val escopo = rememberCoroutineScope()

    if (recuperando) {
        // Esqueceu o PIN: só a dona, com a senha de backup. Usa a dona escolhida ou, se não for dona, a primeira.
        val dona = usuarias.firstOrNull { it.id == escolhida && it.papel == Papel.DONA } ?: usuarias.firstOrNull { it.papel == Papel.DONA }
        TelaRecuperar(sessao, dona?.id, aoVoltar = { recuperando = false }, modifier)
        return
    }

    // Conta o tempo de espera na tela, de segundo em segundo.
    var agora by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(esperarAte) {
        while (esperarAte?.isAfter(Instant.now()) == true) { agora = Instant.now(); delay(1_000) }
        if (esperarAte != null) { esperarAte = null; erro = null }
    }
    val esperando = esperarAte?.isAfter(agora) == true

    TelaComRolagem("Quem é você?", modifier) {
        usuarias.forEach { u ->
            FilterChip(
                selected = u.id == escolhida, onClick = { escolhida = u.id; erro = null; esperarAte = null },
                label = { Text(if (sessao.temPin(u.id)) u.nome else "${u.nome} (PIN precisa ser criado pela dona)", fontSize = 22.sp) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            )
        }
        if (escolhida != -1L) {
            CampoPin(pin, { pin = it })
            if (esperando) {
                val s = Duration.between(agora, esperarAte).seconds + 1
                MensagemErro("Muitos PINs errados. Espere ${s / 60}:${"%02d".format(s % 60)} para tentar de novo.")
            } else MensagemErro(erro)
            BotaoGrande(if (ocupado) "Abrindo…" else "Entrar", habilitado = !ocupado && !esperando && pin.length == 6, aoClicar = {
                ocupado = true
                val id = escolhida
                val tentativa = pin
                escopo.launch {
                    val r = withContext(Dispatchers.Default) { runCatching { sessao.destravar(id, tentativa) } }.getOrNull()
                    ocupado = false
                    pin = ""
                    when (r) {
                        ResultadoPin.Ok -> Unit // a tela some sozinha
                        ResultadoPin.PinErrado -> erro = "PIN errado."
                        ResultadoPin.Inativa -> erro = "Esta usuária foi desativada."
                        ResultadoPin.FalhaNoAparelho -> erro = if (usuarias.firstOrNull { it.id == id }?.papel == Papel.DONA) FALHA_NO_APARELHO else FALHA_NO_APARELHO_SECRETARIA
                        ResultadoPin.SemPin -> erro = if (usuarias.firstOrNull { it.id == id }?.papel == Papel.DONA) SEM_PIN_DONA else SEM_PIN
                        is ResultadoPin.Espere -> { esperarAte = r.ate; agora = Instant.now() }
                        null -> erro = ERRO_INESPERADO
                    }
                }
            })
        }
        TextButton({ recuperando = true }) { Text("Esqueci meu PIN (dona)", style = MaterialTheme.typography.titleMedium) }
    }
}

private const val FALHA_NO_APARELHO_SECRETARIA =
    "A proteção deste aparelho não abriu os dados (o PIN não foi testado). Peça à dona para redefinir o seu PIN em Usuárias."

// Depois de restaurar um backup, só a dona que restaurou tem PIN (plano do Bloco 8, parte 2).
private const val SEM_PIN = "O PIN desta usuária ainda não foi criado. A dona entra e cria em Usuárias → Redefinir PIN."
private const val SEM_PIN_DONA = "O PIN desta dona ainda não foi criado. Use \"Esqueci meu PIN\" com a senha de backup."
private val HORA = DateTimeFormatter.ofPattern("HH:mm:ss")
private const val FALHA_NO_APARELHO =
    "A proteção deste aparelho não abriu os dados (o PIN não foi testado). A dona deve usar \"Esqueci meu PIN\" com a senha de backup."

@Composable
private fun TelaRecuperar(sessao: SessaoDeAcesso, donaId: Long?, aoVoltar: () -> Unit, modifier: Modifier) {
    var senha by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var erro by remember { mutableStateOf<String?>(null) }
    var esperarAte by remember { mutableStateOf<Instant?>(null) }
    var ocupado by remember { mutableStateOf(false) }
    val escopo = rememberCoroutineScope()

    TelaComRolagem("Esqueci meu PIN", modifier) {
        Text("Só a dona. Digite a senha de backup (a que foi anotada em papel) e escolha um PIN novo.",
            style = MaterialTheme.typography.titleMedium)
        CampoSenha(senha, { senha = it }, "Senha de backup")
        CampoPin(pin, { pin = it }, "PIN novo")
        CampoPin(pin2, { pin2 = it }, "Repita o PIN novo")
        MensagemErro(erro)
        BotaoGrande(if (ocupado) "Conferindo…" else "Trocar o PIN e entrar", habilitado = !ocupado, aoClicar = {
            erro = when {
                donaId == null -> "Não há dona ativa cadastrada."
                esperarAte?.isAfter(Instant.now()) == true -> erro
                else -> erroDoPin(pin, pin2)
            }
            if (erro == null) {
                ocupado = true
                escopo.launch {
                    val r = withContext(Dispatchers.Default) { runCatching { sessao.recuperarComSenhaBackup(donaId!!, senha, pin) } }.getOrNull()
                    ocupado = false
                    erro = when (r) {
                        ResultadoPin.Ok -> null // a tela some sozinha
                        ResultadoPin.PinErrado -> "Senha de backup errada."
                        ResultadoPin.Inativa -> "Não há dona ativa cadastrada."
                        is ResultadoPin.Espere -> {
                            esperarAte = r.ate
                            "Muitas tentativas erradas. Tente de novo depois das ${HORA.format(r.ate.atZone(ZoneId.systemDefault()))}."
                        }
                        ResultadoPin.FalhaNoAparelho, ResultadoPin.SemPin, null -> ERRO_INESPERADO
                    }
                }
            }
        })
        TextButton(aoVoltar) { Text("Voltar", style = MaterialTheme.typography.titleMedium) }
    }
}
