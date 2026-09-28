package com.example.sagegarden

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvAndIdsTest {
    @Test fun `quoted fields keep commas and escaped quotes`() {
        assertEquals(listOf("P0001", "Rosemary, trailing", "Says \"hi\"", ""), parseCsvLine("P0001,\"Rosemary, trailing\",\"Says \"\"hi\"\"\","))
    }

    @Test fun `tab-separated files are detected`() {
        assertEquals('\t', detectCsvDelimiter("Plant\tLocation\tSun"))
        assertEquals(',', detectCsvDelimiter("Plant,Location,Sun"))
    }

    @Test fun `next plant id continues from the highest existing id for that prefix`() {
        assertEquals("P0001", nextPlantId(emptyList()))
        assertEquals("P0013", nextPlantId(listOf("P0002", "P0012", "Q0099", "custom")))
        assertEquals("Q0100", nextPlantId(listOf("P0002", "Q0099"), prefix = "Q"))
    }

    @Test fun `irrigation csv accepts epoch millis and date-times`() {
        val csv = "Zone,StartTime,DurationMinutes,Outlet,Source\n" +
            "Front,1700000000000,15,1,Tuya\n" +
            "Back,2024-01-31 06:30:00,10,2,Manual\n"
        val result = parseIrrigationCsv(csv)
        assertTrue(result is CsvImportResult.Success)
        val events = (result as CsvImportResult.Success).items
        assertEquals(2, events.size)
        assertEquals(1700000000000L, events.first { it.zone == "Front" }.startTime)
        assertEquals(10, events.first { it.zone == "Back" }.durationMinutes)
    }

    @Test fun `irrigation csv reports missing columns`() {
        val result = parseIrrigationCsv("Zone,Minutes\nFront,15\n")
        assertTrue(result is CsvImportResult.MissingColumns)
        assertTrue((result as CsvImportResult.MissingColumns).missing.containsAll(listOf("StartTime", "DurationMinutes")))
    }
}
