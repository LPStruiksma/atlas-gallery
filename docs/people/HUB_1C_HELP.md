# People · Same person? and Needs splitting (HUB-1C)

In **People → ⋮ → Same person?**, each question compares two groups of faces. **Yes** and **No** answer whether the groups depict the same person; **Skip** defers a question without changing any review facts.

When either group appears to contain multiple people, choose **Not one person** beneath that side. You can select one or both sides and confirm **Mark this group** or **Mark both groups**. A successful mark records the group as needing a manual split and removes its question from the queue. **Undo** reverses that last successful mark only while no other review has replaced it; it restores the previous queue and count. Marking does not itself split, join, name or move faces.

**People → ⋮ → Needs splitting (N)** opens the groups marked in the currently authorized visible People scope. A named group opens its existing person page; an unnamed group enters the existing manual review flow. This release does not yet deep-link that flow to an individual unnamed group.

**People → ⋮ → Hidden → ⋮ → Needs splitting** lists marked hidden named people separately, inside Hidden only. The ordinary People count and list never include hidden people, and the Hidden entry gives no hidden count. Changing visibility or photo access re-evaluates the lists from the current authorized review state.

**Skip** is kept as an app-private preference for the current photo permission scope, not as a review decision. It survives reopening the dialog and app relaunch while that scope and both groups' actual memberships remain unchanged. Changes to either membership invalidate the old Skip fingerprint. **Show skipped**, in the People overflow or the queue's empty view, clears Skip preferences for the current scope and shows eligible questions again. The open question count includes skipped questions; it is not a count of currently visible cards.

All actions require the currently authorized photo grant. After revocation, leave People and regrant access before making further review changes. These features never perform automatic splitting or face matching.
