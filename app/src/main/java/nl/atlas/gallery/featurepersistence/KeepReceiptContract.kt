package nl.atlas.gallery.featurepersistence

import java.security.MessageDigest

/** A saved keeper never authorizes Trash; only currently verified exact families count. */
data class VerifiedKeepMember(
    val mediaKey: String,
    val contentVersion: String,
    val byteSize: Long,
    val sha256: String,
    val readableNow: Boolean,
    val digestVerified: Boolean
)
data class VerifiedKeepFamily(
    val familyKey: String,
    val members: List<VerifiedKeepMember>
)
data class KeepReceipt(
    val familyKey: String,
    val keeperKey: String,
    val keeperVersion: String,
    val verifiedSha256: String,
    val familyMembersSha256: String
)

object KeepReceiptContract {
    private val sha = Regex("[0-9a-f]{64}")
    const val MAX_BATCH = 256
    const val MAX_MEMBERS = 200_000

    /** Reject visual matches, inaccessible originals, stale digest or fake membership. */
    fun validate(family: VerifiedKeepFamily, chosenKeeperKey: String): KeepReceipt {
        val members = family.members
        require(members.size in 2..MAX_MEMBERS) { "Not a valid multi-copy family" }
        val first = members.first()
        require(first.byteSize >= 0 && sha.matches(first.sha256)) { "Invalid exact digest" }
        require(family.familyKey == "sha256:${first.byteSize}:${first.sha256}") {
            "Family digest/size mismatch"
        }
        val keys = HashSet<String>(members.size)
        members.forEach { m ->
            require(m.mediaKey.isNotBlank() && m.mediaKey.length <= 1024 &&
                !m.mediaKey.startsWith("/") && '\u0000' !in m.mediaKey &&
                m.contentVersion.isNotBlank() && m.contentVersion.length <= 512 &&
                '\u0000' !in m.contentVersion &&
                wellFormedUtf16(m.mediaKey) && wellFormedUtf16(m.contentVersion)) {
                "Invalid or lossy media identity/revision"
            }
            require(m.digestVerified && m.readableNow && m.byteSize == first.byteSize &&
                m.sha256 == first.sha256) { "Family source is unverified or inaccessible" }
            require(keys.add(m.mediaKey)) { "Duplicate family member identity" }
        }
        val keeper = members.singleOrNull { it.mediaKey == chosenKeeperKey }
            ?: throw IllegalArgumentException("Keeper is not in verified family")
        return KeepReceipt(family.familyKey, keeper.mediaKey, keeper.contentVersion,
            first.sha256, memberScopeDigest(family))
    }

    // UTF-8's default encoder substitutes replacement bytes for unpaired
    // UTF-16 surrogates. Distinct provider revisions must not hash identically.
    private fun wellFormedUtf16(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val c = value[index]
            if (c.isHighSurrogate()) {
                if (++index >= value.length || !value[index].isLowSurrogate()) return false
            } else if (c.isLowSurrogate()) return false
            index++
        }
        return true
    }

    /** Length-delimited canonical membership; no filename inference or pairwise guesses. */
    private fun memberScopeDigest(family: VerifiedKeepFamily): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(byteArrayOf(
                (bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(),
                (bytes.size ushr 8).toByte(), bytes.size.toByte()))
            digest.update(bytes)
        }
        add("atlas-keep-membership-v1")
        add(family.familyKey)
        family.members.sortedBy { it.mediaKey }.forEach { member ->
            add(member.mediaKey)
            add(member.contentVersion)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** A record is active only when trusted CURRENT evidence independently matches. */
    fun remainsValid(saved: KeepReceipt, current: VerifiedKeepFamily): Boolean =
        runCatching { validate(current, saved.keeperKey) == saved }.getOrDefault(false)
}
