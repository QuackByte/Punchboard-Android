package dev.quackbyte.punchboard.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The desktop's icons (Lucide, ISC licence), as 24-unit stroke paths. */
object Lucide {
    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(
                    pathData = addPathNodes(d),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0"

    val ChevronLeft = icon("chevron-left", "m15 18-6-6 6-6")
    val ChevronRight = icon("chevron-right", "m9 18 6-6-6-6")
    val ChevronDown = icon("chevron-down", "m6 9 6 6 6-6")
    val Settings = icon("settings-2", "M20 7h-9", "M14 17H5", circle(17f, 17f, 3f), circle(7f, 7f, 3f))
    val LogIn = icon("log-in", "M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4", "m10 17 5-5-5-5", "M15 12H3")
    val LogOut = icon("log-out", "M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4", "m16 17 5-5-5-5", "M21 12H9")
    val Clock = icon("clock", circle(12f, 12f, 10f), "M12 6v6l4 2")
    val Calendar = icon("calendar", "M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M16 2v4", "M8 2v4", "M3 10h18")
    val CalendarClock = icon(
        "calendar-clock",
        "M21 7.5V6a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h3.5",
        "M16 2v4", "M8 2v4", "M3 10h5", "M17.5 17.5 16 16.3V14", circle(16f, 16f, 6f),
    )
    val BarChart = icon("bar-chart", "M3 3v18h18", "M18 17V9", "M13 17V5", "M8 17v-3")
    val Receipt = icon(
        "receipt",
        "M4 2v20l2-1 2 1 2-1 2 1 2-1 2 1 2-1 2 1V2l-2 1-2-1-2 1-2-1-2 1-2-1-2 1Z",
        "M16 8h-6a2 2 0 1 0 0 4h4a2 2 0 1 1 0 4H8", "M12 17.5v-11",
    )
    val Plus = icon("plus", "M5 12h14", "M12 5v14")
    val X = icon("x", "M18 6 6 18", "m6 6 12 12")
    val RotateCcw = icon("rotate-ccw", "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5")
    val ArrowRight = icon("arrow-right", "M5 12h14", "m12 5 7 7-7 7")
    val ArrowLeft = icon("arrow-left", "M19 12H5", "m12 19-7-7 7-7")
    val StickyNote = icon("sticky-note", "M16 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V8Z", "M15 3v4a2 2 0 0 0 2 2h4")
    val Download = icon("download", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "m7 10 5 5 5-5", "M12 15V3")
    val Upload = icon("upload", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "m17 8-5-5-5 5", "M12 3v12")
    val File = icon("file", "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z", "M14 2v4a2 2 0 0 0 2 2h4")
    val Sheet = icon("sheet", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M3 9h18", "M3 15h18", "M9 9v12", "M15 9v12")
    val Printer = icon("printer", "M6 9V2h12v7", "M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2", "M6 14h12v8H6z")
    val Check = icon("check", "M20 6 9 17l-5-5")
    val Sun = icon(
        "sun", circle(12f, 12f, 4f), "M12 2v2", "M12 20v2", "m4.93 4.93 1.41 1.41", "m17.66 17.66 1.41 1.41",
        "M2 12h2", "M20 12h2", "m6.34 17.66-1.41 1.41", "m19.07 4.93-1.41 1.41",
    )
    val Moon = icon("moon", "M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z")
    val Monitor = icon("monitor", "M4 3h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M8 21h8", "M12 17v4")
    val Bell = icon("bell", "M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9", "M10.3 21a1.94 1.94 0 0 0 3.4 0")
    val RefreshCw = icon(
        "refresh-cw",
        "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8", "M21 3v5h-5",
        "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16", "M8 16H3v5",
    )
    val Link = icon("link", "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71", "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71")
    val Unlink = icon("unlink", "m18.84 12.25 1.72-1.71h-.02a5 5 0 1 0-7.07-7.07l-1.72 1.71", "m5.17 11.75-1.71 1.71a5 5 0 0 0 7.07 7.07l1.71-1.71", "M8 2v3", "M2 8h3", "M16 22v-3", "M22 16h-3")
    val ExternalLink = icon("external-link", "M15 3h6v6", "M10 14 21 3", "M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6")
}
