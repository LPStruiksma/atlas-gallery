package nl.atlas.gallery.featurepersistence

/**
 * Pure, side-effect-free confirmed-People merge decision. The caller MUST hold
 * its SQLite transaction open from reading [existing] until applying inserts.
 *
 * No facial similarity or filename matching: face IDs here are the known V4
 * namespace ONLY, pending a separate confirmed namespace contract with P/U.
 */
object PeopleMergePlanner {
    fun missingAssignments(
        existing: Collection<PeopleBackupRow>,
        incoming: Collection<PeopleBackupRow>
    ): List<PeopleBackupRow> {
        require(existing.size <= PeopleBackupCodec.MAX_ROWS &&
            incoming.size <= PeopleBackupCodec.MAX_ROWS) {
            "People merge row limit exceeded"
        }
        PeopleBackupCodec.validateRows(incoming)
        val byFace = HashMap<Long, PeopleBackupRow>(existing.size)
        val knownNames = HashMap<Long, String>()
        for (row in existing) {
            require(byFace.putIfAbsent(row.faceId, row) == null) {
                "Existing People face identifiers conflict; no import performed"
            }
            val oldName = knownNames.putIfAbsent(row.personId, row.personName)
            require(oldName == null || oldName == row.personName) {
                "Existing person names conflict; no import performed"
            }
        }
        val incomingFaceIds = HashSet<Long>()
        val insertions = ArrayList<PeopleBackupRow>()
        for (row in incoming) {
            require(incomingFaceIds.add(row.faceId)) {
                "Duplicate incoming face identifier; no import performed"
            }
            require(row.faceId >= 0 && row.personId >= 0) {
                "Invalid incoming People identifier"
            }
            val old = byFace[row.faceId]
            require(old == null || old == row) {
                "Existing face assignment conflicts; no import performed"
            }
            val oldName = knownNames.putIfAbsent(row.personId, row.personName)
            require(oldName == null || oldName == row.personName) {
                "Existing person name conflicts; no import performed"
            }
            if (old == null) insertions.add(row)
        }
        // An individually valid incoming backup may add rows to an already
        // large native People DB. Admit the COMBINED result, not the two
        // separately valid inputs, before the SQLite caller inserts anything.
        // Identical input rows already present are excluded from insertions.
        PeopleBackupCodec.checkedMergedDocumentSize(existing, insertions)
        return insertions
    }
}
