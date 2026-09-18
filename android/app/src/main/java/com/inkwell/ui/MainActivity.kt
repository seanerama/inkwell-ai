package com.inkwell.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.inkwell.BuildConfig
import com.inkwell.data.CanvasRepository
import com.inkwell.data.InkDatabase
import com.inkwell.data.LayerRepository
import com.inkwell.data.LibraryRepository
import com.inkwell.data.RoomCardStateRepository
import com.inkwell.data.SpaceSync
import com.inkwell.data.ThumbnailRenderer
import com.inkwell.net.AndroidConnectivity
import com.inkwell.net.EncryptedTokenStore
import com.inkwell.net.LoopServices
import com.inkwell.net.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Single-Activity host. Launch screen depends on the kill-switches:
 *
 *  - [BuildConfig.LIBRARY] (Stage 11, default ON): ON → the app opens on [LibraryScreen]
 *    (folders + canvases for the seeded space); tapping a canvas tile opens
 *    [CanvasScreen] for that id, and Back returns to the folder. OFF → today's behaviour:
 *    open the first (default) canvas directly.
 *  - [BuildConfig.INK_ENABLED] (default ON): OFF → the settings/pairing screen is the app.
 *
 * Pairing is always reachable from the canvas's / library's Settings entry.
 */
class MainActivity : ComponentActivity() {

    // Shared across both ViewModels so there is exactly one Room instance / repository.
    private val db by lazy { InkDatabase.create(applicationContext) }
    private val canvasRepository by lazy {
        CanvasRepository(
            spaceDao = db.spaceDao(),
            canvasDao = db.canvasDao(),
            layerDao = db.layerDao(),
            strokeDao = db.strokeDao(),
        )
    }
    private val thumbnailRenderer by lazy { ThumbnailRenderer(applicationContext.filesDir) }

    // Stage 14: mirrors the server's spaces + reconciles the local placeholder (ADR-0010).
    // Reconciliation runs through Room's withTransaction so ink can never be stranded. The
    // device repository is built fresh from the current pairing (null when unpaired).
    private val spaceSync by lazy {
        val tokenStore = EncryptedTokenStore(applicationContext)
        SpaceSync.create(
            db = db,
            deviceRepositoryProvider = { LoopServices.repositoryFrom(tokenStore) },
        )
    }

    private val pairingViewModel: PairingViewModel by viewModels {
        val tokenStore = EncryptedTokenStore(applicationContext)
        viewModelFactory { initializer { PairingViewModel(tokenStore) } }
    }

    private val canvasViewModel: CanvasViewModel by viewModels {
        val layerRepo = LayerRepository(db.layerDao())
        val cardStateRepo = RoomCardStateRepository(db.cardStateDao())
        val tokenStore = EncryptedTokenStore(applicationContext)
        val connectivity = AndroidConnectivity(applicationContext)
        // Plain prefs for the Stage-10 job-type picker's last choice (not a secret).
        val prefs = applicationContext.getSharedPreferences("inkwell_prefs", Context.MODE_PRIVATE)
        viewModelFactory {
            initializer {
                CanvasViewModel(
                    repository = canvasRepository,
                    layerRepository = layerRepo,
                    // Built fresh so it always uses the current pairing (or null if unpaired).
                    deviceRepositoryProvider = { LoopServices.repositoryFrom(tokenStore) },
                    connectivity = connectivity,
                    offlineQueue = LoopServices.offlineQueue,
                    cardStatePersistence = cardStateRepo,
                    // Stage 12: the same repository materialises a Formalize redraw as a
                    // local canvas (using the server's id) when a formalize job returns.
                    formalizedCanvasStore = canvasRepository,
                    loadJobType = { prefs.getString("job_type", "ask") ?: "ask" },
                    saveJobType = { prefs.edit().putString("job_type", it).apply() },
                    // With the Library on, the canvas is opened per-tile via openCanvas().
                    autoOpenDefault = !BuildConfig.LIBRARY,
                    // Stage 14: post with the canvas's own space id; reconcile on send.
                    spaceSync = spaceSync,
                    // Stage 22: load a pushed canvas's raster (PDF page / image) for on-screen
                    // rendering beneath ink and for the composited export. Null when the flag is
                    // OFF so the raster code stays inert.
                    pushedRasterSource = if (BuildConfig.PUSH_INBOX) {
                        com.inkwell.render.PushedRasterSource(db.layerDao(), db.rasterDao())
                    } else {
                        null
                    },
                )
            }
        }
    }

    // Stage 15: on-device space editing (rename / colour / model / prompt) and new spaces.
    // Uses the current pairing for the network calls, mirrors the returned row into Room, then
    // asks the Library ViewModel to re-load its tabs (selecting the created space).
    private val spaceSettingsViewModel: SpaceSettingsViewModel by viewModels {
        val tokenStore = EncryptedTokenStore(applicationContext)
        viewModelFactory {
            initializer {
                SpaceSettingsViewModel(
                    deviceRepositoryProvider = { LoopServices.repositoryFrom(tokenStore) },
                    spaceSync = spaceSync,
                    upsertSpace = { db.spaceDao().upsert(it) },
                    loadSpaces = { canvasRepository.allSpaces() },
                    onSpacesChanged = { selectId -> libraryViewModel.reloadSpacesSelecting(selectId) },
                )
            }
        }
    }

