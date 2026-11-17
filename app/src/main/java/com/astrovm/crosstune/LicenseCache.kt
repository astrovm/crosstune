package com.astrovm.crosstune

import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/** Holds one activity's claim on the shared texts across a configuration change. */
internal class LicenseCacheOwner : ViewModel() {
    var cache by mutableStateOf<LicenseCache?>(null)
        private set

    fun enter() {
        synchronized(owners) {
            cache = owners.firstOrNull()?.cache ?: LicenseCache()
            owners.add(this)
        }
    }

    fun leave() {
        synchronized(owners) {
            val leaving = cache ?: return
            owners.remove(this)
            if (owners.isEmpty()) leaving.close()
            cache = null
        }
    }

    override fun onCleared() = leave()

    private companion object {
        val owners = mutableSetOf<LicenseCacheOwner>()
    }
}

/** The texts kept from the first Licenses page opening until the last one leaves. */
internal class LicenseCache {
    private val lock = Any()
    private var texts: Texts? = Texts()

    private class Texts {
        val pieces = HashMap<String, List<LicenseUnit>>()
        val words = HashMap<String, Map<String, NoticeWords>>()
        val splitLocks = HashMap<String, Any>()
    }

    fun pieces(name: String): List<LicenseUnit>? = synchronized(lock) { texts?.pieces?.get(name) }

    /** Splits one work at a time per name. The page never takes this lock. */
    fun make(notice: Notice) {
        val workLock = synchronized(lock) { texts?.splitLocks?.getOrPut(notice.name) { Any() } } ?: return
        synchronized(workLock) {
            synchronized(lock) {
                val current = texts ?: return
                if (notice.name in current.pieces) return
            }
            val made = split(notice)
            synchronized(lock) { texts?.pieces?.set(notice.name, made) }
        }
    }

    fun wordsRead(tag: String): Map<String, NoticeWords>? = synchronized(lock) { texts?.words?.get(tag) }

    /** Reads the words off the main thread, outside the lock the page uses. */
    fun words(tag: String): Map<String, NoticeWords>? {
        synchronized(lock) {
            val current = texts ?: return null
            current.words[tag]?.let { return it }
        }
        val made = Notices.translate(tag)
        return synchronized(lock) { texts?.words?.getOrPut(tag) { made } }
    }

    /** Drops the texts and invalidates work already scheduled against this cache. */
    fun close() {
        synchronized(lock) { texts = null }
    }

    companion object {
        /** Reads and splits a notice's texts. Tests can hold a split here. */
        @VisibleForTesting
        var split: (Notice) -> List<LicenseUnit> = { licenseUnits(Notices.text(it)) }
    }
}
