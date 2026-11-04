package com.assistenteclinica.dados

import android.content.Context
import com.assistenteclinica.dominio.Conferencia
import com.assistenteclinica.seguranca.Chaves
import net.zetetic.database.sqlcipher.SQLiteDatabase

// Bloco 8, etapa 3: a cópia do banco que vai dentro do backup.
class CopiaDoBanco(val chave: ByteArray, val bytes: ByteArray, val versao: Int, val conferencia: Conferencia)

private const val COPIA_EM_PREPARO = "backup_em_preparo.db"

// Copia o banco em uso (o Room pode continuar aberto) para um arquivo temporário cifrado com uma chave nova, calcula
// o código de conferência NA CÓPIA e lê os bytes. O temporário é apagado no fim, mesmo com erro.
// Lições da etapa 0: o ATTACH vai por uma conexão própria, direta e sem WAL ligado nela (pela do Room, o SQLCipher
// tenta desligar o WAL e falha), e essa conexão precisa poder criar o arquivo da cópia.
fun copiarBanco(context: Context, chavePrincipal: ByteArray, nomeBanco: String = NOME_DO_BANCO): CopiaDoBanco {
    System.loadLibrary("sqlcipher")
    val chaveDaCopia = Chaves.novaChavePrincipal()
    val destino = context.getDatabasePath(COPIA_EM_PREPARO)
    context.deleteDatabase(COPIA_EM_PREPARO) // sobra de uma tentativa que morreu no meio
    try {
        val direta = SQLiteDatabase.openDatabase(
            context.getDatabasePath(nomeBanco).absolutePath, chavePrincipal.copyOf(), null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY, null,
        )
        val versao = try {
            direta.execSQL("ATTACH DATABASE ? AS copia KEY ?", arrayOf<Any>(destino.absolutePath, chaveDaCopia))
            direta.rawQuery("SELECT sqlcipher_export('copia')", null).use { it.moveToFirst() }
            direta.execSQL("DETACH DATABASE copia")
            direta.version
        } finally {
            direta.close()
        }
        return CopiaDoBanco(chaveDaCopia, destino.readBytes(), versao, conferenciaDoArquivo(context, COPIA_EM_PREPARO, chaveDaCopia))
    } catch (e: Throwable) {
        chaveDaCopia.fill(0)
        throw e
    } finally {
        context.deleteDatabase(COPIA_EM_PREPARO)
    }
}

// O código de conferência de um arquivo de banco, lido por uma conexão direta, sem o Room: assim nada é migrado.
// A restauração (etapa 5) usa o mesmo, no arquivo restaurado e antes de o app atualizar a versão do banco.
fun conferenciaDoArquivo(context: Context, nome: String, chave: ByteArray): Conferencia {
    System.loadLibrary("sqlcipher")
    val db = SQLiteDatabase.openDatabase(context.getDatabasePath(nome).absolutePath, chave.copyOf(), null, SQLiteDatabase.OPEN_READONLY, null)
    return try { calcularConferencia(db) } finally { db.close() }
}
