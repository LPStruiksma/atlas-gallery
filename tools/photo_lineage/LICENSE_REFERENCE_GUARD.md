# Strict offline license-reference consistency in photo-source cohorts

Lane B / coordination issue #83. A photo manifest cannot promote a
license label to trusted evidence merely because license_url is an
HTTPS URL. Previously a CC-BY-4.0 label with an unrelated, attacker-
controlled or CC-BY-SA reference passed syntax validation.

New license_reference_guard.consistent_license_reference requires
recognized Creative Commons IDs to link to the matching canonical
version-specific reference on creativecommons.org, with HTTPS, no
credentials/port/query/fragment, no encoded path ambiguity or dot
traversal. CC0-1.0, CC-BY-4.0 and CC-BY-SA-4.0 have distinct reference
paths. PUBLIC-DOMAIN is intentionally treated as a status CLAIM:
accept only the official CC Public Domain Mark informational URL or a
curated Wikimedia Commons Template:PD-* page as a reference needing
manual curator rights review. The PD Mark itself is not a grant.

After this change, the offline heldout evaluator refuses a
label/reference mismatch BEFORE any real image decoding. Synthetic
tests check version confusion, spoofed hosts, URL credentials, ports,
control characters, encoded aliases, public-domain claims and a
previously valid CC-BY fixture. This is an admission/consistency
check, not an online license lookup or legal copyright determination.

All actual source media still require independently verified rights,
pinned SHA-256 bytes, separately claimed physical shutters and
scene-separated holdout; a caption, camera EXIF, URL or geometric
candidate cannot certify two files share an exposure. No Gallery,
People, device, network, original media or production application
files are changed.
