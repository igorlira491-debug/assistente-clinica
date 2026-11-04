package com.assistenteclinica.seguranca

import com.assistenteclinica.dominio.Conferencia
import com.assistenteclinica.dominio.LIMITE_DO_ARQUIVO_DE_BACKUP
import com.assistenteclinica.dominio.Papel
import com.assistenteclinica.dominio.Usuaria
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.time.DateTimeException
import java.time.LocalDateTime

// Bloco 8, etapa 3: o arquivo de backup (plano 2026-10-29, parte 1). Sem Android aqui: formato e cifra testados
// no computador.
//
// Chaves:
// - chave de backup: aleatória, criada uma vez quando a dona configura o backup (com a senha digitada). Vai no
//   arquivo embrulhada pela senha (PBKDF2 + AES-GCM, Chaves.embrulharComSenha); no celular, fica embrulhada pela
//   chave principal. Assim o backup diário sai sem pedir a senha, e a chave principal nunca vai para o arquivo.
// - chave do arquivo: aleatória a cada backup, embrulhada pela chave de backup. Cifra o conteúdo inteiro.
// - chave do banco: aleatória a cada backup, a do sqlcipher_export. Vai DENTRO do conteúdo cifrado.
//
// Arquivo = cabeçalho em claro + conteúdo cifrado (AES-GCM, com o cabeçalho como dado autenticado). No cabeçalho
// só há o que é preciso para abrir: marca, formato, o embrulho da chave de backup e o da chave do arquivo. Data,
// usuárias, código de conferência e o banco ficam todos dentro do conteúdo cifrado.
// A garantia de que nada mudou é a etiqueta do AES-GCM (e a do SQLCipher, página a página, no banco); o código de
// conferência só confere que o banco restaurado é o mesmo que foi copiado.

class ConteudoDoBackup(
    val versaoDoBanco: Int,
    val criadoEm: LocalDateTime,
    val usuarias: List<Usuaria>,
    val conferencia: Conferencia,
    val chaveDoBanco: ByteArray,
    val banco: ByteArray,
)

sealed interface AberturaDoBackup {
    class Ok(val conteudo: ConteudoDoBackup) : AberturaDoBackup
    data object NaoEBackup : AberturaDoBackup // não começa com a marca: outro arquivo qualquer
    data object SenhaErrada : AberturaDoBackup // o embrulho da chave de backup não abre (ou o cabeçalho foi mexido)
    data object Danificado : AberturaDoBackup // a senha abriu, mas o conteúdo não confere: cortado ou alterado
    data class VersaoMaisNova(val versao: Int) : AberturaDoBackup // feito por um app mais novo que este
}

object ArquivoDeBackup {
    const val FORMATO = 1
    private val MARCA = "ASSISTENTE-BACKUP".toByteArray(Charsets.US_ASCII)
    // Um campo maior que o arquivo inteiro pode ser é arquivo corrompido. montar() recusa passar disso: um backup
    // gravado sempre abre.
    private const val LIMITE_DE_CAMPO = LIMITE_DO_ARQUIVO_DE_BACKUP.toInt()
    // Rodadas do PBKDF2 aceitas: um cabeçalho mexido com um número enorme travaria a restauração.
    private val RODADAS_ACEITAS = 1..10_000_000

    fun montar(c: ConteudoDoBackup, chaveDeBackup: ByteArray, embrulhoDaChaveDeBackup: Embrulho): ByteArray {
        val chaveDoArquivo = Chaves.novaChavePrincipal()
        try {
            val cabecalho = escrever {
                write(MARCA)
                writeInt(FORMATO)
                campo(embrulhoDaChaveDeBackup.sal)
                writeInt(embrulhoDaChaveDeBackup.iteracoes)
                campo(embrulhoDaChaveDeBackup.cifrado)
                campo(Chaves.cifrarComChave(chaveDeBackup, chaveDoArquivo))
            }
            val conteudo = conteudoEmBytes(c)
            check(conteudo.size.toLong() + cabecalho.size + 1024 <= LIMITE_DO_ARQUIVO_DE_BACKUP) { "backup maior que o limite" }
            return escrever {
                write(cabecalho)
                campo(Chaves.cifrarComChave(chaveDoArquivo, conteudo, aad = cabecalho))
            }
        } finally {
            chaveDoArquivo.fill(0)
        }
    }

