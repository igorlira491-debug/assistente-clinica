package com.assistenteclinica.dados

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.assistenteclinica.dominio.AppWhatsApp
import com.assistenteclinica.dominio.MODELO_FAMILIA_PADRAO
import com.assistenteclinica.dominio.MODELO_TERAPEUTA_PADRAO
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConfiguracaoMensagensTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val nome = "teste_${Random.nextLong().toULong()}.db"
    private val banco = abrirBanco(context, Random.nextBytes(32), nome)
    private val config = banco.configuracao()

    @After fun apagar() { banco.close(); context.deleteDatabase(nome) }

    @Test fun semEscolhaValemOsPadroes() = runBlocking {
        assertEquals(AppWhatsApp.COMUM, config.appWhatsApp())
        assertEquals(MODELO_FAMILIA_PADRAO, config.modeloFamilia())
        assertEquals(MODELO_TERAPEUTA_PADRAO, config.modeloTerapeuta())
    }

    @Test fun escolhasFicamGuardadas() = runBlocking {
        config.salvarAppWhatsApp(AppWhatsApp.BUSINESS)
        assertEquals(AppWhatsApp.BUSINESS, config.appWhatsApp())
        assertTrue(config.salvarModeloFamilia("Oi {nome}, confirma {sessoes} {quando}?"))
        assertEquals("Oi {nome}, confirma {sessoes} {quando}?", config.modeloFamilia())
    }

    @Test fun modeloSemMarcadoresNaoEGuardado() = runBlocking {
        assertFalse(config.salvarModeloTerapeuta("Oi, tudo bem?"))
        assertEquals(MODELO_TERAPEUTA_PADRAO, config.modeloTerapeuta())
    }
}
