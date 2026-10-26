package com.astrovm.crosstune

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

internal const val PHONE_CODE_TAG = "phone_code"

/** The link a phone code opens, for tests, which can't scan it. */
internal val PhoneCodeLink = SemanticsPropertyKey<String>("PhoneCodeLink")

/** Under a TV's link field: the code a phone's camera opens to type there, or why there's none. */
@Composable
internal fun PhoneCode(page: PhonePage, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val close = remember { FocusRequester() }
    // The remote goes to it, so OK or Back puts it away.
    LaunchedEffect(Unit) { runCatching { close.requestFocus() } }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
    ) {
        Row(Modifier.heightIn(min = 56.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (page is PhonePage.Open) QrCode(page.link)
            Text(
                stringResource(if (page is PhonePage.Open) R.string.phone_page_scan else R.string.phone_page_offline),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp)
            )
            IconButton(onClick = onClose, modifier = Modifier.focusRequester(close)) {
                AppIcon(R.drawable.ic_close, contentDescription = stringResource(R.string.dismiss_button))
            }
        }
    }
}

/** Dark squares on white, whatever the theme, with the margin around them cameras need. */
@Composable
private fun QrCode(link: String) {
    val squares = remember(link) { QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to QUIET_SQUARES)) }
    Canvas(Modifier.size(160.dp).testTag(PHONE_CODE_TAG).semantics { set(PhoneCodeLink, link) }) {
        drawRect(Color.White)
        val square = size.width / squares.width
        for (x in 0 until squares.width) for (y in 0 until squares.height) {
            if (squares[x, y]) drawRect(Color.Black, Offset(x * square, y * square), Size(square, square))
        }
    }
}

private const val QUIET_SQUARES = 2
