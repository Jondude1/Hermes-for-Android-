package com.jondude1.hermes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jondude1.hermes.update.Updater
import kotlinx.coroutines.launch

/** "Update ready" strip. Hermes installs its own new versions from the latest GitHub release. */
@Composable
fun UpdateBanner() {
    val release = Updater.available ?: return
    if (Updater.dismissedTag == release.tag) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(20.dp)
    val downloading = Updater.progress >= 0f

    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(W.Deep)
            .border(1.dp, W.Haze, shape)
            .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (downloading) "Downloading ${(Updater.progress * 100).toInt()}%" else "A new version is ready",
            style = T.bodyBone,
            modifier = Modifier.weight(1f),
        )
        if (!downloading) {
            Text(
                "Later",
                style = T.small,
                modifier = Modifier.clickable { Updater.dismissedTag = release.tag }.padding(12.dp),
            )
            Text(
                "Update",
                style = T.button.copy(color = W.Void),
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(W.Bone)
                    .clickable { scope.launch { Updater.downloadAndInstall(ctx) } }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}
