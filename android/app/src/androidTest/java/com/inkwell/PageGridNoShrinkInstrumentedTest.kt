package com.inkwell

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkwell.data.CanvasEntity
import com.inkwell.data.CanvasRepository
import com.inkwell.data.InkDatabase
import com.inkwell.data.LibraryRepository
import com.inkwell.data.PageExtent
import com.inkwell.data.RoomPushedCanvasStore
import com.inkwell.data.pageExtent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 34 (ADR-0014: the page grid never shrinks — on ANY path). Against real Room (so the
 * SQL itself is under test, not a fake): every write path that touches an existing canvas
 * row keeps a grown grid.
 *
 *  - a re-delivered `canvas.formalize` job (it used to REPLACE the row with a one-page grid);
 *  - a re-materialised pushed canvas ([RoomPushedCanvasStore.insertCanvas]);
 *  - marking a canvas seen (it used to read the row and REPLACE it, which could write a stale
 *    grid back);
 *  - rename, move, move-to-another-space, trash / restore;
 *  - a direct [com.inkwell.data.dao.CanvasDao.updatePageExtent] with a smaller extent.
 */
@RunWith(AndroidJUnit4::class)
class PageGridNoShrinkInstrumentedTest {

    private lateinit var db: InkDatabase
    private lateinit var repo: CanvasRepository
    private lateinit var library: LibraryRepository

    private val grown = PageExtent(-1, 2, -1, 1)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, InkDatabase::class.java).build()
        repo = CanvasRepository(
            db.spaceDao(), db.canvasDao(), db.layerDao(), db.strokeDao(),
            runInTransaction = { block -> db.withTransaction { block() } },
        )
        library = LibraryRepository(db.folderDao(), db.canvasDao(), db.layerDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun extentOf(id: String): PageExtent = runBlocking { requireNotNull(db.canvasDao().byId(id)).pageExtent }

    @Test
    fun a_re_delivered_formalize_job_keeps_the_grown_grid() = runBlocking {
        repo.createFormalizedCanvas("srv-1", "space-1", "F", 2480, 3508, sourceCanvasId = null, jobId = "job-1")
        repo.updatePageExtent("srv-1", grown)

        repo.createFormalizedCanvas("srv-1", "space-1", "F", 2480, 3508, sourceCanvasId = null, jobId = "job-1")

        assertEquals(grown, extentOf("srv-1"))
        assertEquals("still one agent layer", 1, db.layerDao().forCanvas("srv-1").size)
    }

    @Test
    fun a_re_materialised_pushed_canvas_keeps_the_grown_grid() = runBlocking {
        val store = RoomPushedCanvasStore(db.canvasDao(), db.layerDao(), db.folderDao(), db.rasterDao())
        val pushed = CanvasEntity(
            id = "push-1", spaceId = "space-1", title = "Doc", createdAt = 1L, updatedAt = 1L, origin = "agent",
        )
        store.insertCanvas(pushed)
        repo.updatePageExtent("push-1", grown)

        store.insertCanvas(pushed) // a racing / repeated materialisation

        assertEquals(grown, extentOf("push-1"))
    }

    @Test
    fun library_writes_keep_the_grown_grid() = runBlocking {
        val spaceId = repo.ensureSeededSpaceId()
        val id = library.createCanvas(spaceId, folderId = null).id
        repo.updatePageExtent(id, grown)

        library.markSeen(id)
        assertNotNull("seen_at is stamped", db.canvasDao().byId(id)!!.seenAt)
        library.renameCanvas(id, "Renamed")
        library.moveCanvas(id, folderId = "folder-x")
        library.deleteCanvas(id)
        library.restoreCanvas(id)
        db.canvasDao().moveToSpace(id, "space-2", 5L)
        repo.renameCanvas(id, "Again")

        assertEquals(grown, extentOf(id))
    }

    @Test
    fun update_page_extent_never_shrinks_the_grid() = runBlocking {
        val spaceId = repo.ensureSeededSpaceId()
        val id = library.createCanvas(spaceId, folderId = null).id
        repo.updatePageExtent(id, grown)

        repo.updatePageExtent(id, PageExtent.SINGLE)
        db.canvasDao().updatePageExtent(id, 0, 1, 0, 0)

        assertEquals(grown, extentOf(id))
        // ...and it still grows.
        repo.updatePageExtent(id, PageExtent(-3, 0, 0, 2))
        assertEquals(PageExtent(-3, 2, -1, 2), extentOf(id))
    }
}
