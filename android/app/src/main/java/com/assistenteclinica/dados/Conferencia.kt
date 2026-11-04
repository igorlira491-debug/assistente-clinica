package com.assistenteclinica.dados

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.assistenteclinica.dominio.Conferencia
import com.assistenteclinica.dominio.TabelaLida
import com.assistenteclinica.dominio.conferencia

// Lê todas as tabelas de dados do banco (as do Room e as internas do SQLite ficam de fora) e calcula o código de
// conferência. As linhas são ordenadas por todas as colunas, não pela posição no arquivo: uma cópia pelo
// sqlcipher_export pode gravar em outra ordem física e tem que dar o mesmo código.
fun calcularConferencia(db: SupportSQLiteDatabase): Conferencia {
    val tabelas = db.query(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
            "AND name NOT IN ('room_master_table', 'android_metadata') ORDER BY name",
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    return conferencia(tabelas.map { TabelaLida(it, linhas(db, it)) })
}

private fun linhas(db: SupportSQLiteDatabase, tabela: String): List<List<Any?>> {
    val colunas = db.query("SELECT * FROM `$tabela` LIMIT 0").use { it.columnCount }
    val ordem = (1..colunas).joinToString(", ")
    return db.query("SELECT * FROM `$tabela` ORDER BY $ordem").use { c ->
        buildList { while (c.moveToNext()) add((0 until c.columnCount).map { valor(c, it) }) }
    }
}

private fun valor(c: Cursor, i: Int): Any? = when (c.getType(i)) {
    Cursor.FIELD_TYPE_NULL -> null
    Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
    Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
    Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
    else -> c.getString(i)
}
