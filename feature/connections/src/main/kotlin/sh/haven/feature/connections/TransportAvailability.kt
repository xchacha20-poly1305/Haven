package sh.haven.feature.connections

/**
 * Which transports the new-connection picker offers, given what this build
 * actually shipped (#510).
 *
 * Extracted from the dialog so the rule can be asserted. A gate that lives
 * only inside a Composable is a gate nobody can prove fired.
 */
internal object TransportAvailability {

    fun offered(
        all: List<Pair<String, String>>,
        rdp: Boolean,
        spice: Boolean,

    ): List<Pair<String, String>> = all.filter { (value, _) ->
        when (value) {
            "RDP" -> rdp
            "SPICE" -> spice
            "RCLONE", "OPENAI", "RETICULUM" -> false
            "LOCAL", "GUEST", "EMAIL" -> false
            else -> true
        }
    }
}
