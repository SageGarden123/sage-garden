import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Generates every report and export for a realistic sample garden into build/report-samples/, and
 * renders each PDF page to PNG so the layout can be eyeballed without a PDF viewer.
 */
class ReportsTest {
    private val out = File("build/report-samples").apply { mkdirs() }
    private val day = 86_400_000L
    private val now = System.currentTimeMillis()

    private fun sampleImage(): File {
        val img = BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0xE8F0DC); g.fillRect(0, 0, 1200, 800)
        g.color = Color(0xC9B99A); g.fillRect(420, 0, 90, 800)          // path
        g.color = Color(0xB7D3A8); g.fillRoundRect(60, 60, 320, 300, 30, 30); g.fillRoundRect(560, 80, 560, 260, 30, 30)
        g.fillRoundRect(60, 440, 320, 300, 30, 30); g.fillRoundRect(560, 440, 560, 300, 30, 30)
        g.color = Color(0x8C7B5E); g.stroke = BasicStroke(6f); g.drawRect(10, 10, 1180, 780)
        g.dispose()
        return File(out, "plan.jpg").also { ImageIO.write(img, "jpg", it) }
    }

    private fun input(): ReportInput {
        fun p(id: String, name: String, sci: String, zone: String, cat: String, x: Double, y: Double, lastWatered: Long?, freq: Int?, prune: Int? = null, lastPruned: Long? = null) =
            Plant(id = id, name = name, sci = sci, location = zone, category = cat, water = listOf("Low", "Moderate", "High").random(),
                mapX = x, mapY = y, lastWateredDate = lastWatered, wateringFrequencyDays = freq,
                pruneFrequencyDays = prune, lastPrunedDate = lastPruned, fertiliseFrequencyDays = 60, lastFertilisedDate = now - 58 * day)
        val plants = listOf(
            p("P1", "Rosemary", "Salvia rosmarinus", "Front bed", "Herbs", 0.12, 0.18, now - 9 * day, 7),
            p("P2", "Lavender", "Lavandula angustifolia", "Front bed", "Shrubs", 0.22, 0.30, now - 2 * day, 5, 90, now - 100 * day),
            p("P3", "Lemon tree", "Citrus limon", "Back lawn", "Trees", 0.70, 0.30, now - 6 * day, 7),
            p("P4", "Tomato", "Solanum lycopersicum", "Veggie patch", "Annuals", 0.62, 0.70, now - 1 * day, 2),
            p("P5", "Basil", "Ocimum basilicum", "Veggie patch", "Herbs", 0.80, 0.72, null, 2),
            p("P6", "Kangaroo paw", "Anigozanthos", "Side garden", "Perennials", 0.15, 0.75, now - 3 * day, 10),
            p("P7", "Agave", "Agave attenuata", "Side garden", "Succulents", 0.25, 0.62, now - 20 * day, 21),
            p("P8", "Jasmine", "Trachelospermum jasminoides", "Back lawn", "Climbers/Vines", 0.92, 0.18, now - 4 * day, 7, 180, now - 175 * day),
        )
        val plan = GardenPlan(
            paths = listOf(
                PlanPath("a", "Front garden", 0.35, 0.05, listOf(
                    PlanSegment("main", listOf(0.35 to 0.05, 0.35 to 0.40, 0.08 to 0.40), null),
                    PlanSegment("drip", listOf(0.08 to 0.40, 0.08 to 0.12, 0.28 to 0.12), null))),
                PlanPath("b", "Back lawn", 0.40, 0.95, listOf(
                    PlanSegment("main", listOf(0.40 to 0.95, 0.60 to 0.55, 0.95 to 0.55), null),
                    PlanSegment("sprinkler", listOf(0.72 to 0.25), 0.10),
                    PlanSegment("impact_sprinkler", listOf(0.55 to 0.20, 0.90 to 0.40), null))),
                PlanPath("c", "Veggie patch", 0.50, 0.95, listOf(PlanSegment("drip", listOf(0.50 to 0.95, 0.55 to 0.78, 0.92 to 0.78), null))),
            ),
            sunZones = listOf(PlanSunZone("full_sun", "custom", listOf(0.47 to 0.55, 0.97 to 0.55, 0.97 to 0.95, 0.47 to 0.95))),
            irrigationZones = listOf("Front garden", "Back lawn", "Veggie patch"),
            imageHash = "sample", imageWidth = 1200, imageHeight = 800,
        )
        return ReportInput("Dan's Garden", GardenMeta("12 Example St, Sydney NSW", -33.87, 151.2, listOf("Front bed", "Back lawn", "Veggie patch", "Side garden")), plants, plan, sampleImage(), now)
    }

    private fun renderPages(pdf: File) {
        Loader.loadPDF(pdf).use { doc ->
            val r = PDFRenderer(doc)
            for (i in 0 until doc.numberOfPages) ImageIO.write(r.renderImageWithDPI(i, 70f), "png", File(out, "${pdf.nameWithoutExtension}-page${i + 1}.png"))
        }
    }

    @Test fun generatesAllOutputs() {
        val input = input()
        val garden = File(out, "garden-report.pdf").also { writePdf(gardenReportXhtml(input), it) }
        val care = File(out, "care-checklist.pdf").also { writePdf(careReportXhtml(input), it) }
        val map = File(out, "map.pdf").also { writePdf(mapExportXhtml(input, MapOptions(width = 1400, showSunZones = true)), it) }
        writeSvg(mapSvg(input, MapOptions(width = 1400)), File(out, "map.svg"))
        writePng(mapSvg(input, MapOptions(width = 1400)), File(out, "map.png"), 1400f)
        listOf(garden, care, map).forEach { assertTrue(it.length() > 5_000, "${it.name} looks empty"); renderPages(it) }

        // No uploaded map: plants drawn to scale from their coordinates instead.
        val noPlan = input.copy(plan = null, planImage = null, plants = input.plants.mapIndexed { i, p -> p.copy(mapX = null, mapY = null, lat = -33.87 + i * 0.00003, lng = 151.2 + (i % 3) * 0.00004) })
        File(out, "garden-report-noplan.pdf").also { writePdf(gardenReportXhtml(noPlan), it); renderPages(it) }
    }

    private fun fakeSatellite(view: SatelliteView): SatelliteImage {
        val img = BufferedImage(view.width * view.scale, view.height * view.scale, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(0x4E6B3A); g.fillRect(0, 0, img.width, img.height)
        g.color = Color(0x6F8F55); for (i in 0 until img.width step 80) g.fillRect(i, 0, 40, img.height)
        g.color = Color.WHITE; g.drawString("Google", 10, img.height - 10)
        g.dispose()
        val out = java.io.ByteArrayOutputStream(); ImageIO.write(img, "jpg", out)
        return SatelliteImage(view, out.toByteArray())
    }

    @Test fun satelliteMapsPlaceMarkersWhereGoogleWould() {
        val base = input()
        val placed = base.plants.mapIndexed { i, p -> p.copy(lat = -33.87 + (i % 4) * 0.00008, lng = 151.2 + (i / 4) * 0.00012) }
        val noSat = base.copy(plants = placed)
        val view = satelliteViewFor(noSat)!!
        val (cx, cy) = satelliteFraction(view, view.lat, view.lng)
        assertTrue(kotlin.math.abs(cx - 0.5) < 1e-9 && kotlin.math.abs(cy - 0.5) < 1e-9, "view centre maps to image centre")
        placed.forEach { p ->
            val (fx, fy) = satelliteFraction(view, p.lat!!, p.lng!!)
            assertTrue(fx in 0.05..0.95 && fy in 0.05..0.95, "${p.name} fits inside the view with a margin")
        }
        val withSat = noSat.copy(satellite = fakeSatellite(view))
        val both = listOf(MapKind.PLAN, MapKind.SATELLITE)
        File(out, "garden-report-both.pdf").also { writePdf(gardenReportXhtml(withSat, both), it); renderPages(it) }
        File(out, "map-both.pdf").also { writePdf(mapExportXhtml(withSat, MapOptions(width = 1400), both), it); renderPages(it) }
    }

    @Test fun seasonsFollowTheHemisphere() {
        val oct = java.util.Calendar.getInstance().apply { set(2026, java.util.Calendar.OCTOBER, 5) }.timeInMillis
        assertTrue(seasonFor(oct, southern = true).name.endsWith("spring"))
        assertTrue(seasonFor(oct, southern = false).name.endsWith("autumn"))
    }
}