    // versaoDoApp: a versão do banco que este app conhece. Um backup de banco mais novo não é aberto.
    fun abrir(arquivo: ByteArray, senha: String, versaoDoApp: Int): AberturaDoBackup {
        if (arquivo.size < MARCA.size || !arquivo.copyOf(MARCA.size).contentEquals(MARCA)) return AberturaDoBackup.NaoEBackup
        return try {
            val entrada = DataInputStream(ByteArrayInputStream(arquivo, MARCA.size, arquivo.size - MARCA.size))
            val formato = entrada.readInt()
            if (formato > FORMATO) return AberturaDoBackup.VersaoMaisNova(formato)
            val embrulho = Embrulho(entrada.campo(), entrada.readInt(), entrada.campo())
            if (embrulho.iteracoes !in RODADAS_ACEITAS) return AberturaDoBackup.Danificado
            val chaveDoArquivoEmbrulhada = entrada.campo()
            val tamanhoDoCabecalho = arquivo.size - entrada.available()
            val cifrado = entrada.campo()
            if (entrada.available() != 0) return AberturaDoBackup.Danificado
            val cabecalho = arquivo.copyOf(tamanhoDoCabecalho)

            val chaveDeBackup = Chaves.abrirComSenha(embrulho, senha) ?: return AberturaDoBackup.SenhaErrada
            val chaveDoArquivo = Chaves.decifrarComChave(chaveDeBackup, chaveDoArquivoEmbrulhada)
            chaveDeBackup.fill(0)
            if (chaveDoArquivo == null) return AberturaDoBackup.Danificado
            val bytes = Chaves.decifrarComChave(chaveDoArquivo, cifrado, aad = cabecalho)
            chaveDoArquivo.fill(0)
            if (bytes == null) return AberturaDoBackup.Danificado
            val c = conteudoDeBytes(bytes)
            if (c.versaoDoBanco > versaoDoApp) AberturaDoBackup.VersaoMaisNova(c.versaoDoBanco) else AberturaDoBackup.Ok(c)
        } catch (_: IOException) {
            AberturaDoBackup.Danificado // cortado no meio de um campo
        } catch (_: IllegalArgumentException) {
            AberturaDoBackup.Danificado // papel que não existe: só um arquivo mexido chega aqui
        } catch (_: DateTimeException) {
            AberturaDoBackup.Danificado // data que não existe, idem
        }
    }

    private fun conteudoEmBytes(c: ConteudoDoBackup) = escrever {
        writeInt(c.versaoDoBanco)
        writeUTF(c.criadoEm.toString())
        writeInt(c.usuarias.size)
        c.usuarias.forEach { writeLong(it.id); writeUTF(it.nome); writeUTF(it.papel.name); writeBoolean(it.ativa) }
        writeUTF(c.conferencia.codigo)
        writeInt(c.conferencia.quantidades.size)
        c.conferencia.quantidades.forEach { (tabela, n) -> writeUTF(tabela); writeInt(n) }
        campo(c.chaveDoBanco)
        campo(c.banco)
    }

    private fun conteudoDeBytes(b: ByteArray): ConteudoDoBackup {
        val e = DataInputStream(ByteArrayInputStream(b))
        val versao = e.readInt()
        val criadoEm = LocalDateTime.parse(e.readUTF())
        val usuarias = List(e.contagem()) { Usuaria(e.readLong(), e.readUTF(), Papel.valueOf(e.readUTF()), e.readBoolean()) }
        val codigo = e.readUTF()
        val quantidades = (0 until e.contagem()).associate { e.readUTF() to e.readInt() }
        val c = ConteudoDoBackup(versao, criadoEm, usuarias, Conferencia(quantidades, codigo), e.campo(), e.campo())
        if (e.available() != 0) throw IOException("sobrou conteúdo")
        return c
    }

    private fun escrever(bloco: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { DataOutputStream(it).apply(bloco).flush() }.toByteArray()

    private fun DataOutputStream.campo(b: ByteArray) { writeInt(b.size); write(b) }

    private fun DataInputStream.campo(): ByteArray {
        val n = readInt()
        if (n < 0 || n > LIMITE_DE_CAMPO || n > available()) throw EOFException("campo de $n bytes")
        return ByteArray(n).also { readFully(it) }
    }

    private fun DataInputStream.contagem(): Int = readInt().also { if (it < 0 || it > 100_000) throw IOException("contagem $it") }
}
