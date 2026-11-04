package com.assistenteclinica.lembretes

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.assistenteclinica.MainActivity
import com.assistenteclinica.R
import com.assistenteclinica.dados.ListaDeLembretes
import com.assistenteclinica.dominio.AVISO_PUBLICO
import com.assistenteclinica.dominio.EstadoDosAvisos
import com.assistenteclinica.dominio.Lembrete
import com.assistenteclinica.dominio.ModoDoAlarme
import com.assistenteclinica.dominio.disparar
import com.assistenteclinica.dominio.listaAtualizada
import com.assistenteclinica.dominio.modoDoAlarme
import com.assistenteclinica.dominio.proximoAlarme
import com.assistenteclinica.dominio.textoAMostrar
import java.time.LocalDateTime
import java.time.ZoneId

private const val CANAL = "lembretes"
const val ACAO_LEMBRETE = "com.assistenteclinica.LEMBRETE"

// Se o app está na frente (spec §5.7: alarme com o app aberto não vira notificação). Marcado pelo AssistenteApp;
// num processo novo (alarme com o app fechado) começa falso.
object PrimeiroPlano { @Volatile var ativo = false }

object Avisos {
    fun criarCanal(context: Context) {
        val canal = NotificationChannel(CANAL, "Lembretes", NotificationManager.IMPORTANCE_DEFAULT)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
    }

    fun estado(context: Context) = EstadoDosAvisos(
        notificacoes = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        alarmeExato = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
    )

    // Um alarme só: o próximo lembrete da lista. Lista vazia cancela.
    fun agendarProximo(context: Context, agora: LocalDateTime = LocalDateTime.now()) {
        val alarmes = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(context, ReceptorDeLembretes::class.java).setAction(ACAO_LEMBRETE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val proximo = ListaDeLembretes(context).ler()?.let { proximoAlarme(it.lembretes, agora) }
        if (proximo == null) { alarmes.cancel(pi); return }
        val quando = proximo.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        when (modoDoAlarme(alarmes.canScheduleExactAlarms())) {
            ModoDoAlarme.EXATO -> alarmes.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, pi)
            ModoDoAlarme.APROXIMADO -> alarmes.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, pi)
        }
    }

    // Mostra o que venceu, tira da lista e agenda o próximo. Não abre o banco nem o cadastro: roda até com o
    // celular ainda bloqueado depois de ligar.
    fun aoTocar(context: Context, agora: LocalDateTime = LocalDateTime.now()) {
        ListaDeLembretes(context).disparar { disparar(it.lembretes, agora, emPrimeiroPlano = PrimeiroPlano.ativo) }
            ?.let { (gravada, d) ->
                val atualizada = listaAtualizada(gravada.calculadaEm, agora)
                d.mostrar.forEach { mostrar(context, it, textoAMostrar(it, atualizada)) }
            }
        agendarProximo(context, agora)
    }

    private fun mostrar(context: Context, l: Lembrete, texto: String) {
        // A permissão só existe do Android 13 em diante; no 12 ela vem sempre "negada" e nenhum aviso sairia.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val abrir = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Na tela bloqueada aparece só a versão pública: sem número, horário ou nome.
        val publica = NotificationCompat.Builder(context, CANAL)
            .setSmallIcon(R.mipmap.ic_launcher).setContentTitle("Assistente").setContentText(AVISO_PUBLICO).build()
        val n = NotificationCompat.Builder(context, CANAL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Assistente")
            .setContentText(texto)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publica)
            .setContentIntent(abrir)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(l.momento.hashCode() * 31 + l.tipo.ordinal, n)
    }
}
