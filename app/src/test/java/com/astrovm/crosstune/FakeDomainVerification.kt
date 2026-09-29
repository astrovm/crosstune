package com.astrovm.crosstune

import android.app.Application
import android.content.Context
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.os.Process
import org.robolectric.Shadows.shadowOf
import java.lang.reflect.Proxy
import java.util.UUID

/**
 * Robolectric has no domain verification service, so this installs a real
 * [DomainVerificationManager] backed by a stand-in for the hidden system binder.
 */
internal object FakeDomainVerification {

    /** [hostStates] null makes the system report the package as unknown. */
    fun install(app: Application, hostStates: () -> Map<String, Int>?) =
        installPerPackage(app) { hostStates() }

    /**
     * Like [install], but [state] answers for whichever package is asked about, e.g. an installed
     * music app, and [linkHandlingAllowed] is that package's "Open supported links" switch.
     */
    fun installPerPackage(
        app: Application,
        linkHandlingAllowed: (packageName: String) -> Boolean = { true },
        state: (packageName: String) -> Map<String, Int>?
    ) {
        val binder = Class.forName("android.content.pm.verify.domain.IDomainVerificationManager")
        val service = Proxy.newProxyInstance(binder.classLoader, arrayOf(binder)) { _, method, args ->
            check(method.name == "getDomainVerificationUserState") { "Unexpected call ${method.name}" }
            val packageName = args[0] as String
            val states = state(packageName) ?: throw nameNotFound()
            userState(packageName, states, linkHandlingAllowed(packageName))
        }
        val manager = DomainVerificationManager::class.java
            .getDeclaredConstructor(Context::class.java, binder)
            .apply { isAccessible = true }
            .newInstance(app, service)
        shadowOf(app).setSystemService(Context.DOMAIN_VERIFICATION_SERVICE, manager)
    }

    /** The binder's way of saying the package is unknown; the manager turns it into NameNotFoundException. */
    private fun nameNotFound(): Throwable {
        val code = DomainVerificationManager::class.java.getDeclaredField("INTERNAL_ERROR_NAME_NOT_FOUND").getInt(null)
        // ServiceSpecificException is hidden from the SDK stubs Robolectric tests compile against.
        return Class.forName("android.os.ServiceSpecificException")
            .getConstructor(Int::class.javaPrimitiveType)
            .newInstance(code) as Throwable
    }

    private fun userState(packageName: String, states: Map<String, Int>, linkHandlingAllowed: Boolean): DomainVerificationUserState =
        DomainVerificationUserState::class.java.declaredConstructors
            .first { it.parameterCount == 5 }
            .apply { isAccessible = true }
            .newInstance(UUID.randomUUID(), packageName, Process.myUserHandle(), linkHandlingAllowed, states) as DomainVerificationUserState
}
