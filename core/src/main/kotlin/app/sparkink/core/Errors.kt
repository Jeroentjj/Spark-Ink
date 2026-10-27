package app.sparkink.core

open class WacomException(message: String) : Exception(message)

/** The pad answered with an error code (b3 xx). */
class DeviceError(val code: Int) : WacomException("Device error $code") {
    val invalidState get() = code == INVALID_STATE

    companion object {
        const val GENERAL_ERROR = 1
        const val INVALID_STATE = 2
        const val AUTHORIZATION_ERROR = 7
    }
}

/** The pad does not recognise our registration id: pair it again. */
class AuthorizationError : WacomException("Pad does not know this app (pair it again)")

class MissingReply(what: String) : WacomException("No reply from pad: $what")

class UnexpectedReply(msg: String) : WacomException(msg)
