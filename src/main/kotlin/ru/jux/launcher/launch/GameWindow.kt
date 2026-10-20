package ru.jux.launcher.launch

import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import ru.jux.launcher.core.Log
import ru.jux.launcher.core.Shell

object GameWindow {

    private const val POLL_MILLIS = 500L
    // Большие сборки на Forge грузятся минутами; дольше ждать нет смысла
    private const val TIMEOUT_MILLIS = 180_000L

    /**
     * Ждёт, пока у игры появится видимое окно. true — окно открылось (или ждать надоело),
     * false — игра закрылась раньше. Вне Windows окна не видно, считаем открытым сразу.
     */
    suspend fun awaitShown(process: Process): Boolean = withContext(Dispatchers.IO) {
        if (!Shell.isWindows) return@withContext process.isAlive
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (process.isAlive) {
            if (hasVisibleWindow(process)) return@withContext true
            if (System.currentTimeMillis() > deadline) {
                Log.warn("no game window after ${TIMEOUT_MILLIS / 1000} s, treating the game as open")
                return@withContext true
            }
            delay(POLL_MILLIS)
        }
        false
    }

    private fun hasVisibleWindow(process: Process): Boolean = runCatching {
        val pids = buildSet {
            add(process.pid())
            process.descendants().forEach { add(it.pid()) }
        }
        val owner = IntByReference()
        val rect = WinDef.RECT()
        var found = false
        User32.INSTANCE.EnumWindows(WinUser.WNDENUMPROC { window, _ ->
            User32.INSTANCE.GetWindowThreadProcessId(window, owner)
            found = owner.value.toLong() in pids &&
                User32.INSTANCE.IsWindowVisible(window) &&
                User32.INSTANCE.GetWindowRect(window, rect) &&
                rect.right - rect.left > 1 && rect.bottom - rect.top > 1
            !found
        }, null)
        found
    }.onFailure { Log.debug("game window lookup failed: ${it.message}") }.getOrDefault(false)
}
