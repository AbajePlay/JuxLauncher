package ru.jux.launcher.core

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid
import com.sun.jna.win32.StdCallLibrary
import java.nio.file.Path

/** Проверка цифровой подписи файла (Authenticode) через WinVerifyTrust — без PowerShell и signtool. */
object Authenticode {

    enum class Status { Valid, Unsigned, Invalid }

    fun status(file: Path): Status {
        if (!Shell.isWindows) return Status.Unsigned
        return runCatching { verify(file) }
            .onFailure { Log.warn("WinVerifyTrust failed for $file: ${it.message}") }
            .getOrDefault(Status.Invalid)
    }

    private fun verify(file: Path): Status {
        val info = FileInfo.ByReference().apply {
            pcwszFilePath = WString(file.toAbsolutePath().toString())
            cbStruct = size()
        }
        val data = TrustData().apply {
            dwUIChoice = WTD_UI_NONE
            fdwRevocationChecks = WTD_REVOKE_NONE
            dwUnionChoice = WTD_CHOICE_FILE
            pFile = info
            dwStateAction = WTD_STATEACTION_VERIFY
            dwProvFlags = WTD_REVOCATION_CHECK_NONE or WTD_CACHE_ONLY_URL_RETRIEVAL
            cbStruct = size()
        }
        val result = try {
            WinTrust.INSTANCE.WinVerifyTrust(null, GENERIC_VERIFY_V2, data)
        } finally {
            data.dwStateAction = WTD_STATEACTION_CLOSE
            WinTrust.INSTANCE.WinVerifyTrust(null, GENERIC_VERIFY_V2, data)
        }
        return when (result) {
            0 -> Status.Valid
            TRUST_E_NOSIGNATURE, TRUST_E_SUBJECT_FORM_UNKNOWN, TRUST_E_PROVIDER_UNKNOWN -> Status.Unsigned
            else -> {
                Log.warn("signature of $file is not valid: 0x%08X".format(result))
                Status.Invalid
            }
        }
    }

    private val GENERIC_VERIFY_V2 = Guid.GUID("{00AAC56B-CD44-11d0-8CC2-00C04FC295EE}")
    private const val WTD_UI_NONE = 2
    private const val WTD_REVOKE_NONE = 0
    private const val WTD_CHOICE_FILE = 1
    private const val WTD_STATEACTION_VERIFY = 1
    private const val WTD_STATEACTION_CLOSE = 2
    private const val WTD_REVOCATION_CHECK_NONE = 0x10
    private const val WTD_CACHE_ONLY_URL_RETRIEVAL = 0x1000
    private const val TRUST_E_NOSIGNATURE = 0x800B0100.toInt()
    private const val TRUST_E_SUBJECT_FORM_UNKNOWN = 0x800B0003.toInt()
    private const val TRUST_E_PROVIDER_UNKNOWN = 0x800B0001.toInt()

    @Suppress("FunctionName")
    private interface WinTrust : StdCallLibrary {
        fun WinVerifyTrust(hwnd: Pointer?, action: Guid.GUID, data: TrustData): Int

        companion object {
            val INSTANCE: WinTrust by lazy { Native.load("wintrust", WinTrust::class.java) }
        }
    }

    @Structure.FieldOrder("cbStruct", "pcwszFilePath", "hFile", "pgKnownSubject")
    open class FileInfo : Structure() {
        @JvmField var cbStruct = 0
        @JvmField var pcwszFilePath: WString? = null
        @JvmField var hFile: Pointer? = null
        @JvmField var pgKnownSubject: Pointer? = null

        class ByReference : FileInfo(), Structure.ByReference
    }

    @Structure.FieldOrder(
        "cbStruct", "pPolicyCallbackData", "pSIPClientData", "dwUIChoice", "fdwRevocationChecks", "dwUnionChoice",
        "pFile", "dwStateAction", "hWVTStateData", "pwszURLReference", "dwProvFlags", "dwUIContext", "pSignatureSettings",
    )
    class TrustData : Structure() {
        @JvmField var cbStruct = 0
        @JvmField var pPolicyCallbackData: Pointer? = null
        @JvmField var pSIPClientData: Pointer? = null
        @JvmField var dwUIChoice = 0
        @JvmField var fdwRevocationChecks = 0
        @JvmField var dwUnionChoice = 0
        @JvmField var pFile: FileInfo.ByReference? = null
        @JvmField var dwStateAction = 0
        @JvmField var hWVTStateData: Pointer? = null
        @JvmField var pwszURLReference: WString? = null
        @JvmField var dwProvFlags = 0
        @JvmField var dwUIContext = 0
        @JvmField var pSignatureSettings: Pointer? = null
    }
}
