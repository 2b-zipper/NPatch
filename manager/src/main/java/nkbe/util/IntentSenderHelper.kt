package nkbe.util

import android.content.IIntentReceiver
import android.content.IIntentSender
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import android.os.IBinder

/**
 * Helper for creating [IntentSender] instances backed by an in-process
 * [IIntentSender] implementation. Used to receive the result of
 * PackageInstaller sessions without requiring a broadcast receiver.
 */
object IntentSenderHelper {

    /**
     * Wraps the given binder into a real [IntentSender] via reflection,
     * since the public API does not expose a constructor for it.
     */
    fun newIntentSender(binder: IIntentSender): IntentSender {
        return IntentSender::class.java.getConstructor(IIntentSender::class.java).newInstance(binder)
    }

    /**
     * An [IIntentSender] stub that forwards the delivered [Intent] to [listener].
     */
    class IIntentSenderAdaptor(private val listener: (Intent) -> Unit) : IIntentSender.Stub() {
        override fun send(
            code: Int,
            intent: Intent,
            resolvedType: String?,
            finishedReceiver: IIntentReceiver?,
            requiredPermission: String?,
            options: Bundle?
        ): Int {
            listener(intent)
            return 0
        }

        override fun send(
            code: Int,
            intent: Intent,
            resolvedType: String?,
            whitelistToken: IBinder?,
            finishedReceiver: IIntentReceiver?,
            requiredPermission: String?,
            options: Bundle?
        ) {
            listener(intent)
        }
    }
}