package casa.moller.tandem.core.designsystem.icon

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import casa.moller.tandem.core.designsystem.R

// Icons of the books, favourites and "Vill laga" (Phase 2, books lane, feature #17), converted like
// the others in [TandemIcons] (Material Symbols Rounded, google/material-design-icons at the pinned
// commit in assets/licenses/NOTICE.txt, git blob hashes checked on download 2026-10-25). Declared as
// extensions in their own file so that lanes adding icons in parallel do not edit the same lines.

/** Not a favourite (heart outline). */
val TandemIcons.Favorite: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_favorite)

/** A favourite (filled heart). */
val TandemIcons.FavoriteFilled: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_favorite_filled)

/** "Vill laga" off (bookmark outline). */
val TandemIcons.Bookmark: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_bookmark)

/** "Vill laga" on (filled bookmark). */
val TandemIcons.BookmarkFilled: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_bookmark_filled)

/** Recipe books. */
val TandemIcons.CollectionsBookmark: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_collections_bookmark)

/** Recipe books, selected. */
val TandemIcons.CollectionsBookmarkFilled: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_collections_bookmark_filled)

/** "Lägg i bok" (add to a book). */
val TandemIcons.LibraryAdd: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_library_add)
