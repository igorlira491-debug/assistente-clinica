package com.assistenteclinica.dominio

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcessoTest {
    private val t0 = Instant.parse("2026-11-05T12:00:00Z")

    @Test fun `pin precisa de 6 digitos`() {
        assertTrue(pinValido("482913")); assertFalse(pinValido("48291")); assertFalse(pinValido("48291a"))
    }

    @Test fun `pin obvio e recusado`() {
        assertFalse(pinValido("000000")); assertFalse(pinValido("123456")); assertFalse(pinValido("654321"))
    }

    @Test fun `senha de backup tem 12 ou mais`() {
        assertFalse(senhaBackupValida("curta123456")); assertTrue(senhaBackupValida("cavalo-azul-dourado-77"))
    }

    @Test fun `ate 5 erros nao espera`() = assertEquals(Duration.ZERO, esperaAposErros(4))

    @Test fun `espera dobra depois do quinto erro`() {
        assertEquals(Duration.ofSeconds(30), esperaAposErros(5)); assertEquals(Duration.ofSeconds(60), esperaAposErros(6))
        assertEquals(Duration.ofHours(1), esperaAposErros(30))
    }

    @Test fun `durante a espera nao pode tentar`() {
        assertFalse(podeTentarPin(5, t0, t0.plusSeconds(29))); assertTrue(podeTentarPin(5, t0, t0.plusSeconds(30)))
    }

    @Test fun `relogio para tras nao trava a clinica`() = assertTrue(podeTentarPin(9, t0, t0.minus(Duration.ofDays(1))))

    @Test fun `trava com 3 minutos sem uso`() {
        assertFalse(travouPorTempo(t0, t0.plusSeconds(179))); assertTrue(travouPorTempo(t0, t0.plusSeconds(180)))
    }

    @Test fun `volta do whatsapp em ate 3 minutos nao pede pin`() {
        assertTrue(voltaSemPin(t0, t0.plusSeconds(180))); assertFalse(voltaSemPin(t0, t0.plusSeconds(181)))
        assertFalse(voltaSemPin(null, t0))
    }

    @Test fun `nao desativa a unica dona`() {
        val dona = Usuaria(1, "D", Papel.DONA); val sec = Usuaria(2, "S", Papel.SECRETARIA)
        assertFalse(podeDesativar(dona, listOf(dona, sec))); assertTrue(podeDesativar(sec, listOf(dona, sec)))
        assertTrue(podeDesativar(dona, listOf(dona, sec, Usuaria(3, "D2", Papel.DONA))))
    }

    @Test fun `marca de saida so vale por 10 segundos`() {
        val t = java.time.Instant.parse("2026-11-05T12:00:00Z")
        assertTrue(marcaDeSaidaValida(t, t.plusSeconds(10)))
        assertFalse(marcaDeSaidaValida(t, t.plusSeconds(11)))
        assertFalse(marcaDeSaidaValida(null, t))
        assertFalse(marcaDeSaidaValida(t, t.minusSeconds(1)))
    }

    // Revisão de segurança, item D: senha de backup nova ou trocada.
    @Test fun senhaDeBackupNovaPrecisaSerUmaFraseDeVerdade() {
        assertEquals(null, problemaDaSenhaBackup("cavalo azul dourado 77"))
        assertEquals(null, problemaDaSenhaBackup("minha frase da assistente azul"))
        assertTrue(problemaDaSenhaBackup("cavalo-azul-77")!!.contains("16")) // 14 caracteres
        assertTrue(problemaDaSenhaBackup("aaaaaaaaaaaaaaaaaaaa")!!.contains("repetidos"))
        assertTrue(problemaDaSenhaBackup("abababababababababab")!!.contains("repetidos"))
        assertTrue(problemaDaSenhaBackup("1234567890123456")!!.contains("sequências"))
        assertTrue(problemaDaSenhaBackup("abcdefghijklmnopq")!!.contains("sequências"))
        assertTrue(problemaDaSenhaBackup("assistente2026senha")!!.contains("nome da clínica"))
        assertTrue(problemaDaSenhaBackup("backup assistente 2026!")!!.contains("nome da clínica"))
    }
}
