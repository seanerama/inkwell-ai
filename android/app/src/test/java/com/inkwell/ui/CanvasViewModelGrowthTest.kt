package com.inkwell.ui

import com.inkwell.data.CanvasEntity
import com.inkwell.data.CanvasRepository
import com.inkwell.data.LayerEntity
import com.inkwell.data.PackedPoints
import com.inkwell.data.PageExtent
import com.inkwell.data.SpaceEntity
import com.inkwell.data.StrokeEntity
import com.inkwell.data.dao.CanvasDao
import com.inkwell.data.dao.LayerDao
import com.inkwell.data.dao.SpaceDao
import com.inkwell.data.dao.StrokeDao
import com.inkwell.data.pageExtent
import com.inkwell.ink.BuiltStroke
import com.inkwell.ink.StrokeCommit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Stage 34 (ADR-0014 §2): [CanvasViewModel]'s commit path with the expandable-canvas switch
 * on and off, over the real [CanvasRepository] and in-memory fake DAOs. The default canvas
 * is A4 (2480 × 3508 CU pages). The render list itself needs `android.graphics.Color`, so it
 * is not asserted here; undo / erase keeping the grid is `ExpandableCanvasInstrumentedTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CanvasViewModelGrowthTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class FakeSpaceDao : SpaceDao {
        val store = mutableListOf<SpaceEntity>()
        override suspend fun upsert(space: SpaceEntity) { store.removeAll { it.id == space.id }; store.add(space) }
        override suspend fun all() = store.sortedBy { it.position }
        override suspend fun byId(id: String) = store.firstOrNull { it.id == id }
        override suspend fun delete(id: String) { store.removeAll { it.id == id } }
    }

    private class FakeCanvasDao : CanvasDao {
        val store = mutableListOf<CanvasEntity>()
        override suspend fun upsert(canvas: CanvasEntity) { store.removeAll { it.id == canvas.id }; store.add(canvas) }
        override suspend fun insertIfAbsent(canvas: CanvasEntity): Long {
            if (store.any { it.id == canvas.id }) return -1L
            store.add(canvas); return 1L
        }
        override suspend fun forSpace(spaceId: String) = store.filter { it.spaceId == spaceId }
        override suspend fun byId(id: String) = store.firstOrNull { it.id == id }
        override suspend fun inFolder(spaceId: String, folderId: String?) = store.filter { it.spaceId == spaceId }
        override suspend fun trashed(spaceId: String) = emptyList<CanvasEntity>()
        override suspend fun allForSpace(spaceId: String) = store.filter { it.spaceId == spaceId }
        override suspend fun rename(id: String, title: String, updatedAt: Long) {}
        override suspend fun move(id: String, folderId: String?, updatedAt: Long) {}
        override suspend fun moveToSpace(id: String, spaceId: String, updatedAt: Long) {}
        override suspend fun setSpace(id: String, spaceId: String, updatedAt: Long) {}
        override suspend fun reassignSpace(oldSpaceId: String, newSpaceId: String) {}
        override suspend fun setDeletedAt(id: String, deletedAt: Long?) {}
        override suspend fun markSeen(id: String, seenAt: Long) {}
        override suspend fun trashCanvasesInFolder(spaceId: String, folderId: String, deletedAt: Long) {}
        override suspend fun purgeTrashedBefore(cutoff: Long) {}
        override suspend fun hardDelete(id: String) { store.removeAll { it.id == id } }
        // Mirrors the monotonic SQL (bounds only move outward).
        override suspend fun updatePageExtent(id: String, minCol: Int, maxCol: Int, minRow: Int, maxRow: Int) {
            store.replaceAll {
                if (it.id != id) {
                    it
                } else {
                    it.copy(
                        pageMinCol = minOf(it.pageMinCol, minCol), pageMaxCol = maxOf(it.pageMaxCol, maxCol),
                        pageMinRow = minOf(it.pageMinRow, minRow), pageMaxRow = maxOf(it.pageMaxRow, maxRow),
                    )
                }
            }
        }
    }

    private class FakeLayerDao : LayerDao {
        val store = mutableListOf<LayerEntity>()
        override suspend fun upsert(layer: LayerEntity) { store.removeAll { it.id == layer.id }; store.add(layer) }
        override suspend fun forCanvas(canvasId: String) = store.filter { it.canvasId == canvasId }.sortedBy { it.z }
    }

    private class FakeStrokeDao : StrokeDao {
        val store = mutableListOf<StrokeEntity>()
        override suspend fun insert(stroke: StrokeEntity) { store.removeAll { it.id == stroke.id }; store.add(stroke) }
        override suspend fun forLayer(layerId: String) = store.filter { it.layerId == layerId }
        override suspend fun byId(id: String) = store.firstOrNull { it.id == id }
        override suspend fun deleteById(id: String) { store.removeAll { it.id == id } }
        override suspend fun count() = store.size
    }

    private val canvasDao = FakeCanvasDao()
    private val strokeDao = FakeStrokeDao()

    private fun viewModel(expandable: Boolean): CanvasViewModel {
        var n = 0
        val repo = CanvasRepository(
            FakeSpaceDao(), canvasDao, FakeLayerDao(), strokeDao,
            idGen = { "id-${n++}" }, clock = { 1L },
        )
        val vm = CanvasViewModel(repository = repo, expandableCanvas = expandable)
        assertTrue(vm.ready)
        return vm
    }

    /** A pen stroke through the given canvas-unit points. */
    private fun commit(vararg xy: Pair<Float, Float>): StrokeCommit {
        val pts = FloatArray(xy.size * PackedPoints.STRIDE)
        xy.forEachIndexed { i, (x, y) ->
            val o = i * PackedPoints.STRIDE
            pts[o] = x; pts[o + 1] = y; pts[o + 2] = 0.5f; pts[o + 4] = i * 4f
        }
        val minX = xy.minOf { it.first }; val maxX = xy.maxOf { it.first }
        val minY = xy.minOf { it.second }; val maxY = xy.maxOf { it.second }
        return StrokeCommit(BuiltStroke(pts, xy.size, minX, minY, maxX - minX, maxY - minY), "pen", "#111111", 3f)
    }

    private val storedExtent: PageExtent get() = canvasDao.store.single().pageExtent

    @Test
    fun a_stroke_into_the_left_ring_grows_the_grid_and_stores_every_point() {
        val vm = viewModel(expandable = true)
        val c = commit(100f to 100f, -200f to 150f, -900f to 300f)

        vm.onStrokeCommitted(c)

        assertEquals(PageExtent(-1, 0, 0, 0), vm.pageExtent)
        assertEquals(PageExtent(-1, 0, 0, 0), storedExtent)
        val stored = strokeDao.store.single()
        assertEquals(3, stored.pointCount)
        assertArrayEquals(c.stroke.points, PackedPoints.decode(stored.points, 3), 0f)
        assertNull(vm.canvasNotice)
    }

    @Test
    fun switch_off_never_grows_the_grid_but_still_stores_the_stroke() {
        val vm = viewModel(expandable = false)
        vm.onStrokeCommitted(commit(100f to 100f, -900f to 300f))

        assertEquals(PageExtent.SINGLE, vm.pageExtent)
        assertEquals(PageExtent.SINGLE, storedExtent)
        assertEquals(2, strokeDao.store.single().pointCount)
    }

    @Test
    fun a_stroke_past_the_cap_commits_whole_keeps_eight_pages_and_shows_the_notice() {
        val vm = viewModel(expandable = true)
        // Reach 8 columns (pages 0..7), then write past the right edge of page 7.
        vm.onStrokeCommitted(commit(100f to 100f, 7.5f * 2480f to 100f))
        assertEquals(PageExtent(0, 7, 0, 0), vm.pageExtent)
        vm.consumeCanvasNotice()

        val c = commit(7.5f * 2480f to 200f, 9.5f * 2480f to 200f)
        vm.onStrokeCommitted(c)

        assertEquals(PageExtent(0, 7, 0, 0), vm.pageExtent)
        assertEquals(PageExtent(0, 7, 0, 0), storedExtent)
        assertEquals(CanvasViewModel.CAP_NOTICE, vm.canvasNotice)
        val stored = strokeDao.store.last()
        assertArrayEquals("all real points are stored", c.stroke.points, PackedPoints.decode(stored.points, 2), 0f)
        vm.consumeCanvasNotice()
        assertNull(vm.canvasNotice)
    }

    // --- Stage 36: fit on open and on Fit, never on growth ---

    @Test
    fun a_fit_is_requested_on_open_and_on_fit_but_never_by_growth() {
        val vm = viewModel(expandable = true)
        val opened = vm.fitRequest
        assertTrue("opening the canvas requests a fit", opened > 0)

        // Growth (a stroke into the ring) never re-fits.
        vm.onStrokeCommitted(commit(100f to 100f, 2600f to 150f))
        assertEquals(PageExtent(0, 1, 0, 0), vm.pageExtent)
        assertEquals("growth does not request a fit", opened, vm.fitRequest)

        // A viewport report (pan/zoom, resize, rotation) never re-fits either.
        vm.onViewportChanged(-100.0, -100.0, 900.0, 900.0)
        assertEquals(opened, vm.fitRequest)

        // The Fit control does.
        vm.fitToScreen()
        assertEquals(opened + 1, vm.fitRequest)

        // Re-opening the canvas already loaded opens it fitted too, with the whole grid as the
        // region until the view reports its fitted viewport.
        vm.openCanvas(requireNotNull(vm.currentCanvasId))
        assertEquals(opened + 2, vm.fitRequest)
        assertEquals(
            com.inkwell.render.CoordinateMapping.Region(0, 0, 2 * 2480, 3508),
            vm.currentExportRegion(),
        )
        assertNull(vm.sendBlockedHint)
    }

    @Test
    fun a_recreated_view_keeps_the_users_view_until_the_next_fit_request() {
        val vm = viewModel(expandable = true)
        assertNull("nothing reported yet: the new view fits", vm.restorableViewTransform())

        vm.onViewTransformChanged(0.5f, 10f, 20f, vm.fitRequest)
        assertArrayEquals(floatArrayOf(0.5f, 10f, 20f), vm.restorableViewTransform(), 0f)

        // Growth keeps it.
        vm.onStrokeCommitted(commit(100f to 100f, -900f to 300f))
        assertArrayEquals(floatArrayOf(0.5f, 10f, 20f), vm.restorableViewTransform(), 0f)

        // A newer fit request wins: the view must fit, not restore.
        vm.fitToScreen()
        assertNull(vm.restorableViewTransform())
        // A report from a view that had not taken that request yet is stale.
        vm.onViewTransformChanged(1f, 0f, 0f, vm.fitRequest - 1)
        assertNull(vm.restorableViewTransform())
    }
}
