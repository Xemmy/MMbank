package com.xemmy.mmbank.domain.export

/** File formats the Activity export offers. */
enum class ExportFormat(
    val label: String,
    val hint: String,
    val extension: String,
    val mimeType: String,
) {
    CSV("CSV", "Spreadsheet: opens in Excel or Google Sheets", "csv", "text/csv"),
    JSON("JSON", "Best for AI: named fields and real types", "json", "application/json"),
    ;

    fun fileName(stamp: String): String = "mmbank-activity-$stamp.$extension"

    companion object {
        /** Lenient parse of a stored preference; unknown or blank gives [CSV]. */
        fun fromStored(value: String?): ExportFormat = entries.firstOrNull { it.name == value } ?: CSV
    }
}
