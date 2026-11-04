package com.assistenteclinica.dominio

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CadastrosTest {
    @Test fun `telefone em varios formatos vira formato do wa me`() {
        assertEquals("5581999990001", telefoneWhatsApp("+55 (81) 9 9999-0001"))
        assertEquals("5581999990001", telefoneWhatsApp("81999990001"))
        assertEquals("5581999990001", telefoneWhatsApp("081 99999-0001"))
        assertEquals("558133330001", telefoneWhatsApp("(81) 3333-0001")) // fixo com WhatsApp Business
    }

    @Test fun `telefone sem DDD estrangeiro ou torto e recusado`() {
        assertNull(telefoneWhatsApp("99999-0001"))
        assertNull(telefoneWhatsApp("+1 555 123 4567"))
        assertNull(telefoneWhatsApp("01 99999-0001")) // DDD não tem zero
        assertNull(telefoneWhatsApp("81 8999-00011")) // 11 dígitos sem o 9 do celular
        assertNull(telefoneWhatsApp(""))
    }

    @Test fun `nome precisa de letra e no maximo 80`() {
        assertTrue(nomeValido("Ana"))
        assertFalse(nomeValido("   "))
        assertFalse(nomeValido("a".repeat(81)))
    }

    @Test fun `duracao de 5 a 240`() {
        assertTrue(duracaoValida(5)); assertTrue(duracaoValida(240))
        assertFalse(duracaoValida(0)); assertFalse(duracaoValida(241))
    }

    @Test fun `observacao ate 200`() {
        assertTrue(observacaoValida(""))
        assertFalse(observacaoValida("x".repeat(201)))
    }

    @Test fun `consentimento nao pode ser no futuro`() {
        val hoje = LocalDate.of(2026, 10, 28)
        assertTrue(consentimentoValido(hoje, hoje))
        assertFalse(consentimentoValido(hoje.plusDays(1), hoje))
    }

    @Test fun `aviso se nao ha responsavel ou algum sem consentimento`() {
        val d = LocalDate.of(2026, 10, 1)
        assertTrue(precisaAvisoDeConsentimento(emptyList()))
        assertTrue(precisaAvisoDeConsentimento(listOf(d, null)))
        assertFalse(precisaAvisoDeConsentimento(listOf(d, d)))
    }

    @Test fun `busca ignora acento e maiusculas`() {
        assertTrue(combinaComBusca("João Pedro", "joao"))
        assertTrue(combinaComBusca("Maria", ""))
        assertFalse(combinaComBusca("Maria", "jo"))
    }
}
