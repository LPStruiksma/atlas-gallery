# Automatic reference curation

The curator proposes a small, useful subset of already indexed local faces. It is a pure function: it does not query or update People, read a clock, call a network service, load a model, or expose UI. A caller may show the proposal and separately persist only a user's decision.

## Inputs and policy

Each candidate supplies its stable local id, YuNet's five landmarks, similarity to the profile centre, and optional capture time, versioned face-quality score, size-floor flag and normalised lighting. Missing quality, light or date is neutral rather than fatal. The caller supplies the limit, identity-similarity floor, optional inclusive capture-date/age window and an explicit reference time for recency.

Faces below the similarity floor are identity outliers. Faces outside the date window, with unusable landmarks, or below the existing size floor are also rejected. Every rejection is returned with a reason; no row is changed or silently reassigned.

Eligible faces are greedily ranked by profile similarity (40%), available quality (30%), pose/expression/lighting diversity (20%) and recency (10%). Pose and expression are coarse estimates from existing YuNet geometry, not a new model. Stable ids break otherwise equal scores, so ordering of the input, locale, wall clock and device do not change the answer.

The result can contain fewer than the requested count. Empty input, a zero limit, missing nullable metadata and fewer-than-limit input are valid. These properties make the selector suitable for offline unit tests and reproducible proposals, while keeping People as read-only source data.
