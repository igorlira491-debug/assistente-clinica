package com.assistenteclinica.seguranca

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// Camada do Android Keystore (Tarefa 4). Nos testes, uma falsa.
interface ProtecaoDoAparelho {
    fun proteger(dados: ByteArray): ByteArray
    fun abrir(dados: ByteArray): ByteArray // lança exceção se não foi este aparelho que protegeu
    // A chave do aparelho quebrou (defeito do Keystore): joga fora e a próxima proteção cria outra. Os embrulhos
    // feitos com a antiga deixam de abrir; a dona refaz o dela pela senha de backup e redefine os das secretárias.
    fun recriar() {}
}

// cifrado = nonce (12 bytes) + AES-GCM. As iterações ficam gravadas para poder mudar o padrão sem quebrar embrulhos antigos.
// `class`, não `data class`: arrays não têm equals útil.
class Embrulho(val sal: ByteArray, val iteracoes: Int, val cifrado: ByteArray)

// A camada do aparelho falhou (chave do Keystore perdida ou com defeito). Não é PIN errado: o PIN nem chegou a ser testado.
class FalhaDoAparelho(causa: Throwable) : Exception(causa)

// Não existe hash do PIN: o PIN certo é o que consegue abrir o embrulho.
object Chaves {
    const val ITERACOES = 600_000
    private const val BYTES_CHAVE = 32
    private const val BYTES_SAL = 16
    private const val BYTES_NONCE = 12
    private const val BITS_TAG = 128
    private val aleatorio = SecureRandom()

    fun novaChavePrincipal(): ByteArray = bytesAleatorios(BYTES_CHAVE)

    // Primeiro o PIN (PBKDF2 + AES-GCM), depois o aparelho por cima: copiar os arquivos para fora do celular não basta.
    fun embrulharComPin(chave: ByteArray, pin: String, aparelho: ProtecaoDoAparelho, iteracoes: Int = ITERACOES): Embrulho {
        val e = embrulhar(chave, pin, iteracoes)
        return Embrulho(e.sal, e.iteracoes, aparelho.proteger(e.cifrado))
    }

    fun abrirComPin(e: Embrulho, pin: String, aparelho: ProtecaoDoAparelho): ByteArray? {
        val cifrado = try { aparelho.abrir(e.cifrado) } catch (ex: Exception) { throw FalhaDoAparelho(ex) }
        return abrir(Embrulho(e.sal, e.iteracoes, cifrado), pin)
    }

    // Sem o aparelho: serve para restaurar num celular novo.
    fun embrulharComSenha(chave: ByteArray, senha: String, iteracoes: Int = ITERACOES): Embrulho =
        embrulhar(chave, senha, iteracoes)

    fun abrirComSenha(e: Embrulho, senha: String): ByteArray? = abrir(e, senha)

    // AES-GCM direto com uma chave pronta (sem PBKDF2): saída = nonce (12 bytes) + cifrado com a etiqueta.
    // aad: dados que não são cifrados, mas entram na etiqueta (o cabeçalho do backup): mudou um byte, não abre.
    fun cifrarComChave(chave: ByteArray, dados: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        val nonce = bytesAleatorios(BYTES_NONCE)
        val cifra = Cipher.getInstance("AES/GCM/NoPadding")
        cifra.init(Cipher.ENCRYPT_MODE, SecretKeySpec(chave, "AES"), GCMParameterSpec(BITS_TAG, nonce))
        cifra.updateAAD(aad)
        return nonce + cifra.doFinal(dados)
    }

    // Chave errada, cifrado ou aad adulterados: null.
    fun decifrarComChave(chave: ByteArray, cifrado: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray? = try {
        val cifra = Cipher.getInstance("AES/GCM/NoPadding")
        cifra.init(Cipher.DECRYPT_MODE, SecretKeySpec(chave, "AES"), GCMParameterSpec(BITS_TAG, cifrado.copyOfRange(0, BYTES_NONCE)))
        cifra.updateAAD(aad)
        cifra.doFinal(cifrado, BYTES_NONCE, cifrado.size - BYTES_NONCE)
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IndexOutOfBoundsException) { // menor que o nonce
        null
    }

    private fun embrulhar(chave: ByteArray, segredo: String, iteracoes: Int): Embrulho {
        val sal = bytesAleatorios(BYTES_SAL)
        val nonce = bytesAleatorios(BYTES_NONCE)
        val cifra = Cipher.getInstance("AES/GCM/NoPadding")
        cifra.init(Cipher.ENCRYPT_MODE, derivar(segredo, sal, iteracoes), GCMParameterSpec(BITS_TAG, nonce))
        return Embrulho(sal, iteracoes, nonce + cifra.doFinal(chave))
    }

    // Segredo errado ou embrulho adulterado: a etiqueta do GCM não confere e vira null.
    private fun abrir(e: Embrulho, segredo: String): ByteArray? = try {
        val nonce = e.cifrado.copyOfRange(0, BYTES_NONCE)
        val cifra = Cipher.getInstance("AES/GCM/NoPadding")
        cifra.init(Cipher.DECRYPT_MODE, derivar(segredo, e.sal, e.iteracoes), GCMParameterSpec(BITS_TAG, nonce))
        cifra.doFinal(e.cifrado, BYTES_NONCE, e.cifrado.size - BYTES_NONCE)
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: IllegalArgumentException) { // iterações ou tamanhos inválidos num embrulho corrompido
        null
    }

    private fun derivar(segredo: String, sal: ByteArray, iteracoes: Int): SecretKeySpec {
        val spec = PBEKeySpec(segredo.toCharArray(), sal, iteracoes, BYTES_CHAVE * 8)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun bytesAleatorios(n: Int) = ByteArray(n).also { aleatorio.nextBytes(it) }
}
