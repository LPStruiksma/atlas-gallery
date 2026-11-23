package nl.atlas.gallery.featureorganization

/**
 * #783: what a Copy/Move target tap may do. The tap must always end in a
 * transfer attempt or visible feedback, never a silent return.
 */
enum class BatchTransferGate { IGNORE, SELECTION_CHANGED, ACCESS_CHANGED, TOO_MANY, PROCEED }

fun gateBatchTransfer(
    selection: GalleryBatchSelection?,
    operating: Boolean,
    accessCurrent: Boolean,
    sdkOk: Boolean
): BatchTransferGate = when {
    !sdkOk || operating -> BatchTransferGate.IGNORE
    selection == null || selection.count == 0 -> BatchTransferGate.SELECTION_CHANGED
    !accessCurrent -> BatchTransferGate.ACCESS_CHANGED
    selection.count > MAX_BATCH_SHARE_ITEMS -> BatchTransferGate.TOO_MANY
    else -> BatchTransferGate.PROCEED
}
