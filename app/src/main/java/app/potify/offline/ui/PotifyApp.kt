package app.potify.offline.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.potify.offline.AppViewModel
import app.potify.offline.Library
import app.potify.offline.LinkResult
import app.potify.offline.PlayerConnection
import kotlinx.coroutines.launch

private const val LIBRARY = "library"
private const val SEARCH = "search"
private const val LIKED = "liked"
private const val SETTINGS = "settings"
private const val PLAYLIST = "playlist:"
private const val REVIEW = "review"
private const val DUPLICATES = "duplicates"
private const val MERGE = "merge"

private val StackSaver = listSaver<SnapshotStateList<String>, String>(
    save = { it.toList() },
    restore = { it.toMutableStateList() },
)

@Composable
fun PotifyApp(
    vm: AppViewModel,
    player: PlayerConnection,
    sharedLink: MutableState<String?>,
    onQueued: () -> Unit,
) {
    val stack = rememberSaveable(saver = StackSaver) { mutableStateListOf(LIBRARY) }
    var nowPlaying by rememberSaveable { mutableStateOf(false) }
    var link by rememberSaveable { mutableStateOf("") }
    var linkError by remember { mutableStateOf<String?>(null) }
    var choice by remember { mutableStateOf<LinkResult.Ambiguous?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun message(text: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text)
        }
    }

    LaunchedEffect(Unit) { vm.messages.collect(::message) }
    LaunchedEffect(Unit) { vm.openReview.collect { if (stack.last() != REVIEW) stack.add(REVIEW) } }
    LaunchedEffect(sharedLink.value) {
        sharedLink.value?.let {
            link = it
            linkError = null
            stack.clear()
            stack.add(LIBRARY)
            nowPlaying = false
            sharedLink.value = null
        }
    }

    fun push(route: String) {
        if (stack.last() != route) stack.add(route)
    }

    fun tab(route: String) {
        stack.clear()
        stack.add(route)
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    BackHandler(enabled = nowPlaying) { nowPlaying = false }
    BackHandler(enabled = !nowPlaying && stack.size > 1) { back() }

    val open = { id: String -> push(if (id == Library.LIKED) LIKED else PLAYLIST + id) }
    val route = stack.last()

    CompositionLocalProvider(LocalThumbDir provides vm.library.thumbDir) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                        MiniPlayer(player, vm, onOpen = { nowPlaying = true })
                        BottomBar(stack.first(), ::tab)
                    }
                },
            ) { padding ->
                AnimatedContent(
                    targetState = route,
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                    label = "screen",
                ) { current ->
                    when {
                        current == LIBRARY -> LibraryScreen(
                            vm = vm,
                            link = link,
                            linkError = linkError,
                            onLinkChange = {
                                link = it
                                linkError = null
                            },
                            onSubmitLink = {
                                when (val result = vm.addLink(link, onQueued)) {
                                    LinkResult.Invalid ->
                                        linkError = "That doesn't look like a YouTube or Spotify link."
                                    LinkResult.Added -> {
                                        link = ""
                                        linkError = null
                                    }
                                    LinkResult.Spotify -> {
                                        link = ""
                                        linkError = null
                                        push(REVIEW)
                                    }
                                    is LinkResult.Ambiguous -> choice = result
                                }
                            },
                            onOpen = open,
                            onSettings = { push(SETTINGS) },
                            onOpenReview = { push(REVIEW) },
                            padding = padding,
                        )
                        current == REVIEW -> SpotifyReviewScreen(
                            vm, player, onBack = ::back,
                            onClosed = { if (stack.last() == REVIEW) back() },
                            onQueued = onQueued,
                            onSaved = { id ->
                                if (stack.last() == REVIEW) stack.removeAt(stack.lastIndex)
                                push(PLAYLIST + id)
                            },
                            onMessage = ::message, padding = padding,
                        )
                        current == SEARCH -> SearchScreen(vm, player, onOpen = open, padding = padding)
                        current == SETTINGS -> SettingsScreen(vm, onBack = ::back, padding = padding)
                        current == LIKED -> PlaylistScreen(
                            Library.LIKED, vm, player, showBack = stack.size > 1,
                            onBack = ::back, onMessage = ::message, padding = padding,
                        )
                        current.startsWith(PLAYLIST) -> PlaylistScreen(
                            current.removePrefix(PLAYLIST), vm, player, showBack = true,
                            onBack = ::back, onMessage = ::message, padding = padding,
                            onFindDuplicates = { push(DUPLICATES) },
                            onSelectToMerge = { push(MERGE) },
                        )
                        current == DUPLICATES -> DuplicatesScreen(
                            vm, player, onBack = ::back,
                            onDone = { if (stack.last() == DUPLICATES) back() },
                            onSelectYourself = {
                                if (stack.last() == DUPLICATES) stack.removeAt(stack.lastIndex)
                                push(MERGE)
                            },
                            padding = padding,
                        )
                        current == MERGE -> MergeScreen(vm, player, onBack = ::back, padding = padding)
                    }
                }
            }
            choice?.let { both ->
                AlertDialog(
                    onDismissRequest = { choice = null },
                    title = { Text("Song or playlist?") },
                    text = { Text("This link is a song opened from a playlist. Download just the song, or the whole playlist?") },
                    confirmButton = {
                        TextButton(onClick = {
                            vm.addPlaylist(both.playlistId, onQueued)
                            choice = null
                            link = ""
                        }) { Text("Whole playlist") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            vm.addVideo(both.videoId, onQueued)
                            choice = null
                            link = ""
                        }) { Text("Just this song") }
                    },
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                )
            }
            AnimatedVisibility(
                visible = nowPlaying && player.currentId != null,
                enter = slideInVertically(tween(320)) { it } + fadeIn(tween(200)),
                exit = slideOutVertically(tween(260)) { it } + fadeOut(tween(200)),
            ) {
                NowPlaying(player, vm, onClose = { nowPlaying = false })
            }
        }
    }
}

@Composable
private fun BottomBar(currentTab: String, onSelect: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.background(colors.surfaceContainer)) {
        HorizontalDivider(color = colors.outlineVariant)
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().height(62.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            BarItem(Icons.Rounded.LibraryMusic, Icons.Rounded.LibraryMusic, "Library", currentTab == LIBRARY) { onSelect(LIBRARY) }
            BarItem(Icons.Rounded.Search, Icons.Rounded.Search, "Search", currentTab == SEARCH) { onSelect(SEARCH) }
            BarItem(Icons.Rounded.Favorite, Icons.Rounded.FavoriteBorder, "Liked", currentTab == LIKED) { onSelect(LIKED) }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BarItem(
    selectedIcon: ImageVector,
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = 36.dp),
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(if (selected) selectedIcon else icon, null, Modifier.size(24.dp), tint = color)
        Spacer(Modifier.height(3.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}
