# People · Same person? and Needs splitting (HUB-1C)

In **People → ⋮ → Same person?**, each question compares two groups of faces. **Yes** and **No** answer whether the groups depict the same person; **Skip** defers a question without changing any review facts.

When either group appears to contain multiple people, choose **Not one person** beneath that side. You can select one or both sides and confirm **Mark this group** or **Mark both groups**. A successful mark records the group as needing a manual split and removes its question from the queue. **Undo** reverses that last successful mark only while no other review has replaced it; it restores the previous queue and count. Marking does not itself split, join, name or move faces.


The pencil beside either group's name opens the existing name editor without leaving Same person?. Enter a new name and choose **Save** to name the group, or edit an existing name to rename that person. **Name search** matches visible existing names by text, not facial similarity; **More people** expands the typed results. Choosing an existing name (or typing an exact match) shows both people before any merge. **Cancel** leaves the question and names unchanged; **Merge** explicitly assigns the source group/person to the pictured existing person. After a successful save the queue refreshes the same question if it still applies, otherwise advances. **Undo** reverses the last acknowledged name, rename, assignment or mixed mark only while its review generation is current. If the group or photo access changes while the editor is open, return to the refreshed question before retrying. Hidden people are not suggested by the ordinary queue.

**People → ⋮ → Needs splitting (N)** opens the groups marked in the currently authorized visible People scope. A named group opens its existing person page; an unnamed group opens that group's existing page and manual split controls.

**People → ⋮ → Hidden → Needs splitting (H)** is a secondary action directly below Hidden's search/header. It lists marked hidden named people separately, inside Hidden only; H counts only active marks visible in that Hidden scope. The ordinary People count and list never include hidden people, and the ordinary People **Hidden** entry gives no hidden count. Changing visibility or photo access re-evaluates the lists from the current authorized review state.

**Skip** is kept as an app-private preference for the current photo permission scope, not as a review decision. It survives reopening the dialog and app relaunch while that scope and both groups' actual memberships remain unchanged. Changes to either membership invalidate the old Skip fingerprint. **Show skipped**, in the People overflow or the queue's empty view, clears Skip preferences for the current scope and shows eligible questions again. The open question count includes skipped questions; it is not a count of currently visible cards.

All actions require the currently authorized photo grant. After revocation, leave People and regrant access before making further review changes. These features never perform automatic splitting or face matching.
