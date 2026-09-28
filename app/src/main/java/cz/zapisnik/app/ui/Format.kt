package cz.zapisnik.app.ui

import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Months = listOf("leden", "únor", "březen", "duben", "květen", "červen", "červenec", "srpen", "září", "říjen", "listopad", "prosinec")

fun today(): String = LocalDate.now().toString()

fun fmtDate(iso: String): String = runCatching {
    val d = LocalDate.parse(iso); "${d.dayOfMonth}. ${d.monthValue}. ${d.year}"
}.getOrDefault(iso)

fun monthLabel(iso: String): String = runCatching {
    val d = LocalDate.parse(iso); "${Months[d.monthValue - 1]} ${d.year}"
}.getOrDefault("Bez data")

fun fmtTimestamp(ms: Long): String =
    DateTimeFormatter.ofPattern("d. M. yyyy H:mm").format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** Malá písmena bez diakritiky, aby „olej“ našlo i „OLEJE“. */
fun fold(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

fun countLabel(n: Int): String = when {
    n == 1 -> "1 záznam"
    n in 2..4 -> "$n záznamy"
    else -> "$n záznamů"
}
