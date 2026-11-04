package com.assistenteclinica.dados

import com.assistenteclinica.dominio.AppWhatsApp
import com.assistenteclinica.dominio.MODELO_FAMILIA_PADRAO
import com.assistenteclinica.dominio.MODELO_TERAPEUTA_PADRAO
import com.assistenteclinica.dominio.modeloValido

// Escolhas do Bloco 6 que dependem da secretária: ficam guardadas, com padrão, até ela responder (spec §10).
private const val APP_WHATSAPP = "whatsapp_app"
private const val MODELO_FAMILIA = "modelo_familia"
private const val MODELO_TERAPEUTA = "modelo_terapeuta"

suspend fun ConfiguracaoDao.appWhatsApp(): AppWhatsApp =
    valor(APP_WHATSAPP)?.let { v -> AppWhatsApp.entries.firstOrNull { it.name == v } } ?: AppWhatsApp.COMUM

suspend fun ConfiguracaoDao.salvarAppWhatsApp(app: AppWhatsApp) = salvar(Configuracao(APP_WHATSAPP, app.name))

suspend fun ConfiguracaoDao.modeloFamilia(): String = valor(MODELO_FAMILIA)?.takeIf(::modeloValido) ?: MODELO_FAMILIA_PADRAO
suspend fun ConfiguracaoDao.modeloTerapeuta(): String = valor(MODELO_TERAPEUTA)?.takeIf(::modeloValido) ?: MODELO_TERAPEUTA_PADRAO

// Modelo sem os marcadores não é guardado: devolve false e o padrão continua valendo.
suspend fun ConfiguracaoDao.salvarModeloFamilia(texto: String): Boolean =
    modeloValido(texto).also { if (it) salvar(Configuracao(MODELO_FAMILIA, texto)) }

suspend fun ConfiguracaoDao.salvarModeloTerapeuta(texto: String): Boolean =
    modeloValido(texto).also { if (it) salvar(Configuracao(MODELO_TERAPEUTA, texto)) }
