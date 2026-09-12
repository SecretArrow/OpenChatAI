package com.openchai.app.permissions

import com.openchai.core.agent.PermissionBroker
import com.openchai.core.agent.PermissionDecision
import com.openchai.core.agent.PermissionRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Implementasi [PermissionBroker] referensi:
 *  - request() suspend sampai user menjawab lewat respond() (CompletableDeferred),
 *  - approval per-session: tool yang sudah di- AllowSession tidak bertanya lagi
 *    (dibersihkan di awal tiap run via clearSession),
 *  - hanya SATU request pending aktif (antrean FIFO sederhana) supaya UI selalu
 *    menampilkan dialog yang paling relevan.
 *
 * Thread-safe: dipanggil dari tool loop (Dispatchers.IO) dan UI (main).
 */
class DefaultPermissionBroker : PermissionBroker {

    private class Waiter(val req: PermissionRequest) {
        val deferred = CompletableDeferred<PermissionDecision>()
    }

    private val mutex = Any()
    private val waiters = ArrayDeque<Waiter>()
    private val sessionApproved = ConcurrentHashMap<String, MutableSet<String>>()

    private val _pending = MutableStateFlow<PermissionRequest?>(null)
    override val pending: StateFlow<PermissionRequest?> = _pending.asStateFlow()

    override suspend fun request(req: PermissionRequest): PermissionDecision {
        // Sudah di-approve untuk session ini → langsung izinkan tanpa UI.
        val approved = sessionApproved[req.sessionId]
        if (approved != null && req.toolName in approved) {
            return PermissionDecision.AllowOnce
        }
        val waiter = Waiter(req)
        synchronized(mutex) {
            waiters.addLast(waiter)
            if (_pending.value == null) promoteNextLocked()
        }
        return waiter.deferred.await()
    }

    override fun respond(reqId: String, decision: PermissionDecision) {
        val waiter = synchronized(mutex) {
            val found = waiters.firstOrNull { it.req.id == reqId }
            if (found != null) waiters.remove(found)
            if (waiters.isEmpty()) _pending.value = null else promoteNextLocked()
            found
        } ?: return
        if (decision is PermissionDecision.AllowSession) {
            sessionApproved.getOrPut(waiter.req.sessionId) { ConcurrentHashMap.newKeySet() }
                .add(waiter.req.toolName)
        }
        waiter.deferred.complete(decision)
    }

    override fun clearSession(sessionId: String) {
        sessionApproved.remove(sessionId)
    }

    override fun clearAll() {
        synchronized(mutex) {
            waiters.forEach { it.deferred.cancel() }
            waiters.clear()
            _pending.value = null
        }
        sessionApproved.clear()
    }

    /** Panggil di DALAM synchronized(mutex): promote waiter kepala antrean ke pending. */
    private fun promoteNextLocked() {
        _pending.value = waiters.firstOrNull()?.req
    }

    companion object {
        /** Id request baru (konvensi "perm-<uuid>"). */
        fun newId(): String = "perm-" + UUID.randomUUID().toString()
    }
}
