package com.xemmy.mmbank.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.xemmy.mmbank.R

/**
 * Obsidian Ledger's three type roles (Phase 5 spec 5.1). Only Medium/SemiBold
 * weights are bundled — the design never calls for Regular display or Bold.
 */
object AppFonts {
    /** Display / titles / avatar initials. */
    val spaceGrotesk = FontFamily(
        Font(R.font.space_grotesk_medium, FontWeight.Medium),
        Font(R.font.space_grotesk_semibold, FontWeight.SemiBold),
    )

    /** Body copy and uppercase meta labels. */
    val inter = FontFamily(
        Font(R.font.inter_regular, FontWeight.Normal),
        Font(R.font.inter_medium, FontWeight.Medium),
        Font(R.font.inter_semibold, FontWeight.SemiBold),
    )

    /** Amounts and other tabular-numeral fields. */
    val plexMono = FontFamily(
        Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
        Font(R.font.ibm_plex_mono_semibold, FontWeight.SemiBold),
    )
}
