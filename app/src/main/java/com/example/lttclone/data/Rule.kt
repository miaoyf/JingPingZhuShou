package com.example.lttclone.data

data class Rule(
    val id: String,
    val action: String,
    val times: Int = 1
)

data class AppRules(
    val popupRules: List<Rule>,
    val clickWayPopup: Boolean = false,
    val times: Int = 1,
    val delayPopup: Long = 0L,
    val unitePopupRules: Boolean = false,
    val searchTimesPopup: Int = 5
)
