package dev.lumen.launcher.auth

/** Every failure carries a message written for the player, not the developer. */
sealed class AuthException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotConfigured : AuthException(
        "Microsoft sign-in isn't set up in this build. Add your Azure app's client ID as " +
            "lumen.msaClientId in ~/.gradle/gradle.properties (or the LUMEN_MSA_CLIENT_ID environment variable).",
    )
    class AppNotFound : AuthException("Microsoft doesn't recognise this launcher's client ID. Check the Azure app registration.")
    class PublicClientDisabled : AuthException(
        "The Azure app doesn't allow device-code sign-in. Enable \"Allow public client flows\" under Authentication.",
    )
    class AppNotApproved : AuthException(
        "This launcher's Azure app isn't approved for Minecraft logins yet. Mojang must approve the client ID first.",
    )
    class Declined : AuthException("Sign-in was cancelled in the browser.")
    class CodeExpired : AuthException("The sign-in code expired before it was used. Try again.")
    class SessionExpired : AuthException("Your Microsoft session has expired. Sign in again.")
    class NoXboxAccount : AuthException(
        "This Microsoft account has no Xbox profile yet. Sign in once at xbox.com to create one, then try again.",
    )
    class XboxUnavailableInRegion : AuthException("Xbox Live isn't available in this account's country or region.")
    class AdultVerificationRequired : AuthException("This account needs adult verification on xbox.com before it can play.")
    class ChildAccount : AuthException(
        "This is a child account. An adult must add it to a Microsoft Family group before it can sign in.",
    )
    class NoMinecraft : AuthException(
        "This account doesn't own Minecraft: Java Edition, or hasn't chosen a player name yet " +
            "(Game Pass players: start the official launcher once to pick one).",
    )
    class Service(what: String, detail: String) : AuthException("$what failed: $detail")
}
