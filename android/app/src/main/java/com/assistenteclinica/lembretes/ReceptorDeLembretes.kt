package com.assistenteclinica.lembretes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Alarme do lembrete, celular ligado (antes e depois do desbloqueio), app atualizado, troca de hora ou fuso
// e permissão de alarme exato concedida: em todos, mostra o que venceu hoje e agenda o próximo a partir da lista.
// directBootAware no manifesto: roda antes do desbloqueio, por isso só usa a lista (armazenamento protegido).
class ReceptorDeLembretes : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { Avisos.aoTocar(context) }
    }
}
