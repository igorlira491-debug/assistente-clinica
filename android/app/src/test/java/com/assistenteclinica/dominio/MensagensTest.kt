package com.assistenteclinica.dominio

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MensagensTest {
    private fun dia(d: Int) = LocalDate.of(2026, 11, d) // 01/11/2026 é domingo; 05/11 é quinta
    private fun s(id: Long, crianca: Long, inicio: LocalDateTime, terapeuta: Long = 30, familia: Resposta = Resposta.AGUARDANDO) =
        Sessao(id, crianca, 20, terapeuta, inicio, 40, familia = familia, origem = Origem.FIXA)

    private val nomes = mapOf(10L to "João Teste", 11L to "Maria Teste", 12L to "Pedro Teste")
    private val ana = Contato(1, "Ana Teste", "5591988880001", principal = true)
    private val beto = Contato(2, "Beto Teste", "5591988880002")
    private val carla = Contato(3, "Carla Teste", "5591988880003", principal = true)
    private val responsaveis = mapOf(10L to listOf(ana, beto), 11L to listOf(ana), 12L to listOf(carla))
    private val quinta = dia(5)
    private val quarta = dia(4)

    private fun familias(sessoes: List<Sessao>, escolhidos: Map<Long, Long> = emptyMap(), modelo: String = MODELO_FAMILIA_PADRAO, hoje: LocalDate = quarta) =
        mensagensParaFamilias(sessoes, quinta, hoje, { nomes.getValue(it) }, { responsaveis[it].orEmpty() }, escolhidos, modelo)

    @Test fun `mensagem da familia segue o exemplo do desenho`() {
        val m = familias(listOf(s(1, 10, quinta.atTime(14, 0)))).single()
        assertEquals(ana, m.para)
        assertEquals("Olá, Ana! Passando para confirmar a sessão de João às 14h amanhã (quinta) aqui na clínica. Pode confirmar pra gente? 😊", m.texto)
    }

    @Test fun `uma mensagem por responsavel por dia juntando os filhos`() {
        val joao = s(1, 10, quinta.atTime(15, 0))
        val maria = s(2, 11, quinta.atTime(14, 0))
        val pedro = s(3, 12, quinta.atTime(14, 0))
        val r = familias(listOf(joao, maria, pedro))
        assertEquals(2, r.size)
        val daAna = r.single { it.para == ana }
        assertEquals(listOf(maria, joao), daAna.sessoes)
        assertTrue(daAna.texto.contains("as sessões de Maria às 14h e de João às 15h"))
        assertEquals(listOf(pedro), r.single { it.para == carla }.sessoes)
    }

    @Test fun `mesma crianca com duas sessoes no dia`() {
        val r = familias(listOf(s(1, 10, quinta.atTime(14, 0)), s(2, 10, quinta.atTime(14, 40)))).single()
        assertTrue(r.texto.contains("as sessões de João às 14h e 14h40"))
    }

    @Test fun `so entra o que ainda espera a familia e do dia certo`() {
        val confirmada = s(1, 10, quinta.atTime(14, 0), familia = Resposta.SIM)
        val encerrada = s(2, 11, quinta.atTime(15, 0)).copy(desfecho = Desfecho.FALTOU)
        val outroDia = s(3, 12, dia(6).atTime(14, 0))
        assertEquals(emptyList<Mensagem>(), familias(listOf(confirmada, encerrada, outroDia)))
    }

    @Test fun `destinatario padrao e o principal e a secretaria pode escolher outro`() {
        val joao = s(1, 10, quinta.atTime(14, 0))
        assertEquals(ana, familias(listOf(joao)).single().para)
        assertEquals(beto, familias(listOf(joao), escolhidos = mapOf(10L to 2L)).single().para)
        // Sem principal marcado, vai para o primeiro; escolha que não é da criança é ignorada.
        assertEquals(1L, destinatario(listOf(ana.copy(principal = false), beto))?.id)
        assertEquals(ana, destinatario(listOf(ana, beto), escolhidoId = 99))
    }

    @Test fun `crianca sem responsavel fica de fora`() {
        val semNinguem = mensagensParaFamilias(listOf(s(1, 10, quinta.atTime(14, 0))), quinta, quarta, { "João" }, { emptyList() })
        assertEquals(emptyList<Mensagem>(), semNinguem)
    }

    @Test fun `texto minimo nao leva terapia nem diagnostico`() {
        // O modelo não tem marcador de terapia: nem um modelo editado consegue colocar o nome da terapia.
        val m = familias(listOf(s(1, 10, quinta.atTime(14, 0))), modelo = "{nome} {sessoes} {quando} {terapia}").single()
        assertEquals("Ana a sessão de João às 14h amanhã (quinta) {terapia}", m.texto)
        assertFalse(MODELO_FAMILIA_PADRAO.contains("terapia", ignoreCase = true))
    }

    @Test fun `modelo editado vale e modelo sem marcadores volta ao padrao`() {
        val joao = s(1, 10, quinta.atTime(14, 0))
        assertEquals("Oi Ana, confirma a sessão de João às 14h amanhã (quinta)?", familias(listOf(joao), modelo = "Oi {nome}, confirma {sessoes} {quando}?").single().texto)
        assertEquals(familias(listOf(joao)).single().texto, familias(listOf(joao), modelo = "Oi, confirma?").single().texto)
        assertFalse(modeloValido("Oi {nome}, {sessoes}"))
    }

    @Test fun `segunda e confirmada na sexta com a data`() {
        val segunda = dia(9)
        val r = mensagensParaFamilias(listOf(s(1, 10, segunda.atTime(9, 0))), segunda, dia(6), { "João" }, { listOf(ana) }).single()
        assertTrue(r.texto.contains("a sessão de João às 9h segunda (09/11)"))
    }

    @Test fun `quando hora e nome em texto`() {
        assertEquals("hoje (quinta)", quandoEmTexto(quinta, quinta))
        assertEquals("amanhã (quinta)", quandoEmTexto(quinta, quarta))
        assertEquals("sábado (07/11)", quandoEmTexto(dia(7), quarta))
        assertEquals("14h", horaCurta(LocalTime.of(14, 0)))
        assertEquals("8h05", horaCurta(LocalTime.of(8, 5)))
        assertEquals("Ana", primeiroNome("  Ana Maria Teste "))
    }

    @Test fun `uma mensagem por terapeuta por dia so com o confirmado pela familia`() {
        val a = s(1, 10, quinta.atTime(15, 0), familia = Resposta.SIM)
        val b = s(2, 11, quinta.atTime(14, 0), familia = Resposta.SIM)
        val semResposta = s(3, 12, quinta.atTime(16, 0))
        val outra = s(4, 12, quinta.atTime(14, 0), terapeuta = 31, familia = Resposta.SIM)
        val terapeutas = mapOf(30L to Contato(30, "Rita Teste", "5591988880030"), 31L to Contato(31, "Lia Teste", "5591988880031"))
        val r = mensagensParaTerapeutas(listOf(a, b, semResposta, outra), quinta, quarta, { nomes.getValue(it) }, { terapeutas[it] })
        assertEquals(2, r.size)
        val daRita = r.single { it.para.id == 30L }
        assertEquals(listOf(b, a), daRita.sessoes)
        assertEquals("Olá, Rita! Sessões confirmadas para amanhã (quinta): Maria às 14h e João às 15h. Equipe da clínica", daRita.texto)
    }

    @Test fun `link do whatsapp com numero e texto escritos`() {
        assertEquals("https://wa.me/5591988880001?text=Ol%C3%A1%2C%20Ana%21", linkWhatsApp("(91) 98888-0001", "Olá, Ana!"))
        assertEquals("https://wa.me/5591988880001?text=a%20b", linkWhatsApp("5591988880001", "a b"))
        assertNull(linkWhatsApp("1234", "Oi"))
        // Emoji e quebra de linha também vão codificados (nada de texto cru na URL).
        val comEmoji = linkWhatsApp("5591988880001", "Oi 😊\nTchau")!!
        assertFalse(comEmoji.substringAfter("text=").contains(' '))
        assertTrue(comEmoji.endsWith("Oi%20%F0%9F%98%8A%0ATchau"))
    }

    @Test fun `os dois apps do whatsapp`() {
        assertEquals("com.whatsapp", AppWhatsApp.COMUM.pacote)
        assertEquals("com.whatsapp.w4b", AppWhatsApp.BUSINESS.pacote)
    }
}
