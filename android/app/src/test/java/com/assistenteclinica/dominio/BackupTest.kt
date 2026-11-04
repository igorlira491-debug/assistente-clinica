package com.assistenteclinica.dominio

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {
    private val hoje = LocalDate.of(2026, 10, 29)

    @Test fun nomeDoArquivoSoComAData() {
        assertEquals("assistente-backup-2026-10-29.assistente", nomeDoArquivoDeBackup(hoje))
        assertTrue(nomeDoArquivoDeBackup(hoje).endsWith(EXTENSAO_DO_BACKUP))
    }

    @Test fun salvarBackupVenceAsDezoitoHoras() {
        assertEquals(hoje.atTime(18, 0), prazoSalvarBackup(hoje.atTime(8, 0)))
        assertEquals(hoje.atTime(18, 0), prazoSalvarBackup(hoje.atTime(17, 0)))
        // Preparado depois das 17h: vence 1 h depois, para não nascer atrasado.
        assertEquals(hoje.atTime(20, 30), prazoSalvarBackup(hoje.atTime(19, 30)))
    }

    @Test fun preparaUmaVezPorDia() {
        assertTrue(precisaPrepararBackup(null, hoje))
        assertTrue(precisaPrepararBackup(hoje.minusDays(1), hoje))
        assertFalse(precisaPrepararBackup(hoje, hoje))
    }

    @Test fun destinoDoDriveOuOutroApp() {
        assertEquals(Detalhe.DRIVE, detalheDoDestino("com.google.android.apps.docs"))
        assertEquals(Detalhe.OUTRO_APP, detalheDoDestino("com.whatsapp"))
        assertEquals(Detalhe.OUTRO_APP, detalheDoDestino(null))
        assertEquals("salvou o backup (no Google Drive)", textoDoRegistro(Registro(hoje.atTime(9, 0), 1, Acao.SALVOU_O_BACKUP, detalhe = Detalhe.DRIVE)))
    }

    // ---- Restaurar (etapa 5) ----

    @Test fun soDonaAtivaCriaOPinAoRestaurar() {
        val usuarias = listOf(
            Usuaria(1, "Dona", Papel.DONA), Usuaria(2, "Secretária", Papel.SECRETARIA),
            Usuaria(3, "Dona antiga", Papel.DONA, ativa = false), Usuaria(4, "Sócia", Papel.DONA),
        )
        assertEquals(listOf(1L, 4L), donasParaRestaurar(usuarias).map { it.id })
    }

    @Test fun dataDoBackupViraNumeroEVolta() {
        assertEquals(20261029, dataComoNumero(hoje))
        assertEquals(hoje, dataDoNumero(20261029))
        assertEquals(null, dataDoNumero(20261399)) // mês 13: número mexido
        assertEquals("restaurou o backup (de 29/10/2026)", textoDoRegistro(Registro(hoje.atTime(9, 0), 1, Acao.RESTAUROU_O_BACKUP, quantidade = 20261029)))
    }
}