    private val libraryViewModel: LibraryViewModel by viewModels {
        val library = LibraryRepository(
            folderDao = db.folderDao(),
            canvasDao = db.canvasDao(),
            layerDao = db.layerDao(),
            // Stage 14: move-between-spaces runs atomically so a partial move never strands ink.
            runInTransaction = { block -> db.withTransaction { block() } },
        )
        // Stage 14: the active-space tab is remembered in the same plain prefs as the job type.
        val prefs = applicationContext.getSharedPreferences("inkwell_prefs", Context.MODE_PRIVATE)
        val tokenStore = EncryptedTokenStore(applicationContext)
        // Stage 22: the push inbox materialises host-pushed `to_user` jobs discovered via the
        // persisted `/sync` cursor. Wired only when the kill-switch is ON.
        val pushInbox = if (BuildConfig.PUSH_INBOX) {
            com.inkwell.net.PushInbox(
                store = com.inkwell.data.RoomPushedCanvasStore(
                    canvasDao = db.canvasDao(),
                    layerDao = db.layerDao(),
                    folderDao = db.folderDao(),
                    rasterDao = db.rasterDao(),
                ),
                downloader = com.inkwell.net.CachingBlobDownloader(
                    repoProvider = { LoopServices.repositoryFrom(tokenStore) },
                    cacheDir = applicationContext.cacheDir,
                ),
            )
        } else {
            null
        }
        viewModelFactory {
            initializer {
                LibraryViewModel(
                    library = library,
                    canvasRepository = canvasRepository,
                    spaceSync = spaceSync,
                    loadActiveSpaceId = { prefs.getString("active_space_id", null) },
                    saveActiveSpaceId = { prefs.edit().putString("active_space_id", it).apply() },
                    // Stage 22: push-inbox discovery collaborators (null when the flag is OFF).
                    pushInbox = pushInbox,
                    syncCursorStore = if (BuildConfig.PUSH_INBOX) {
                        com.inkwell.net.PrefsSyncCursorStore(applicationContext)
                    } else {
                        null
                    },
                    deviceRepositoryProvider = { LoopServices.repositoryFrom(tokenStore) },
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Register the durable background poll/flush lane (WorkManager) only when the
        // Send feature is enabled (release stays dark until the follow-up PR flips it).
        if (BuildConfig.SEND_ENABLED) {
            SyncWorker.schedulePeriodic(applicationContext)
        }
        // Stage 14 trigger: prime the canvas ViewModel's server-space mirror on app start
        // (no-op when unpaired/offline — the mirror is refreshed again at send time).
        if (BuildConfig.SPACES) {
            canvasViewModel.refreshSpaces()
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // Screen routing without a nav library.
                    var showSettings by remember { mutableStateOf(!BuildConfig.INK_ENABLED) }

                    when {
                        showSettings || !BuildConfig.INK_ENABLED -> {
                            SettingsScaffold(
                                canReturnToCanvas = BuildConfig.INK_ENABLED,
                                onBack = { showSettings = false },
                            ) {
                                PairingScreen(viewModel = pairingViewModel)
                            }
                        }
                        BuildConfig.LIBRARY -> {
                            LibraryRoute(
                                onOpenSettings = { showSettings = true },
                            )
                        }
                        else -> {
                            // Flag OFF: today's direct-to-canvas flow.
                            CanvasScreen(
                                viewModel = canvasViewModel,
                                onOpenSettings = { showSettings = true },
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * The Library launch flow: the grid, or the opened canvas. Opening a tile loads it into
     * the shared [canvasViewModel]; Back renders a fresh thumbnail (best-effort) and returns
     * to the folder.
     */
    @androidx.compose.runtime.Composable
    private fun LibraryRoute(onOpenSettings: () -> Unit) {
        var selectedCanvasId by remember { mutableStateOf<String?>(null) }

        val openId = selectedCanvasId
        if (openId == null) {
            LibraryScreen(
                viewModel = libraryViewModel,
                onOpenCanvas = { selectedCanvasId = it },
                settingsViewModel = spaceSettingsViewModel,
                loadThumbnail = { canvas ->
                    val f = thumbnailRenderer.file(canvas.id)
                    if (f.exists()) {
                        BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap()
                    } else {
                        null
                    }
                },
            )
        } else {
            LaunchedEffect(openId) { canvasViewModel.openCanvas(openId) }
            // Stage 12: a Formalize redraw asks to open its new agent-origin canvas beside
            // the source. Follow the request by switching the selected tile to the new id.
            val pendingOpen = canvasViewModel.pendingOpenCanvasId
            LaunchedEffect(pendingOpen) {
                if (pendingOpen != null && pendingOpen != openId) {
                    selectedCanvasId = pendingOpen
                    canvasViewModel.consumePendingOpenCanvas()
                }
            }
            CanvasScreen(
                viewModel = canvasViewModel,
                onOpenSettings = onOpenSettings,
                onBack = {
                    renderThumbnailAsync(openId)
                    selectedCanvasId = null
                },
            )
        }
    }

    /** Render a 256-px thumbnail for the canvas just closed (off the main thread). */
    private fun renderThumbnailAsync(canvasId: String) {
        val width = canvasViewModel.canvasWidth
        val height = canvasViewModel.canvasHeight
        val strokes = canvasViewModel.strokes.toList()
        lifecycleScope.launch(Dispatchers.Default) {
            runCatching {
                thumbnailRenderer.ensure(
                    canvasId = canvasId,
                    updatedAt = System.currentTimeMillis(),
                    widthCu = width,
                    heightCu = height,
                    strokes = strokes,
                )
            }
        }
    }
}

/**
 * Wraps the pairing screen with a back affordance when it is reached as "settings"
 * from the canvas/library. When the ink kill-switch is OFF the pairing screen is the
 * whole app, so no back is offered.
 */
@androidx.compose.runtime.Composable
private fun SettingsScaffold(
    canReturnToCanvas: Boolean,
    onBack: () -> Unit,
    content: @androidx.compose.runtime.Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (canReturnToCanvas) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.padding(8.dp),
            ) { Text("< Back") }
        }
        content()
    }
}
