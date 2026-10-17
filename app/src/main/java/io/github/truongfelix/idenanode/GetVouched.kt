package io.github.truongfelix.idenanode

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** For an account without an identity nor voucher: what a vouch gives, and its address to share. */
@Composable
fun GetVouchedCard(address: String) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t(R.string.profile_get_vouched_title), style = MaterialTheme.typography.titleMedium)
            Text(t(R.string.profile_get_vouched_text), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, address)
                context.startActivity(Intent.createChooser(send, null))
            }) { Text(t(R.string.profile_share_address)) }
        }
    }
}
