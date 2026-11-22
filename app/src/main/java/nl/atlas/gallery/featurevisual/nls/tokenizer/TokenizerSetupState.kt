package nl.atlas.gallery.featurevisual.nls.tokenizer

/** Explicit setup status; encoding must never initiate a download. */
sealed interface TokenizerSetupState {
    data object Absent : TokenizerSetupState
    data object Downloading : TokenizerSetupState
    data object Ready : TokenizerSetupState
    data object Corrupt : TokenizerSetupState
    data object NetworkUnavailable : TokenizerSetupState
    data object ChecksumFailure : TokenizerSetupState
}
