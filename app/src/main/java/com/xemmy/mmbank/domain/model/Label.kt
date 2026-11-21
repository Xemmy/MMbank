package com.xemmy.mmbank.domain.model

/** A free-form tag a transaction can carry (several per transaction), e.g. "Holiday" or "Tax". */
data class Label(
    val id: Long = 0,
    val name: String,
    val colorHex: String,
)
