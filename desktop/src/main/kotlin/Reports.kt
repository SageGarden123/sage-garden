import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import com.openhtmltopdf.svgsupport.BatikSVGDrawer
import org.apache.batik.transcoder.TranscoderInput
import org.apache.batik.transcoder.TranscoderOutput
import org.apache.batik.transcoder.image.PNGTranscoder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

// ============================================================================
// Printable garden reports and map export.
//
// Everything is drawn as SVG (maps, legends) inside XHTML pages, rendered to PDF by openhtmltopdf,
// so maps stay sharp at any print size and the same map SVG exports directly as SVG or PNG.
// Colours and symbols match the phone app: irrigation zones use its zone palette and hash, plant
// dots use its category colours.
// ============================================================================

data class ReportInput(
    val gardenName: String,
    val meta: GardenMeta,
    val plants: List<Plant>,
    val plan: GardenPlan?,
    val planImage: File?,
    val now: Long = System.currentTimeMillis(),
)

private const val DAY = 86_400_000L
private val dateFmt get() = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
private val shortDateFmt get() = SimpleDateFormat("EEE d MMM", Locale.getDefault())

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

// ---- Season -------------------------------------------------------------------------------------

data class Season(val name: String, val growingPeriod: String)

/** Meteorological season for the garden's hemisphere, with an early/mid/late qualifier and what that period means for the garden. */
fun seasonFor(now: Long, southern: Boolean): Season {
    val month = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.MONTH) // 0 = Jan
    // Shift so that index 0 = the first month of spring for this hemisphere.
    val springStart = if (southern) Calendar.SEPTEMBER else Calendar.MARCH
    val i = (month - springStart + 12) % 12
    val stage = listOf("Early", "Mid", "Late")[i % 3]
    return when (i / 3) {
        0 -> Season("$stage spring", "Main growing season — plant, feed and mulch")
        1 -> Season("$stage summer", "Peak growth — water deeply and watch for heat stress")
        2 -> Season("$stage autumn", "Planting and tidy-up season — divide, plant and prepare for cooler weather")
        else -> Season("$stage winter", "Dormant season — prune deciduous plants and plan ahead")
    }
}

// ---- Care tasks ---------------------------------------------------------------------------------

enum class CarePriority(val label: String, val cssClass: String) {
    OVERDUE("Overdue", "p-overdue"), TODAY("Today", "p-today"), WEEK("This week", "p-week"), SOON("Next 2 weeks", "p-soon")
}

data class ReportTask(val plant: Plant, val action: String, val due: Long?, val lastDone: Long?, val priority: CarePriority)

