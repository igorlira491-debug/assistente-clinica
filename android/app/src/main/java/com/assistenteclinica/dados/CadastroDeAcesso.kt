package com.assistenteclinica.dados

import android.content.Context
import android.util.Base64
import com.assistenteclinica.dominio.Papel
import com.assistenteclinica.dominio.Usuaria
import com.assistenteclinica.seguranca.Embrulho
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

// Fica FORA do banco cifrado: a tela "Quem é você?" precisa da lista antes do PIN.
// Guarda só o nome das funcionárias, os embrulhos e os erros; nunca dado de criança.
// Grava com commit() (síncrono): fechar o app à força logo após um erro não pode zerar a contagem.
class CadastroDeAcesso(context: Context, nomeArquivo: String = "acesso") {
    private val prefs = context.getSharedPreferences(nomeArquivo, Context.MODE_PRIVATE)

    fun usuarias(): List<Usuaria> {
        val lista = JSONArray(prefs.getString(USUARIAS, "[]"))
        return (0 until lista.length()).map {
            val o = lista.getJSONObject(it)
            Usuaria(o.getLong("id"), o.getString("nome"), Papel.valueOf(o.getString("papel")), o.getBoolean("ativa"))
        }
    }

    fun salvarUsuaria(u: Usuaria) = salvarUsuarias(usuarias().filter { it.id != u.id } + u)

    // Todas de uma vez (restauração): ou a lista inteira fica, ou nenhuma, e o app continua em "primeiro uso".
    fun salvarUsuarias(lista: List<Usuaria>) {
        val json = JSONArray()
        lista.sortedBy { it.id }.forEach {
            json.put(JSONObject().put("id", it.id).put("nome", it.nome).put("papel", it.papel.name).put("ativa", it.ativa))
        }
        gravar { putString(USUARIAS, json.toString()) }
    }

    fun primeiroUso(): Boolean = usuarias().isEmpty()

    fun embrulhoDe(id: Long): Embrulho? = ler("$EMBRULHO$id")
    fun salvarEmbrulho(id: Long, e: Embrulho) = gravar { putString("$EMBRULHO$id", texto(e)) }
    fun removerEmbrulho(id: Long) = gravar { remove("$EMBRULHO$id") }

    fun embrulhoBackup(): Embrulho? = ler(BACKUP)
    fun salvarEmbrulhoBackup(e: Embrulho) = gravar { putString(BACKUP, texto(e)) }

    // Chave de backup (Bloco 8): pela chave principal (para o backup diário, com o app destravado) e pela senha de
    // backup (vai dentro de cada arquivo). As duas gravam juntas: uma sem a outra não serve.
    fun chaveDeBackupPelaPrincipal(): ByteArray? = prefs.getString(CHAVE_BACKUP_PRINCIPAL, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
    fun chaveDeBackupPelaSenha(): Embrulho? = ler(CHAVE_BACKUP_SENHA)
    fun salvarChaveDeBackup(pelaPrincipal: ByteArray, pelaSenha: Embrulho) =
        gravar { putString(CHAVE_BACKUP_PRINCIPAL, b64(pelaPrincipal)); putString(CHAVE_BACKUP_SENHA, texto(pelaSenha)) }

    // Trocar a senha de backup: o embrulho da chave principal e a chave de backup nova gravam juntos.
    fun salvarSenhaDeBackup(embrulho: Embrulho, chavePelaPrincipal: ByteArray, chavePelaSenha: Embrulho) = gravar {
        putString(BACKUP, texto(embrulho))
        putString(CHAVE_BACKUP_PRINCIPAL, b64(chavePelaPrincipal))
        putString(CHAVE_BACKUP_SENHA, texto(chavePelaSenha))
    }

    fun erros(id: Long): Int = prefs.getInt("$ERROS$id", 0)
    fun ultimoErro(id: Long): Instant? = prefs.getLong("$ULTIMO_ERRO$id", -1).takeIf { it >= 0 }?.let(Instant::ofEpochMilli)

    fun registrarErro(id: Long, agora: Instant) =
        gravar { putInt("$ERROS$id", erros(id) + 1); putLong("$ULTIMO_ERRO$id", agora.toEpochMilli()) }

    fun zerarErros(id: Long) = gravar { remove("$ERROS$id"); remove("$ULTIMO_ERRO$id") }

    private fun gravar(bloco: android.content.SharedPreferences.Editor.() -> Unit) {
        check(prefs.edit().apply(bloco).commit()) { "não consegui gravar o cadastro de acesso" }
    }

    // Formato: sal:iteracoes:cifrado, com sal e cifrado em Base64.
    private fun texto(e: Embrulho) = "${b64(e.sal)}:${e.iteracoes}:${b64(e.cifrado)}"

    private fun ler(chave: String): Embrulho? {
        val (sal, iteracoes, cifrado) = prefs.getString(chave, null)?.split(":") ?: return null
        return Embrulho(Base64.decode(sal, Base64.NO_WRAP), iteracoes.toInt(), Base64.decode(cifrado, Base64.NO_WRAP))
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    private companion object {
        const val USUARIAS = "usuarias"
        const val EMBRULHO = "embrulho_"
        const val BACKUP = "embrulho_backup"
        const val CHAVE_BACKUP_PRINCIPAL = "chave_backup_principal"
        const val CHAVE_BACKUP_SENHA = "chave_backup_senha"
        const val ERROS = "erros_"
        const val ULTIMO_ERRO = "ultimo_erro_"
    }
}
