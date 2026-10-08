package com.example.myapp.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarPartsNoteTest {

    private val content = """
        --- À acheter
        [ ] Piston V8 x8

        --- Stock
        Brake Disc V8 x4
        Camshaft V8 OHV x2
        Camshaft V8 OHV +3
        Head Gasket I4
    """.trimIndent()

    @Test
    fun parsesSectionsScoresAndQuantities() {
        val note = parseCarPartsNote(content)
        assertEquals(
            listOf(
                CarPart("Brake Disc V8", quantity = 4),
                CarPart("Camshaft V8 OHV", quantity = 2),
                CarPart("Camshaft V8 OHV", score = 3),
                CarPart("Head Gasket I4")
            ),
            note.stock
        )
        assertEquals(listOf(CarPart("Piston V8", quantity = 8)), note.tuning.toBuy)
        assertEquals(CarShopping(), note.repair)
        assertEquals(content, note.render())
    }

    @Test
    fun linesBeforeAnySeparatorAreStock() {
        val note = parseCarPartsNote("Alternator\nalternator x2")
        assertEquals(listOf(CarPart("Alternator", quantity = 3)), note.stock)
    }

    @Test
    fun inputSuffixesInAnyOrder() {
        assertEquals(CarPart("Camshaft V8", 3, 2), parseCarPartInput("Camshaft V8 x2 +3"))
        assertEquals(CarPart("Camshaft V8", 3, 2), parseCarPartInput("Camshaft V8 +3 x2"))
        assertEquals(CarPart("Camshaft V8"), parseCarPartInput("  Camshaft V8 "))
        assertNull(parseCarPartInput("+3"))
        assertEquals(CarPart("Bolt (12)", quantity = 2), parseCarPartInput("Bolt (12) x2"))
    }

    @Test
    fun repairModeLeavesRatedPartsAloneInItsOwnLists() {
        val note = parseCarPartsNote(content)
        val result = note.request("camshaft v8 ohv", 3, star = false)
        assertEquals(listOf(CarPart("Camshaft V8 OHV", quantity = 2)), result.taken)
        assertEquals(CarPart("Camshaft V8 OHV"), result.toBuy)
        assertEquals(listOf(CarPart("Camshaft V8 OHV", score = 3)), result.note.stockOf("Camshaft V8 OHV"))
        assertEquals(
            CarShopping(listOf(CarPart("Camshaft V8 OHV", quantity = 2)), listOf(CarPart("Camshaft V8 OHV"))),
            result.note.repair
        )
        assertEquals(note.tuning, result.note.tuning)
    }

    @Test
    fun bestRatedModeTakesTheBonusFirstThenUnrated() {
        val note = parseCarPartsNote(content)
        val result = note.request("Camshaft V8 OHV", 2, star = true)
        assertEquals(
            listOf(CarPart("Camshaft V8 OHV"), CarPart("Camshaft V8 OHV", score = 3)),
            result.taken
        )
        assertNull(result.toBuy)
        assertEquals(listOf(CarPart("Camshaft V8 OHV")), result.note.stockOf("Camshaft V8 OHV"))
    }

    @Test
    fun missingPartGoesToBuyList() {
        val result = CarPartsNote().request("Water Pump", 1, star = true)
        assertEquals(emptyList<CarPart>(), result.taken)
        assertEquals(listOf(CarPart("Water Pump")), result.note.tuning.toBuy)
        assertEquals("--- À acheter\n[ ] Water Pump\n\n--- Stock", result.note.render())
    }

    @Test
    fun finishShoppingDropsTakenAndBuyList() {
        val note = parseCarPartsNote(content).request("Head Gasket I4", 1, star = false).note
        val done = note.finishShopping(star = false)
        assertEquals(CarShopping(), done.repair)
        assertEquals(note.tuning, done.tuning)
        assertEquals(emptyList<CarPart>(), done.stockOf("Head Gasket I4"))
    }

    @Test
    fun suggestionsPreferPrefixThenFrequency() {
        val counts = mapOf("Camshaft V8" to 5, "Crankshaft V8" to 9, "Water Pump" to 1)
        assertEquals(
            listOf("Crankshaft V8", "Camshaft V8"),
            suggestCarPartNames("sha", counts, listOf("Brake Disc"))
        )
        assertEquals(
            listOf("Crankshaft V8", "Camshaft V8", "Water Pump", "Brake Disc"),
            suggestCarPartNames("", counts, listOf("Brake Disc", "brake disc"))
        )
        assertEquals(listOf("Camshaft V8"), suggestCarPartNames("cam", counts, emptyList()))
    }

    @Test
    fun trailingCommaBeforeTheBonusIsDropped() {
        val note = parseCarPartsNote("Bougie, +3\nBougie,\nbougie , x2")
        assertEquals(listOf(CarPart("Bougie", quantity = 3), CarPart("Bougie", score = 3)), note.stock)
        assertEquals("--- Stock\nBougie x3\nBougie +3", note.render())
        assertEquals(CarPart("Bougie", score = 3, quantity = 2), parseCarPartInput("Bougie, +3 x2"))
    }

    @Test
    fun bothJobsRenderOnTopAndHideEachOther() {
        val note = parseCarPartsNote(content).request("Head Gasket I4", 2, star = false).note
        val text = note.render()
        assertEquals(
            """
            --- À acheter (réparations)
            [ ] Head Gasket I4

            --- Pris du stock (réparations)
            Head Gasket I4

            --- À acheter
            [ ] Piston V8 x8

            --- Stock
            Brake Disc V8 x4
            Camshaft V8 OHV x2
            Camshaft V8 OHV +3
            """.trimIndent(),
            text
        )
        assertEquals(note, parseCarPartsNote(text))
        assertEquals(setOf(0, 1, 2, 3, 4, 5), carHiddenLines(text, star = true))
        assertEquals(setOf(6, 7, 8, 12), carHiddenLines(text, star = false))
        assertEquals(listOf(CarPart("Camshaft V8 OHV", quantity = 2)), note.stockOf("Camshaft V8 OHV", star = false))
    }

    @Test
    fun suggestionsForgiveATypo() {
        val counts = mapOf("Camshaft V8" to 5, "Crankshaft V8" to 9, "Water Pump" to 1)
        assertEquals(listOf("Camshaft V8"), suggestCarPartNames("camshft", counts, emptyList()))
        assertEquals(listOf("Water Pump"), suggestCarPartNames("watr pump", counts, emptyList()))
        assertEquals(emptyList<String>(), suggestCarPartNames("cmx", counts, emptyList()))
    }
}
