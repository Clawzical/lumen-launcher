package dev.lumen.launcher.auth.secrets

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Minimal Advapi32 credential API surface. */
@Suppress("FunctionName")
internal interface CredApi : StdCallLibrary {
    fun CredWriteW(credential: Credential, flags: Int): Boolean
    fun CredReadW(targetName: WString, type: Int, flags: Int, credential: PointerByReference): Boolean
    fun CredDeleteW(targetName: WString, type: Int, flags: Int): Boolean
    fun CredFree(buffer: Pointer)

    companion object {
        val INSTANCE: CredApi by lazy { Native.load("Advapi32", CredApi::class.java) }
    }
}

/** CREDENTIALW from wincred.h. */
@Suppress("PropertyName")
@Structure.FieldOrder(
    "Flags", "Type", "TargetName", "Comment", "LastWritten", "CredentialBlobSize",
    "CredentialBlob", "Persist", "AttributeCount", "Attributes", "TargetAlias", "UserName",
)
internal class Credential : Structure {
    @JvmField var Flags: Int = 0
    @JvmField var Type: Int = 0
    @JvmField var TargetName: WString? = null
    @JvmField var Comment: WString? = null
    @JvmField var LastWritten: WinBase.FILETIME = WinBase.FILETIME()
    @JvmField var CredentialBlobSize: Int = 0
    @JvmField var CredentialBlob: Pointer? = null
    @JvmField var Persist: Int = 0
    @JvmField var AttributeCount: Int = 0
    @JvmField var Attributes: Pointer? = null
    @JvmField var TargetAlias: WString? = null
    @JvmField var UserName: WString? = null

    constructor() : super()
    constructor(p: Pointer) : super(p) { read() }
}

/**
 * Windows Credential Manager (visible under "Windows Credentials" as `Lumen/<key>`), encrypted
 * by Windows with the user's login (DPAPI). A credential blob is capped at 2,560 bytes, so long
 * secrets are split across `Lumen/<key>#0..n` with a small header entry.
 */
class WindowsCredentialStore(private val service: String) : SecretStore {
    private val api: CredApi? = runCatching { CredApi.INSTANCE }.getOrNull()

    override val isAvailable: Boolean get() = api != null
    override val description = "Windows Credential Manager"

    override suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
        val head = read(target(key)) ?: return@withContext null
        if (!head.startsWith(CHUNK_HEADER)) return@withContext head.removePrefix(PLAIN_HEADER)
        val count = head.removePrefix(CHUNK_HEADER).toInt()
        buildString { repeat(count) { i -> append(read(target(key, i)) ?: return@withContext null) } }
    }

    override suspend fun put(key: String, secret: String) = withContext(Dispatchers.IO) {
        deleteBlocking(key) // drop stale chunks if the new secret is shorter
        val bytes = (PLAIN_HEADER + secret).toByteArray(Charsets.UTF_8)
        if (bytes.size <= MAX_BLOB) {
            write(target(key), PLAIN_HEADER + secret)
        } else {
            val chunks = secret.chunked(CHUNK_CHARS) // ASCII tokens: 1 char = 1 byte
            chunks.forEachIndexed { i, part -> write(target(key, i), part) }
            write(target(key), CHUNK_HEADER + chunks.size)
        }
    }

    override suspend fun delete(key: String) = withContext(Dispatchers.IO) { deleteBlocking(key) }

    private fun deleteBlocking(key: String) {
        val head = read(target(key))
        if (head != null && head.startsWith(CHUNK_HEADER)) {
            repeat(head.removePrefix(CHUNK_HEADER).toInt()) { i -> api!!.CredDeleteW(WString(target(key, i)), CRED_TYPE_GENERIC, 0) }
        }
        api!!.CredDeleteW(WString(target(key)), CRED_TYPE_GENERIC, 0)
    }

    private fun target(key: String, chunk: Int? = null) = "$service/$key" + (chunk?.let { "#$it" } ?: "")

    private fun write(target: String, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val blob = Memory(bytes.size.toLong()).apply { write(0, bytes, 0, bytes.size) }
        val cred = Credential().apply {
            Type = CRED_TYPE_GENERIC
            TargetName = WString(target)
            UserName = WString(service)
            CredentialBlobSize = bytes.size
            CredentialBlob = blob
            Persist = CRED_PERSIST_LOCAL_MACHINE
        }
        check(api!!.CredWriteW(cred, 0)) { "CredWrite failed (error ${Native.getLastError()})" }
    }

    private fun read(target: String): String? {
        val ref = PointerByReference()
        if (!api!!.CredReadW(WString(target), CRED_TYPE_GENERIC, 0, ref)) return null // ERROR_NOT_FOUND
        return try {
            val cred = Credential(ref.value)
            cred.CredentialBlob?.getByteArray(0, cred.CredentialBlobSize)?.toString(Charsets.UTF_8)
        } finally {
            api.CredFree(ref.value)
        }
    }

    private companion object {
        const val CRED_TYPE_GENERIC = 1
        const val CRED_PERSIST_LOCAL_MACHINE = 2
        const val MAX_BLOB = 2_560 // CRED_MAX_CREDENTIAL_BLOB_SIZE
        const val CHUNK_CHARS = 2_400
        const val PLAIN_HEADER = "v1:"
        const val CHUNK_HEADER = "chunks:"
    }
}
