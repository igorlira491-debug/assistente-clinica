package com.assistenteclinica

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.assistenteclinica.acesso.SessaoDeAcesso
import com.assistenteclinica.dados.CadastroDeAcesso
import com.assistenteclinica.dados.ListaDeLembretes
import com.assistenteclinica.dados.calcularLembretes
import com.assistenteclinica.lembretes.Avisos
import com.assistenteclinica.lembretes.PrimeiroPlano
import com.assistenteclinica.seguranca.ProtecaoKeystore
import java.time.LocalDateTime

// Uma SessaoDeAcesso só para o app inteiro: sobrevive a girar a tela, morre com o processo (e aí nasce travada).
class AssistenteApp : Application() {
    // Criada só no primeiro uso (uma tela): com o receptor de lembretes rodando antes do desbloqueio do celular,
    // o cadastro de acesso (armazenamento comum) ainda não pode ser lido. O receptor nunca usa a sessão.
    val sessao: SessaoDeAcesso by lazy {
        SessaoDeAcesso(
            this, CadastroDeAcesso(this), ProtecaoKeystore(),
            recalcularLembretes = { banco ->
                val agora = LocalDateTime.now()
                ListaDeLembretes(this).salvar(agora, calcularLembretes(banco, agora))
                Avisos.agendarProximo(this, agora)
            },
            aoRecalculoIncompleto = { ListaDeLembretes(this).marcarDesatualizada() },
        )
    }

    override fun onCreate() {
        super.onCreate()
        Avisos.criarCanal(this)
        // onStop/onStart do app inteiro (não de cada tela): saiu para Home, recentes ou outro app.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { PrimeiroPlano.ativo = false; sessao.aoSairDaTela() }
            override fun onStart(owner: LifecycleOwner) { PrimeiroPlano.ativo = true; sessao.aoVoltarATela() }
        })
    }
}
