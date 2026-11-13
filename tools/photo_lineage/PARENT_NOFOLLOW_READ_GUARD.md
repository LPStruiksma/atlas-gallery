# No-follow protection for parent directories in Photo DNA

Lane B / #83. The descriptor-anchored reader from #367 protects its
final filename against symlink replacement, but POSIX O_NOFOLLOW
alone does not refuse symlinked *parent* components. A path such
as photos/alias/photo.jpg could otherwise traverse outside its
intended tree. This update closes that specific path component gap.

Starting from the absolute root, the offline research loader now
opens each parent component read-only with O_DIRECTORY + O_NOFOLLOW,
retaining the open descriptor through each next directory open.
The final image is opened relative to its already anchored parent
via dir_fd + O_NOFOLLOW. Pillow receives only that open handle.
Parent symlinks (including those introduced mid-traversal) cannot
redirect a successful open; renaming the parent after it has been
opened likewise does not change what the directory descriptor refers to.
Unsupported nofollow flags and explicit .. parent traversal fail closed.

Three additional all-fictional tests verify preexisting parent symlinks,
a parent replaced immediately before its directory open, and a parent
renamed and replaced immediately before its image leaf open. Existing
leaf-symlink and file-replacement tests remain mandatory.

This is not a hostile OS/mount-namespace sandbox, cannot stop in-place
writes to an already opened inode, and cannot authenticate a camera
source or prove image lineage. The SQLite indexer's before/after
metadata checks remain separate. No user photos, live caches, native
Android, release/signing, People DB, V4/V5, network, credentials or
automatic merges/deletions are touched.
