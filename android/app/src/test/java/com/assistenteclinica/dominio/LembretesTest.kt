package com.assistenteclinica.dominio

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LembretesTest {
    private fun dia(d: Int) = LocalDate.of(2026, 11, d) // 01/11/2026 é domingo; 03/11 é terça
    private fun s(id: Long, inicio: LocalDateTime, crianca: Long = 10, duracao: Int = 40) =
        Sessao(id, crianca, 20, 30, inicio, duracao, origem = Origem.AVULSA)
    private val semMes: (LocalDate) -> Boolean = { false }
    private fun doTipo(l: List<Lembrete>, t: TipoLembrete) = l.filter { it.tipo == t }

    @Test fun `resumo das 8h conta atrasadas e para hoje como a tela`() {
        // Às 8h de terça: a confirmação da sessão de terça 14h venceu segunda 16h (🔴), a guardada venceu
        // ontem (🔴), o "Como foi?" de segunda 15h vence terça 15h40 (🟠) e a guardada de quinta fica em ⚪.
        val terca14 = s(1, dia(3).atTime(14, 0))
        val segunda15 = s(2, dia(2).atTime(15, 0), crianca = 11).copy(familia = Resposta.SIM, terapeuta = Resposta.SIM)
        val vencidaOntem = PrazoGuardado(dia(2).atTime(9, 0), jaDisponivel = true)
        val proxima = PrazoGuardado(dia(5).atTime(9, 0), jaDisponivel = false)
        val l = lembretes(listOf(terca14, segunda15), listOf(vencidaOntem, proxima), semMes, dia(2).atTime(20, 0), dias = 2)
        val resumo = doTipo(l, TipoLembrete.RESUMO).single()
        assertEquals(dia(3).atTime(8, 0), resumo.momento)
        assertEquals("Bom dia! Você tem 3 tarefas hoje (2 atrasadas).", resumo.texto)
        assertEquals("Bom dia! Há tarefas para hoje no Assistente.", resumo.generico)
    }

    @Test fun `resumo sem atrasadas e no singular`() {
        val guardada = PrazoGuardado(dia(3).atTime(17, 0), jaDisponivel = true)
        val resumo = doTipo(lembretes(emptyList(), listOf(guardada), semMes, dia(2).atTime(20, 0), dias = 2), TipoLembrete.RESUMO).single()
        assertEquals("Bom dia! Você tem 1 tarefa hoje.", resumo.texto)
    }

    @Test fun `texto generico so quando a lista esta desatualizada`() {
        val l = Lembrete(dia(3).atTime(9, 0), TipoLembrete.CONFIRMAR, "Assistente: 2 confirmações para enviar.", GENERICO_CONFIRMAR)
        assertEquals("Assistente: 2 confirmações para enviar.", textoAMostrar(l, listaAtualizada = true))
        assertEquals("Assistente: há confirmações para enviar.", textoAMostrar(l, listaAtualizada = false))
        listOf(GENERICO_RESUMO, GENERICO_CONFIRMAR, GENERICO_ESCALADA, GENERICO_COMO_FOI).forEach { assertFalse(it, it.any(Char::isDigit)) }
    }

    @Test fun `resumo nao aparece em dia sem tarefa de hoje`() {
        // A guardada só vence daqui a dois dias e ainda não está disponível: não conta no resumo de amanhã.
        val longe = PrazoGuardado(dia(5).atTime(9, 0), jaDisponivel = false)
        val l = lembretes(emptyList(), listOf(longe), semMes, dia(2).atTime(20, 0), dias = 2)
        assertEquals(emptyList<Lembrete>(), doTipo(l, TipoLembrete.RESUMO))
    }

    @Test fun `resumo conta o lancamento do mes`() {
        // A tarefa surge no dia 20 às 9h: o resumo das 8h do dia 20 ainda não a vê; o do dia 21 vê.
        val l = lembretes(emptyList(), emptyList(), { it.dayOfMonth >= 20 }, LocalDateTime.of(2026, 10, 19, 20, 0), dias = 3)
        assertEquals(listOf(LocalDateTime.of(2026, 10, 21, 8, 0)), doTipo(l, TipoLembrete.RESUMO).map { it.momento })
    }

    @Test fun `confirmacao as 9h e escalada as 16h da vespera`() {
        val quarta14 = s(1, dia(4).atTime(14, 0))
        val l = lembretes(listOf(quarta14), emptyList(), semMes, dia(2).atTime(20, 0), dias = 2)
        val confirmar = doTipo(l, TipoLembrete.CONFIRMAR).single()
        assertEquals(dia(3).atTime(9, 0), confirmar.momento)
        assertEquals("Assistente: 1 confirmação para enviar.", confirmar.texto)
        val escalada = doTipo(l, TipoLembrete.ESCALADA).single()
        assertEquals(dia(3).atTime(16, 0), escalada.momento)
        assertEquals("Assistente: 1 família sem resposta. Hora de ligar.", escalada.texto)
    }

    @Test fun `varias confirmacoes no plural`() {
        val l = lembretes(listOf(s(1, dia(4).atTime(14, 0)), s(2, dia(4).atTime(15, 0), crianca = 11)), emptyList(), semMes, dia(2).atTime(20, 0), dias = 2)
        assertEquals("Assistente: 2 confirmações para enviar.", doTipo(l, TipoLembrete.CONFIRMAR).single().texto)
        assertEquals("Assistente: 2 famílias sem resposta. Hora de ligar.", doTipo(l, TipoLembrete.ESCALADA).single().texto)
    }

    @Test fun `mesma crianca com duas sessoes e uma familia so na escalada`() {
        val l = lembretes(listOf(s(1, dia(4).atTime(14, 0)), s(2, dia(4).atTime(15, 0))), emptyList(), semMes, dia(2).atTime(20, 0), dias = 2)
        assertEquals("Assistente: 2 confirmações para enviar.", doTipo(l, TipoLembrete.CONFIRMAR).single().texto)
        assertEquals("Assistente: 1 família sem resposta. Hora de ligar.", doTipo(l, TipoLembrete.ESCALADA).single().texto)
    }

    @Test fun `lista de ontem usa o texto generico as 8h`() {
        // Calculada segunda 20h (ao sair do app); o resumo de terça 8h sai genérico porque a lista não é de hoje.
        val calculadaEm = dia(2).atTime(20, 0)
        val l = lembretes(emptyList(), listOf(PrazoGuardado(dia(2).atTime(9, 0), true)), semMes, calculadaEm, dias = 2)
        val resumo = doTipo(l, TipoLembrete.RESUMO).single()
        assertEquals("Bom dia! Há tarefas para hoje no Assistente.", textoAMostrar(resumo, listaAtualizada(calculadaEm, agora = dia(3).atTime(8, 0))))
    }

    @Test fun `lista calculada hoje vale o dia todo`() {
        assertTrue(listaAtualizada(dia(3).atTime(0, 1), agora = dia(3).atTime(16, 0)))
        assertFalse(listaAtualizada(dia(2).atTime(23, 59), agora = dia(3).atTime(0, 1)))
    }

    @Test fun `resolver 3 confirmacoes as 8h50 e sair muda o aviso das 9h de 5 para 2`() {
        // Cinco sessões de quarta aguardando a família. Antes: 5. Ela confirma três e sai às 8h50 (recálculo): 2.
        val quarta = (1L..5L).map { s(it, dia(4).atTime(8 + it.toInt(), 0), crianca = 10 + it) }
        val antes = lembretes(quarta, emptyList(), semMes, dia(2).atTime(20, 0), dias = 2)
        assertEquals("Assistente: 5 confirmações para enviar.", doTipo(antes, TipoLembrete.CONFIRMAR).single().texto)
        val depois = quarta.mapIndexed { i, x -> if (i < 3) x.copy(familia = Resposta.SIM) else x }
        val as8h50 = dia(3).atTime(8, 50)
        val recalculada = lembretes(depois, emptyList(), semMes, as8h50, dias = 2)
        val as9 = doTipo(recalculada, TipoLembrete.CONFIRMAR).single()
        assertEquals("Assistente: 2 confirmações para enviar.", textoAMostrar(as9, listaAtualizada(as8h50, agora = as9.momento)))
    }

    @Test fun `9h e 16h contam as sessoes de amanha e nunca as de hoje`() {
        // Terça: a sessão de terça 17h (hoje) ainda sem resposta não entra; a de quarta 14h (amanhã) entra.
        val hoje17 = s(1, dia(3).atTime(17, 0))
        val amanha14 = s(2, dia(4).atTime(14, 0), crianca = 11)
        val l = lembretes(listOf(hoje17, amanha14), emptyList(), semMes, dia(3).atTime(7, 0), dias = 1)
        assertEquals("Assistente: 1 confirmação para enviar.", doTipo(l, TipoLembrete.CONFIRMAR).single().texto)
        assertEquals("Assistente: 1 família sem resposta. Hora de ligar.", doTipo(l, TipoLembrete.ESCALADA).single().texto)
    }

    @Test fun `so a sessao de hoje nao gera 9h nem 16h`() {
        val l = lembretes(listOf(s(1, dia(3).atTime(17, 0))), emptyList(), semMes, dia(3).atTime(7, 0), dias = 1)
        assertEquals(emptyList<Lembrete>(), doTipo(l, TipoLembrete.CONFIRMAR) + doTipo(l, TipoLembrete.ESCALADA))
    }

    @Test fun `familia que ja respondeu nao gera confirmacao nem escalada`() {
        val respondida = s(1, dia(4).atTime(14, 0)).copy(familia = Resposta.SIM)
        val l = lembretes(listOf(respondida), emptyList(), semMes, dia(2).atTime(20, 0), dias = 2)
        assertEquals(emptyList<Lembrete>(), doTipo(l, TipoLembrete.CONFIRMAR) + doTipo(l, TipoLembrete.ESCALADA))
    }

    @Test fun `sessao de segunda confirma na sexta`() {
        val segunda = s(1, dia(9).atTime(9, 0))
        val l = lembretes(listOf(segunda), emptyList(), semMes, dia(5).atTime(20, 0), dias = 4)
        assertEquals(listOf(dia(6).atTime(9, 0)), doTipo(l, TipoLembrete.CONFIRMAR).map { it.momento })
    }

    @Test fun `como foi no fim da sessao e junta o mesmo horario`() {
        val a = s(1, dia(3).atTime(14, 0)).copy(familia = Resposta.SIM)
        val b = s(2, dia(3).atTime(14, 0), crianca = 11).copy(familia = Resposta.SIM)
        val c = s(3, dia(3).atTime(15, 0)).copy(familia = Resposta.SIM)
        val l = doTipo(lembretes(listOf(a, b, c), emptyList(), semMes, dia(3).atTime(10, 0), dias = 1), TipoLembrete.COMO_FOI)
        assertEquals(listOf(dia(3).atTime(14, 40), dia(3).atTime(15, 40)), l.map { it.momento })
        assertEquals("Registrar como foi 2 sessões das 14h", l[0].texto)
        assertEquals("Registrar como foi a sessão das 15h", l[1].texto)
    }

    @Test fun `mesmo fim com inicios diferentes usa o horario do fim`() {
        val longa = s(1, dia(3).atTime(14, 0), duracao = 60)
        val curta = s(2, dia(3).atTime(14, 20), crianca = 11)
        val l = doTipo(lembretes(listOf(longa, curta), emptyList(), semMes, dia(3).atTime(10, 0), dias = 1), TipoLembrete.COMO_FOI)
        assertEquals("Registrar como foi 2 sessões que terminaram às 15h", l.single().texto)
    }

    @Test fun `sessao encerrada ou que nao vai acontecer nao gera como foi`() {
        val encerrada = s(1, dia(3).atTime(14, 0)).copy(desfecho = Desfecho.REALIZADA)
        val nao = s(2, dia(3).atTime(15, 0)).copy(familia = Resposta.NAO)
        val l = lembretes(listOf(encerrada, nao), emptyList(), semMes, dia(3).atTime(10, 0), dias = 1)
        assertEquals(emptyList<Lembrete>(), doTipo(l, TipoLembrete.COMO_FOI))
    }

    @Test fun `dia sem tarefas nao gera nada`() {
        assertEquals(emptyList<Lembrete>(), lembretes(emptyList(), emptyList(), semMes, dia(2).atTime(20, 0)))
    }

    @Test fun `so momentos futuros e dentro do horizonte em ordem`() {
        val hoje15 = s(1, dia(3).atTime(15, 0))
        val depois = s(2, dia(10).atTime(14, 0)) // fora do horizonte de 2 dias
        val l = lembretes(listOf(hoje15, depois), emptyList(), semMes, dia(3).atTime(9, 30), dias = 2)
        assertTrue(l.all { it.momento > dia(3).atTime(9, 30) && it.momento < dia(5).atStartOfDay() })
        assertEquals(l.sortedBy { it.momento }, l)
        assertFalse(l.any { it.tipo == TipoLembrete.RESUMO && it.momento.toLocalDate() == dia(3) })
    }

    @Test fun `texto nunca tem nome nem id`() {
        val sessoes = listOf(s(41, dia(4).atTime(14, 0), crianca = 987), s(42, dia(3).atTime(16, 0), crianca = 654))
        val l = lembretes(sessoes, listOf(PrazoGuardado(dia(2).atTime(9, 0), true)), semMes, dia(2).atTime(20, 0), dias = 3)
        assertTrue(l.isNotEmpty())
        val permitidos = Regex("""(Bom dia! Você tem \d+ (tarefa|tarefas) hoje( \(\d+ (atrasada|atrasadas)\))?\.|Assistente: \d+ (confirmação|confirmações) para enviar\.|""" +
            """Assistente: \d+ (família|famílias) sem resposta\. Hora de ligar\.|Registrar como foi (a sessão|\d+ sessões) (das|que terminaram às) \d+h(\d\d)?)""")
        l.forEach { assertTrue(it.texto, permitidos.matches(it.texto)) }
        l.forEach { assertTrue(it.generico in listOf(GENERICO_RESUMO, GENERICO_CONFIRMAR, GENERICO_ESCALADA, GENERICO_COMO_FOI)) }
        assertFalse(l.any { "987" in it.texto || "654" in it.texto })
    }

    @Test fun `jaDisponivel segue a regra da tela`() {
        assertFalse(jaDisponivel(TipoPendencia.ENTRAR_EM_CONTATO, adiada = false))
        assertFalse(jaDisponivel(TipoPendencia.REMARCAR, adiada = true))
        assertTrue(jaDisponivel(TipoPendencia.REMARCAR, adiada = false))
        assertTrue(jaDisponivel(TipoPendencia.DEFINIR_PROXIMO_PASSO, adiada = false))
    }

    private fun lem(m: LocalDateTime, t: TipoLembrete) = Lembrete(m, t, t.name, t.name)

    @Test fun `disparar mostra o vencido de hoje e guarda o futuro`() {
        val agora = dia(3).atTime(9, 0)
        val r = disparar(listOf(lem(dia(3).atTime(9, 0), TipoLembrete.CONFIRMAR), lem(dia(3).atTime(16, 0), TipoLembrete.ESCALADA)), agora)
        assertEquals(listOf(TipoLembrete.CONFIRMAR), r.mostrar.map { it.tipo })
        assertEquals(listOf(TipoLembrete.ESCALADA), r.restante.map { it.tipo })
    }

    @Test fun `celular desligado das 7h as 11h mostra um aviso so sem numeros`() {
        val lista = listOf(
            lem(dia(2).atTime(8, 0), TipoLembrete.RESUMO), // ontem: some
            lem(dia(3).atTime(8, 0), TipoLembrete.RESUMO),
            lem(dia(3).atTime(9, 0), TipoLembrete.CONFIRMAR),
            lem(dia(3).atTime(16, 0), TipoLembrete.ESCALADA),
        )
        val r = disparar(lista, dia(3).atTime(11, 0))
        assertEquals(listOf(AVISO_ATRASADO), r.mostrar.map { it.texto })
        assertEquals(AVISO_ATRASADO, r.mostrar.single().generico)
        assertEquals(listOf(dia(3).atTime(16, 0)), r.restante.map { it.momento })
    }

    @Test fun `celular ligado as 21h nao mostra bom dia nem como foi um por um`() {
        val lista = listOf(
            lem(dia(3).atTime(8, 0), TipoLembrete.RESUMO),
            lem(dia(3).atTime(14, 40), TipoLembrete.COMO_FOI),
            lem(dia(3).atTime(15, 40), TipoLembrete.COMO_FOI),
            lem(dia(3).atTime(20, 50), TipoLembrete.COMO_FOI), // dentro da tolerância: sai normal
        )
        val r = disparar(lista, dia(3).atTime(21, 0))
        assertEquals(listOf(TipoLembrete.COMO_FOI.name, AVISO_ATRASADO), r.mostrar.map { it.texto })
    }

    @Test fun `alarme atrasado ate 30 min sai normal`() {
        val r = disparar(listOf(lem(dia(3).atTime(9, 0), TipoLembrete.CONFIRMAR)), dia(3).atTime(9, 30))
        assertEquals(listOf(TipoLembrete.CONFIRMAR.name), r.mostrar.map { it.texto })
    }

    @Test fun `dois resumos vencidos de hoje mostram so o mais recente`() {
        val r = disparar(listOf(lem(dia(3).atTime(8, 10), TipoLembrete.RESUMO), lem(dia(3).atTime(8, 30), TipoLembrete.RESUMO)), dia(3).atTime(8, 35))
        assertEquals(listOf(dia(3).atTime(8, 30)), r.mostrar.map { it.momento })
    }

    @Test fun `alarme com o app em primeiro plano nao mostra notificacao`() {
        val lista = listOf(lem(dia(3).atTime(9, 0), TipoLembrete.CONFIRMAR), lem(dia(3).atTime(16, 0), TipoLembrete.ESCALADA))
        val r = disparar(lista, dia(3).atTime(9, 0), emPrimeiroPlano = true)
        assertEquals(emptyList<Lembrete>(), r.mostrar)
        assertEquals(listOf(TipoLembrete.ESCALADA), r.restante.map { it.tipo }) // o das 9h sai da lista mesmo assim
    }

    @Test fun `como foi vencidos de hoje aparecem todos`() {
        val r = disparar(listOf(lem(dia(3).atTime(15, 30), TipoLembrete.COMO_FOI), lem(dia(3).atTime(15, 40), TipoLembrete.COMO_FOI)), dia(3).atTime(15, 45))
        assertEquals(2, r.mostrar.size)
        assertEquals(emptyList<Lembrete>(), r.restante)
    }

    // ---- parte 2 ----

    @Test fun `lista vira texto e volta igual`() {
        val calculadaEm = dia(3).atTime(7, 30)
        val lista = listOf(
            Lembrete(dia(3).atTime(9, 0), TipoLembrete.CONFIRMAR, "Assistente: 2 confirmações para enviar.", GENERICO_CONFIRMAR),
            Lembrete(dia(3).atTime(14, 40), TipoLembrete.COMO_FOI, "Registrar como foi a sessão das 14h", GENERICO_COMO_FOI),
        )
        assertEquals(ListaGravada(calculadaEm, lista), textoParaLista(listaParaTexto(calculadaEm, lista)))
        assertEquals(ListaGravada(calculadaEm, emptyList()), textoParaLista(listaParaTexto(calculadaEm, emptyList())))
    }

    @Test fun `texto estragado ou vazio vira sem lista`() {
        assertEquals(null, textoParaLista(null))
        assertEquals(null, textoParaLista(""))
        assertEquals(null, textoParaLista("2026-11-03T07:30\nlinha quebrada"))
    }

    @Test fun `texto gravado nao tem nome`() {
        val sessoes = listOf(s(1, dia(4).atTime(14, 0), crianca = 987))
        val texto = listaParaTexto(dia(3).atTime(7, 0), lembretes(sessoes, emptyList(), semMes, dia(3).atTime(7, 0), dias = 2))
        assertFalse("987" in texto)
        assertTrue(texto.lines().drop(1).all { it.split("\t").size == 4 })
    }

    @Test fun `proximo alarme e o primeiro depois de agora`() {
        val lista = listOf(lem(dia(3).atTime(9, 0), TipoLembrete.CONFIRMAR), lem(dia(3).atTime(16, 0), TipoLembrete.ESCALADA))
        assertEquals(dia(3).atTime(16, 0), proximoAlarme(lista, dia(3).atTime(9, 0)))
        assertEquals(dia(3).atTime(9, 0), proximoAlarme(lista, dia(3).atTime(8, 0)))
        assertEquals(null, proximoAlarme(lista, dia(3).atTime(17, 0)))
    }

    @Test fun `sem permissao de alarme exato o aviso vai aproximado`() {
        assertEquals(ModoDoAlarme.EXATO, modoDoAlarme(podeExato = true))
        assertEquals(ModoDoAlarme.APROXIMADO, modoDoAlarme(podeExato = false))
    }

    @Test fun `tudo pronto so com notificacoes e alarme exato`() {
        assertTrue(EstadoDosAvisos(notificacoes = true, alarmeExato = true).tudoPronto)
        assertFalse(EstadoDosAvisos(notificacoes = true, alarmeExato = false).tudoPronto)
        assertFalse(EstadoDosAvisos(notificacoes = false, alarmeExato = true).tudoPronto)
    }

    @Test fun `aviso publico nao tem numero nem horario`() {
        assertEquals("Aviso do Assistente", AVISO_PUBLICO)
        assertFalse(AVISO_PUBLICO.any(Char::isDigit))
    }

    @Test fun `guia da samsung tem apps nunca em suspensao`() {
        assertTrue(guiaDeBateria("samsung").any { "Apps nunca em suspensão" in it })
    }

    @Test fun `guia do poco tem sem restricoes e inicio automatico`() {
        listOf("Xiaomi", "POCO", "redmi").forEach { marca ->
            val g = guiaDeBateria(marca)
            assertTrue(g.any { "Sem restrições" in it })
            assertTrue(g.any { "Início automático" in it })
        }
    }

    @Test fun `marca desconhecida recebe so o passo padrao`() {
        assertEquals(1, guiaDeBateria("qualquer-marca").size)
        assertTrue(guiaDeBateria("qualquer-marca").single().contains("Sem restrição"))
    }
}