private fun startOfDay(t: Long) = Calendar.getInstance().apply {
    timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** Every watering/pruning/fertilising/feeding task due within [horizonDays] (or overdue), most urgent first. */
fun careTasks(input: ReportInput, horizonDays: Int = 14): List<ReportTask> {
    val today = startOfDay(input.now)
    fun priority(due: Long?): CarePriority? = when {
        due == null -> CarePriority.TODAY // never done — do now
        due < today -> CarePriority.OVERDUE
        due < today + DAY -> CarePriority.TODAY
        due < today + 7 * DAY -> CarePriority.WEEK
        due < today + horizonDays * DAY -> CarePriority.SOON
        else -> null
    }
    val southern = input.meta.southernHemisphere
    return input.plants.flatMap { p ->
        listOfNotNull(
            computeWateringStatus(p, input.now, southern)?.let { s -> priority(s.nextDueMillis)?.let { ReportTask(p, "Water", s.nextDueMillis, p.lastWateredDate, it) } },
            computePruneStatus(p, input.now)?.let { s -> priority(s.nextDueMillis)?.let { ReportTask(p, "Prune", s.nextDueMillis, p.lastPrunedDate, it) } },
            computeFertiliseStatus(p, input.now)?.let { s -> priority(s.nextDueMillis)?.let { ReportTask(p, "Fertilise", s.nextDueMillis, p.lastFertilisedDate, it) } },
            computeFeedStatus(p, input.now)?.let { s -> priority(s.nextDueMillis)?.let { ReportTask(p, "Feed", s.nextDueMillis, p.lastFedDate, it) } },
        )
    }.sortedWith(compareBy({ it.priority.ordinal }, { it.due ?: 0L }, { it.plant.name.lowercase() }))
}

// ---- Map drawing --------------------------------------------------------------------------------

/** The phone's irrigation-zone palette and hash (MapScreen.kt colorForZone) — so zones keep their colours across apps. */
private val zonePalette = listOf("#3D8FB0", "#B0793D", "#6B3DB0", "#3DB073", "#B03D6B", "#B0AA3D", "#3D62B0", "#8FB03D")
fun zoneColor(zone: String) = zonePalette[abs(zone.hashCode()) % zonePalette.size]

/** The phone's category dot colours (PlantOptions.kt categoryMarkerColor). */
fun categoryColor(category: String) = when (category) {
    "Trees" -> "#2E7D32"; "Shrubs" -> "#558B2F"; "Ground Cover" -> "#9E9D24"; "Climbers/Vines" -> "#00897B"
    "Grasses" -> "#F9A825"; "Ferns" -> "#00695C"; "Perennials" -> "#D81B60"; "Annuals" -> "#FB8C00"
    "Bulbs" -> "#8E24AA"; "Succulents" -> "#00ACC1"; "Palms/Cycads" -> "#6D4C41"; "Aquatic" -> "#1E88E5"
    "Herbs" -> "#43A047"; else -> "#FF7A45"
}

private val sunZoneColors = mapOf(
    "full_sun" to ("#F9A825" to "Full sun"), "morning_sun" to ("#FFD54F" to "Morning sun"),
    "afternoon_sun" to ("#FB8C00" to "Afternoon sun"), "part_shade" to ("#90A4AE" to "Part shade"),
    "full_shade" to ("#546E7A" to "Full shade"),
)

/** Plants in the order they're numbered on the map and listed in the index: by zone, then name. */
fun numberedPlants(plants: List<Plant>): List<Plant> =
    plants.sortedWith(compareBy({ it.location.ifBlank { "￿" }.lowercase() }, { it.name.lowercase() }))

data class MapOptions(
    val width: Int = 1000,
    val numbered: Boolean = true,
    val showIrrigation: Boolean = true,
    val showSunZones: Boolean = false,
    val showLegend: Boolean = true,
    val title: String? = null,
)

/**
 * The garden map as a standalone SVG: the owner's uploaded map image (when synced) with plants,
 * irrigation lines coloured by zone and styled by type, optional sun zones, and a legend. Without
 * an uploaded map, plants placed on the satellite map are drawn to scale on a plain grid instead.
 */
fun mapSvg(input: ReportInput, o: MapOptions): String {
    val w = o.width.toDouble()
    val image = input.planImage?.takeIf { it.exists() && input.plan?.imageWidth != null && input.plan.imageHeight != null }
    val plants = numberedPlants(input.plants)
    val numbers = plants.withIndex().associate { (i, p) -> p.id to i + 1 }
    val body = StringBuilder()
    val titleH = if (o.title != null) w * 0.05 else 0.0
    val mapH: Double

    fun marker(x: Double, y: Double, p: Plant) {
        val r = if (o.numbered) w * 0.012 else w * 0.008
        body.append("""<circle cx="$x" cy="$y" r="$r" fill="${categoryColor(p.category)}" stroke="#FFFFFF" stroke-width="${w * 0.0018}"/>""")
        if (o.numbered) {
            body.append("""<text x="$x" y="${y + r * 0.38}" font-family="Helvetica, Arial, sans-serif" font-size="${r * 1.05}" font-weight="bold" fill="#FFFFFF" text-anchor="middle">${numbers[p.id]}</text>""")
        }
    }

    if (image != null) {
        val iw = input.plan!!.imageWidth!!.toDouble(); val ih = input.plan.imageHeight!!.toDouble()
        mapH = w * ih / iw
        val data = Base64.getEncoder().encodeToString(image.readBytes())
        body.append("""<image x="0" y="$titleH" width="$w" height="$mapH" preserveAspectRatio="none" xlink:href="data:image/jpeg;base64,$data"/>""")
        fun px(x: Double) = x * w
        fun py(y: Double) = titleH + y * mapH
        if (o.showSunZones) input.plan.sunZones.filter { it.mapType == "custom" && it.points.size >= 3 }.forEach { z ->
            val c = sunZoneColors[z.category]?.first ?: "#FFD54F"
            body.append("""<polygon points="${z.points.joinToString(" ") { "${px(it.first)},${py(it.second)}" }}" fill="$c" fill-opacity="0.28" stroke="$c" stroke-width="${w * 0.002}"/>""")
        }
        if (o.showIrrigation) input.plan.paths.forEach { path ->
            val c = zoneColor(path.zone)
            path.segments.forEach { seg ->
                val pts = seg.points.joinToString(" ") { "${px(it.first)},${py(it.second)}" }
                when (seg.type) {
                    "sprinkler" -> seg.points.firstOrNull()?.let { (x, y) ->
                        val r = (seg.radius ?: 0.08) * w
                        body.append("""<circle cx="${px(x)}" cy="${py(y)}" r="$r" fill="$c" fill-opacity="0.18" stroke="$c" stroke-width="${w * 0.003}"/>""")
                        body.append("""<circle cx="${px(x)}" cy="${py(y)}" r="${w * 0.004}" fill="$c"/>""")
                    }
                    "impact_sprinkler" -> {
                        body.append("""<polyline points="$pts" fill="none" stroke="$c" stroke-opacity="0.25" stroke-width="${w * 0.02}" stroke-linecap="round"/>""")
                        body.append("""<polyline points="$pts" fill="none" stroke="$c" stroke-width="${w * 0.003}" stroke-dasharray="${w * 0.002},${w * 0.006}" stroke-linecap="round"/>""")
                    }
                    "drip" -> body.append("""<polyline points="$pts" fill="none" stroke="$c" stroke-width="${w * 0.0035}" stroke-dasharray="${w * 0.009},${w * 0.007}" stroke-linecap="round"/>""")
                    else -> body.append("""<polyline points="$pts" fill="none" stroke="$c" stroke-width="${w * 0.008}" stroke-linecap="round" stroke-linejoin="round"/>""")
                }
            }
            body.append("""<circle cx="${px(path.outletX)}" cy="${py(path.outletY)}" r="${w * 0.009}" fill="$c" stroke="#FFFFFF" stroke-width="${w * 0.003}"/>""")
        }
        plants.forEach { p -> val x = p.mapX; val y = p.mapY; if (x != null && y != null) marker(px(x), py(y), p) }
    } else {
        val placed = plants.filter { it.lat != null && it.lng != null }
        mapH = w * 0.62
        body.append("""<rect x="0" y="$titleH" width="$w" height="$mapH" fill="#F4F1E8"/>""")
        for (i in 1..9) body.append("""<line x1="${w * i / 10}" y1="$titleH" x2="${w * i / 10}" y2="${titleH + mapH}" stroke="#E2DDD0" stroke-width="1"/>""")
        for (i in 1..5) body.append("""<line x1="0" y1="${titleH + mapH * i / 6}" x2="$w" y2="${titleH + mapH * i / 6}" stroke="#E2DDD0" stroke-width="1"/>""")
        if (placed.isEmpty()) {
            body.append("""<text x="${w / 2}" y="${titleH + mapH / 2}" font-family="Helvetica, Arial, sans-serif" font-size="${w * 0.022}" fill="#7A7667" text-anchor="middle">No map yet — upload a garden map on your phone, or place plants on the map.</text>""")
        } else {
            val lat0 = placed.map { it.lat!! }.average()
            val kx = cos(Math.toRadians(lat0))
            val xs = placed.map { it.lng!! * kx }; val ys = placed.map { -it.lat!! }
            val spanX = max(xs.max() - xs.min(), 1e-6); val spanY = max(ys.max() - ys.min(), 1e-6)
            val scale = min(w * 0.86 / spanX, mapH * 0.82 / spanY)
            val ox = (w - spanX * scale) / 2 - xs.min() * scale
            val oy = titleH + (mapH - spanY * scale) / 2 - ys.min() * scale
            placed.forEach { p -> marker(p.lng!! * kx * scale + ox, -p.lat!! * scale + oy, p) }
            // Scale bar: metres per pixel from degrees-of-latitude distance.
            val metresPerPx = 111_320.0 / scale
            val barMetres = listOf(1, 2, 5, 10, 20, 50, 100, 200, 500).firstOrNull { it / metresPerPx > w * 0.08 } ?: 1000
            val barPx = barMetres / metresPerPx
            val by = titleH + mapH - w * 0.03
            body.append("""<line x1="${w * 0.03}" y1="$by" x2="${w * 0.03 + barPx}" y2="$by" stroke="#4A4739" stroke-width="${w * 0.003}"/>""")
            body.append("""<text x="${w * 0.03}" y="${by - w * 0.008}" font-family="Helvetica, Arial, sans-serif" font-size="${w * 0.016}" fill="#4A4739">$barMetres m</text>""")
        }
    }
    body.append("""<rect x="0" y="$titleH" width="$w" height="$mapH" fill="none" stroke="#CBC6B5" stroke-width="1"/>""")
    if (o.title != null) {
        body.append("""<text x="0" y="${titleH * 0.7}" font-family="Helvetica, Arial, sans-serif" font-size="${titleH * 0.6}" font-weight="bold" fill="#233821">${esc(o.title)}</text>""")
    }

    var totalH = titleH + mapH
    if (o.showLegend) {
        val legend = legendSvg(input, o, plants, totalH + w * 0.02, w)
        body.append(legend.first)
        totalH = legend.second
    }
    return """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="${w.toInt()}" height="${totalH.toInt() + 2}" viewBox="0 0 $w ${totalH + 2}">""" +
        """<rect x="0" y="0" width="$w" height="${totalH + 2}" fill="#FFFFFF"/>""" + body + "</svg>"
}

/** Legend block (SVG fragment, bottom y) — every line style, symbol, zone colour and plant category used. */
private fun legendSvg(input: ReportInput, o: MapOptions, plants: List<Plant>, top: Double, w: Double): Pair<String, Double> {
    val sb = StringBuilder()
    val fs = w * 0.016
    val rowH = fs * 1.9
    val colW = w / 3
    var y = top + fs
    fun heading(text: String, x: Double, yy: Double) =
        sb.append("""<text x="$x" y="$yy" font-family="Helvetica, Arial, sans-serif" font-size="${fs * 1.05}" font-weight="bold" fill="#233821">${esc(text)}</text>""")
    fun label(text: String, x: Double, yy: Double) =
        sb.append("""<text x="$x" y="$yy" font-family="Helvetica, Arial, sans-serif" font-size="$fs" fill="#1B1C18">${esc(text)}</text>""")

    val columns = mutableListOf<Double>()
    // Column 1: irrigation symbols (only when the plan has any).
    val plan = input.plan
    var col = 0
    if (o.showIrrigation && plan != null && plan.paths.isNotEmpty()) {
        val x = col * colW; var yy = y
        heading("Irrigation", x, yy); yy += rowH
        val grey = "#4A4739"
        val types = plan.paths.flatMap { p -> p.segments.map { it.type } }.toSet()
        fun sample(draw: String, text: String) { sb.append(draw); label(text, x + w * 0.06, yy + fs * 0.35); yy += rowH }
        if ("main" in types) sample("""<line x1="$x" y1="$yy" x2="${x + w * 0.045}" y2="$yy" stroke="$grey" stroke-width="${w * 0.008}" stroke-linecap="round"/>""", "Main line")
        if ("drip" in types) sample("""<line x1="$x" y1="$yy" x2="${x + w * 0.045}" y2="$yy" stroke="$grey" stroke-width="${w * 0.0035}" stroke-dasharray="${w * 0.009},${w * 0.007}"/>""", "Drip line")
        if ("sprinkler" in types) sample("""<circle cx="${x + w * 0.022}" cy="$yy" r="${fs * 0.7}" fill="$grey" fill-opacity="0.18" stroke="$grey" stroke-width="${w * 0.003}"/>""", "Sprinkler (coverage)")
        if ("impact_sprinkler" in types) sample("""<line x1="$x" y1="$yy" x2="${x + w * 0.045}" y2="$yy" stroke="$grey" stroke-width="${w * 0.003}" stroke-dasharray="${w * 0.002},${w * 0.006}" stroke-linecap="round"/>""", "Impact sprinkler")
        sample("""<circle cx="${x + w * 0.022}" cy="$yy" r="${w * 0.009}" fill="$grey" stroke="#FFFFFF" stroke-width="${w * 0.003}"/>""", "Outlet / tap")
        columns += yy
        col++
    }
    // Column 2: irrigation zones by colour.
    val zones = ((plan?.paths?.map { it.zone } ?: emptyList()) + (plan?.irrigationZones ?: emptyList()))
        .map { it.trim() }.filter { it.isNotBlank() }.distinct().sorted()
    if (o.showIrrigation && zones.isNotEmpty()) {
        val x = col * colW; var yy = y
        heading("Irrigation zones", x, yy); yy += rowH
        zones.forEach { z ->
            sb.append("""<rect x="$x" y="${yy - fs * 0.6}" width="${w * 0.03}" height="${fs * 1.0}" rx="${fs * 0.2}" fill="${zoneColor(z)}"/>""")
            label(z, x + w * 0.045, yy + fs * 0.35); yy += rowH
        }
        columns += yy
        col++
    }
    // Column 3: plant categories (and sun zones when shown).
    val categories = plants.map { it.category.ifBlank { "Other" } }.distinct().sorted()
    if (categories.isNotEmpty()) {
        val x = col * colW; var yy = y
        heading(if (o.numbered) "Plants (numbered — see index)" else "Plants", x, yy); yy += rowH
        categories.forEach { c ->
            sb.append("""<circle cx="${x + w * 0.012}" cy="$yy" r="${fs * 0.55}" fill="${categoryColor(c)}" stroke="#FFFFFF" stroke-width="1"/>""")
            label(c, x + w * 0.045, yy + fs * 0.35); yy += rowH
        }
        if (o.showSunZones && plan != null && plan.sunZones.isNotEmpty()) {
            yy += rowH * 0.3
            heading("Sun zones", x, yy); yy += rowH
            plan.sunZones.map { it.category }.distinct().forEach { cat ->
                val (c, name) = sunZoneColors[cat] ?: ("#FFD54F" to cat)
                sb.append("""<rect x="$x" y="${yy - fs * 0.6}" width="${w * 0.03}" height="${fs * 1.0}" fill="$c" fill-opacity="0.5" stroke="$c"/>""")
                label(name, x + w * 0.045, yy + fs * 0.35); yy += rowH
            }
        }
        columns += yy
    }
    return sb.toString() to ((columns.maxOrNull() ?: y) + fs)
}

// ---- Output -------------------------------------------------------------------------------------

fun writeSvg(svg: String, out: File) = out.writeText(svg)

fun writePng(svg: String, out: File, widthPx: Float = 2400f) {
    FileOutputStream(out).use { os ->
        PNGTranscoder().apply { addTranscodingHint(PNGTranscoder.KEY_WIDTH, widthPx) }
            .transcode(TranscoderInput(StringReader(svg)), TranscoderOutput(os))
    }
}

/** PNG bytes for an in-app preview. */
fun renderPngBytes(svg: String, widthPx: Float): ByteArray = ByteArrayOutputStream().also { os ->
    PNGTranscoder().apply { addTranscodingHint(PNGTranscoder.KEY_WIDTH, widthPx) }
        .transcode(TranscoderInput(StringReader(svg)), TranscoderOutput(os))
}.toByteArray()

fun writePdf(xhtml: String, out: File) {
    FileOutputStream(out).use { os ->
        PdfRendererBuilder().useFastMode().useSVGDrawer(BatikSVGDrawer()).withHtmlContent(xhtml, null).toStream(os).run()
    }
}

private const val BRAND = "#3A5A40"
private const val BRAND_DARK = "#233821"

private fun page(title: String, body: String, landscape: Boolean = false) = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>${esc(title)}</title><style>
@page { size: A4${if (landscape) " landscape" else ""}; margin: 16mm 14mm 18mm 14mm;
  @bottom-left { content: "${esc(title)}"; font-family: Helvetica, Arial, sans-serif; font-size: 7.5pt; color: #7A7667; }
  @bottom-right { content: "Page " counter(page) " of " counter(pages); font-family: Helvetica, Arial, sans-serif; font-size: 7.5pt; color: #7A7667; } }
body { font-family: Helvetica, Arial, sans-serif; font-size: 9.5pt; color: #1B1C18; line-height: 1.35; }
.eyebrow { text-transform: uppercase; letter-spacing: 1.5pt; font-size: 7.5pt; color: $BRAND; font-weight: bold; }
h1 { font-size: 22pt; color: $BRAND_DARK; margin: 2pt 0 2pt 0; }
h2 { font-size: 12.5pt; color: $BRAND_DARK; margin: 16pt 0 6pt 0; padding-bottom: 3pt; border-bottom: 1.5pt solid $BRAND; }
h3 { font-size: 10.5pt; color: $BRAND_DARK; margin: 12pt 0 4pt 0; }
.sub { color: #4A4739; font-size: 9.5pt; }
.band { background: #F1EEE6; border-left: 4pt solid $BRAND; padding: 8pt 10pt; margin: 10pt 0; }
table { border-collapse: collapse; width: 100%; }
.tiles td { width: 25%; padding: 4pt; vertical-align: top; }
.tile { border: 0.75pt solid #CBC6B5; border-radius: 4pt; padding: 7pt 8pt; background: #FBFAF6; }
.tile .v { font-size: 17pt; font-weight: bold; color: $BRAND_DARK; }
.tile .l { font-size: 7.5pt; color: #4A4739; text-transform: uppercase; letter-spacing: 0.5pt; }
.tile .n { font-size: 7.5pt; color: #B23B3B; }
.grid th { text-align: left; font-size: 7.5pt; text-transform: uppercase; letter-spacing: 0.5pt; color: #4A4739; border-bottom: 1pt solid #7A7667; padding: 4pt 5pt; }
.grid td { border-bottom: 0.5pt solid #E2DDD0; padding: 4pt 5pt; vertical-align: top; }
.bar { background: $BRAND; height: 7pt; border-radius: 2pt; }
.muted { color: #7A7667; }
.sci { color: #7A7667; font-style: italic; font-size: 8pt; }
.box { display: inline-block; width: 9pt; height: 9pt; border: 1pt solid #4A4739; border-radius: 1.5pt; }
.badge { font-size: 7.5pt; font-weight: bold; padding: 1pt 4pt; border-radius: 2pt; color: #FFFFFF; }
.p-overdue { background: #B23B3B; } .p-today { background: #A8481F; } .p-week { background: #8A5A00; } .p-soon { background: #3A5A40; }
.zone { background: $BRAND; color: #FFFFFF; padding: 4pt 7pt; font-weight: bold; margin-top: 12pt; border-radius: 3pt 3pt 0 0; }
.notes { border-bottom: 0.5pt dotted #A8A392; height: 12pt; }
.break { page-break-before: always; }
.avoid { page-break-inside: avoid; }
</style></head><body>$body</body></html>"""

private fun header(eyebrow: String, input: ReportInput, extra: String = ""): String {
    val season = seasonFor(input.now, input.meta.southernHemisphere)
    val parts = listOfNotNull(dateFmt.format(Date(input.now)), season.name, input.meta.address.takeIf { it.isNotBlank() })
    return """<div class="eyebrow">${esc(eyebrow)}</div><h1>${esc(input.gardenName)}</h1>
<div class="sub">${parts.joinToString(" · ") { esc(it) }}</div>
<div class="band"><b>${esc(season.name)}</b> — ${esc(season.growingPeriod)}${if (extra.isNotBlank()) "<br/>$extra" else ""}</div>"""
}

private fun embedSvg(svg: String, widthCss: String) =
    """<div style="width: $widthCss;">${svg.replaceFirst("<svg ", "<svg style=\"width: 100%; height: auto;\" ")}</div>"""

/** The holistic garden report: at a glance, garden health, and the full garden map with its index. */
fun gardenReportXhtml(input: ReportInput): String {
    val plants = input.plants
    val now = input.now
    val tasks = careTasks(input, horizonDays = 7)
    fun countFor(action: String) = tasks.count { it.action == action }
    fun overdueFor(action: String) = tasks.count { it.action == action && it.priority == CarePriority.OVERDUE }
    val varieties = plants.map { it.sci.ifBlank { it.name }.trim().lowercase() }.filter { it.isNotBlank() }.distinct().size
    val zones = plants.map { it.location.trim() }.filter { it.isNotBlank() }.distinct()
    val irrigationZones = ((input.plan?.irrigationZones ?: emptyList()) + (input.plan?.paths?.map { it.zone } ?: emptyList()))
        .map { it.trim() }.filter { it.isNotBlank() }.distinct()
    val needsAttention = tasks.map { it.plant.id }.distinct().size

    fun tile(value: Any, label: String, note: String = "") =
        """<td><div class="tile"><div class="v">$value</div><div class="l">${esc(label)}</div>${if (note.isNotBlank()) "<div class=\"n\">${esc(note)}</div>" else ""}</div></td>"""
    fun dueTile(action: String, label: String): String {
        val od = overdueFor(action)
        return tile(countFor(action), label, if (od > 0) "$od overdue" else "")
    }

    val thumb = mapSvg(input, MapOptions(width = 600, numbered = false, showLegend = false))
    val glance = """<table><tr><td style="width: 62%; vertical-align: top; padding-right: 8pt;">
<table class="tiles"><tr>${tile(plants.size, "Plants")}${tile(plants.sumOf { max(it.qty, 1) }, "Individual plants")}</tr>
<tr>${tile(varieties, "Varieties")}${tile(zones.size, "Garden zones")}</tr>
<tr>${tile(irrigationZones.size, "Irrigation zones")}${tile(needsAttention, "Need care this week")}</tr></table>
</td><td style="vertical-align: top;">${embedSvg(thumb, "100%")}<div class="muted" style="font-size: 7.5pt; margin-top: 3pt;">Garden map — full page overleaf.</div></td></tr></table>"""

    val careDue = """<table class="tiles"><tr>${dueTile("Water", "To water")}${dueTile("Prune", "To prune")}${dueTile("Fertilise", "To fertilise")}${dueTile("Feed", "To feed")}</tr></table>
<div class="muted" style="font-size: 8pt;">Due within the next 7 days, including anything overdue. The plant care checklist has the details.</div>"""

    fun breakdown(title: String, groups: Map<String, Int>): String {
        if (groups.isEmpty()) return ""
        val top = groups.values.max().coerceAtLeast(1)
        val rows = groups.entries.sortedByDescending { it.value }.take(12).joinToString("") { (k, v) ->
            """<tr><td style="width: 38%;">${esc(k)}</td><td style="width: 50%;"><div class="bar" style="width: ${(v * 100 / top).coerceAtLeast(2)}%;"></div></td><td style="text-align: right;">$v</td></tr>"""
        }
        return """<div class="avoid"><h3>${esc(title)}</h3><table class="grid">$rows</table></div>"""
    }
    val issues = gardenCheckIssues(plants, now)
    val issuesHtml = if (issues.isEmpty()) """<p>No issues found — nice work.</p>""" else
        """<table class="grid"><tr><th>Check</th><th style="text-align: right;">Plants</th><th>What it means</th></tr>${
            issues.joinToString("") { """<tr><td><b>${esc(it.title)}</b></td><td style="text-align: right;">${it.count}</td><td class="muted">${esc(it.explanation)}</td></tr>""" }
        }</table>"""

    val fullMap = mapSvg(input, MapOptions(width = 1000, numbered = true, showLegend = true, showSunZones = false))
    val index = numberedPlants(plants).withIndex().joinToString("") { (i, p) ->
        val status = computeWateringStatus(p, now, input.meta.southernHemisphere)?.label ?: "—"
        """<tr><td><b>${i + 1}</b></td><td>${esc(p.name)}${if (p.sci.isNotBlank()) "<br/><span class=\"sci\">${esc(p.sci)}</span>" else ""}</td><td>${esc(p.location.ifBlank { "—" })}</td><td>${esc(p.category.ifBlank { "—" })}</td><td>${esc(p.water.ifBlank { "—" })}</td><td>${esc(status)}</td></tr>"""
    }
    val placedNote = plants.count { it.mapX == null && it.lat == null }.let { if (it > 0) """<p class="muted">$it plant(s) aren't placed on a map yet, so they only appear in the index.</p>""" else "" }

    val body = header("Garden report", input) +
        "<h2>At a glance</h2>$glance" +
        "<h2>Garden health</h2>$careDue" +
        breakdown("Plants by category", plants.groupingBy { it.category.ifBlank { "Uncategorised" } }.eachCount()) +
        breakdown("Plants by zone", plants.groupingBy { it.location.ifBlank { "No zone" } }.eachCount()) +
        """<div class="avoid"><h3>Garden check</h3>$issuesHtml</div>""" +
        """<div class="break"></div><div class="eyebrow">Garden map</div><h1>${esc(input.gardenName)}</h1>""" +
        embedSvg(fullMap, "100%") + placedNote +
        """<h2>Plant index</h2><table class="grid"><tr><th>No.</th><th>Plant</th><th>Zone</th><th>Category</th><th>Water need</th><th>Watering</th></tr>$index</table>"""
    return page("${input.gardenName} — Garden report", body)
}

/** The walk-around checklist: every care task for the next 2 weeks, grouped by zone, with tick boxes and space for notes. */
fun careReportXhtml(input: ReportInput, horizonDays: Int = 14): String {
    val tasks = careTasks(input, horizonDays)
    val end = input.now + horizonDays * DAY
    val summary = CarePriority.entries.joinToString(" &#160; ") { pr ->
        """<span class="badge ${pr.cssClass}">${esc(pr.label)}</span> ${tasks.count { it.priority == pr }}"""
    }
    val extra = "Covers ${esc(dateFmt.format(Date(input.now)))} – ${esc(dateFmt.format(Date(end)))}. &#160; $summary"
    val grouped = tasks.groupBy { it.plant.location.ifBlank { "No zone" } }
        .toSortedMap(compareBy<String> { if (it == "No zone") "￿" else it.lowercase() })
    val body = StringBuilder(header("Plant care checklist", input, extra))
    if (tasks.isEmpty()) body.append("<p>Nothing is due in the next $horizonDays days. Enjoy the garden!</p>")
    grouped.forEach { (zone, zoneTasks) ->
        body.append("""<div class="avoid"><div class="zone">${esc(zone)} — ${zoneTasks.size} task${if (zoneTasks.size == 1) "" else "s"}</div>""")
        body.append("""<table class="grid"><tr><th style="width: 4%;"></th><th style="width: 13%;">Priority</th><th style="width: 27%;">Plant</th><th style="width: 11%;">Action</th><th style="width: 13%;">Due</th><th style="width: 13%;">Last done</th><th>Notes</th></tr>""")
        zoneTasks.forEach { t ->
            body.append("""<tr><td><span class="box"></span></td><td><span class="badge ${t.priority.cssClass}">${esc(t.priority.label)}</span></td>
<td><b>${esc(t.plant.name)}</b>${if (t.plant.sci.isNotBlank()) "<br/><span class=\"sci\">${esc(t.plant.sci)}</span>" else ""}</td>
<td>${esc(t.action)}</td><td>${esc(t.due?.let { shortDateFmt.format(Date(it)) } ?: "Now")}</td>
<td class="muted">${esc(t.lastDone?.let { shortDateFmt.format(Date(it)) } ?: "Never")}</td><td><div class="notes"></div></td></tr>""")
        }
        body.append("</table></div>")
    }
    body.append("""<p class="muted" style="margin-top: 14pt;">Tick tasks off as you go, then log them in the Sage Garden app so your reminders stay accurate.</p>""")
    return page("${input.gardenName} — Plant care checklist", body.toString())
}

/** A one-page map export (landscape A4): title, map and legend. */
fun mapExportXhtml(input: ReportInput, options: MapOptions): String {
    val svg = mapSvg(input, options.copy(title = null))
    val body = """<div class="eyebrow">Garden map</div><h1>${esc(input.gardenName)}</h1><div class="sub">${esc(dateFmt.format(Date(input.now)))}</div>""" +
        embedSvg(svg, "100%")
    return page("${input.gardenName} — Garden map", body, landscape = true)
}
