package io.github.truongfelix.idenanode

import android.content.Context
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * My ads, opened from the Profile card: the ads this identity makes, each with its stage and its one next step
 * (MyAds.kt): a draft goes to review, an approved ad is shown, a shown ad gets more burns. + New opens the form.
 */
class MyAdsActivity : AppActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Scaffold { padding ->
                    MyAdsScreen(onClose = { finish() }, modifier = Modifier.padding(padding))
                }
            }
        }
    }
}

internal fun adStore(context: Context) = AdDraftStore(File(context.filesDir, "ads"))

internal fun myAdsNode(context: Context) =
    MyAdsNode(NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))) { cid -> IpfsStore.cached(context, cid) ?: IpfsStore.fetch(context, cid) }

@Composable
fun MyAdsScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The draft open in the form ("" for a new ad), and the voting open on its page.
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var votingOf by rememberSaveable { mutableStateOf<String?>(null) }
    var reloads by rememberSaveable { mutableIntStateOf(0) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var read by remember { mutableStateOf<Result<Pair<String, MyAds>>?>(null) }
    var acting by remember { mutableStateOf<Pair<AdAction, MyAd>?>(null) }
    LaunchedEffect(reloads) {
        read = null
        read = withContext(Dispatchers.IO) {
            runCatching {
                val node = myAdsNode(context)
                val me = node.me()
                me to node.list(adStore(context), me)
            }
        }
    }
    val me = read?.getOrNull()?.first
    val reload: (String?) -> Unit = { text ->
        acting = null
        text?.let { message = it }
        reloads++
    }

    editing?.let { id ->
        BackHandler { editing = null }
        AdEditorScreen(id.ifEmpty { null }, onClose = { editing = null; reloads++ }, modifier = modifier)
        return
    }
    val contract = votingOf
    if (contract != null && me != null) {
        val back: () -> Unit = { votingOf = null; reloads++ }
        BackHandler(onBack = back)
        OracleVotingScreen(contract, me, onClose = back, modifier = modifier)
        return
    }
    BackHandler(onBack = onClose)
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenTitle(t(R.string.ads_my_ads), onClose) { TextButton(onClick = { message = null; editing = "" }) { Text(t(R.string.ads_new_button)) } }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        val current = read
        when {
            current == null -> CircularProgressIndicator()
            current.isFailure -> {
                Text(t(R.string.ads_node_no_answer, current.exceptionOrNull()?.message.toString()))
                Button(onClick = { reloads++ }) { Text(t(R.string.common_try_again)) }
            }
            else -> {
                val ads = current.getOrThrow().second
                Text(t(R.string.ads_balance, idna(ads.balance.toPlainString())), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ads.profileError?.let {
                    Text(t(R.string.ads_profile_unreadable, it), color = MaterialTheme.colorScheme.error)
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ads.ads, key = { it.draft?.id ?: it.cid }) { ad ->
                        AdCard(ad) { action ->
                            message = null
                            when (action) {
                                AdAction.Edit -> editing = ad.draft?.id
                                AdAction.SeeVoting -> votingOf = ad.contract
                                AdAction.CopyAsDraft -> scope.launch {
                                    editing = withContext(Dispatchers.IO) { runCatching { copyAsDraft(context, ad) }.getOrNull() }
                                }
                                else -> acting = action to ad
                            }
                        }
                    }
                    if (ads.ads.isEmpty()) item { Text(t(R.string.ads_empty), style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }

    val (action, ad) = acting ?: return
    val address = me ?: return
    val close = { acting = null }
    when (action) {
        AdAction.Preview -> AdPreviewDialog(ad, close)
        AdAction.Delete -> DeleteDialog(ad, onClose = close) { reload(t(R.string.ads_deleted, ad.title)) }
        AdAction.SendToReview -> ReviewDialog(ad, address, onClose = close, onDone = reload)
        AdAction.StartReview -> StartReviewDialog(ad, address, onClose = close, onDone = reload)
        AdAction.Show -> ShowAdDialog(ad, address, onClose = close, onDone = reload)
        AdAction.BurnMore -> BurnMoreDialog(ad, address, onClose = close, onDone = reload)
        AdAction.RemoveFromProfile -> RemoveFromProfileDialog(ad, address, onClose = close, onDone = reload)
        else -> close()
    }
}

/** A new draft with the texts and pictures of [ad] ("Edit as a new draft"); returns its id. */
private fun copyAsDraft(context: Context, ad: MyAd): String {
    val store = adStore(context)
    val from = ad.draft ?: AdDraft(id = "", title = ad.title, desc = ad.desc)
    val copy = AdDraft(AdDraftStore.newId(), from.title, from.desc, from.url, System.currentTimeMillis())
    val id = ad.draft?.id
    store.save(copy, id?.let { store.thumb(it) } ?: ad.thumb, id?.let { store.media(it) })
    return copy.id
}

/** An ad in the list: thumbnail, title, line, stage; its next step and the ⋯ menu. */
@Composable
private fun AdCard(ad: MyAd, onAction: (AdAction) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AdImage(ad.thumb, maxSide = 160, Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(ad.title.ifBlank { t(R.string.ads_untitled) }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (ad.desc.isNotBlank()) {
                        Text(ad.desc, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text(stageDot(ad.stage.kind) + " " + ad.stage.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                val (label, main) = mainAction(ad.stage.kind)
                Pill(label, enabled = true) { onAction(main) }
                Box {
                    TextButton(onClick = { menu = true }) { Text("⋯") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        menuActions(ad.stage.kind, ad.draft != null, ad.contract.isNotEmpty()).forEach { (text, action) ->
                            DropdownMenuItem(text = { Text(text) }, onClick = { menu = false; onAction(action) })
                        }
                    }
                }
            }
        }
    }
}

/** ○ draft, ◐ on its way, ● showing. */
private fun stageDot(kind: AdStageKind): String = when (kind) {
    AdStageKind.Draft, AdStageKind.Rejected -> "○"
    AdStageKind.Showing -> "●"
    else -> "◐"
}

@Composable
private fun DeleteDialog(ad: MyAd, onClose: () -> Unit, onDeleted: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(t(R.string.ads_delete_title)) },
        text = { Text(t(R.string.ads_delete_confirm, ad.title.ifBlank { t(R.string.ads_untitled) })) },
        confirmButton = {
            TextButton(onClick = {
                ad.draft?.let { adStore(context).delete(it.id) }
                onDeleted()
            }) { Text(t(R.string.common_delete)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(t(R.string.common_cancel)) } },
    )
}
