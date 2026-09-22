package org.openautoandroid.source

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Takes a destination shared from another app (P6.4, P6 plan §5.1): `ACTION_SEND` text
 * from the share sheet — OsmAnd's "Message" item — and `ACTION_VIEW geo:` from OsmAnd's
 * "Geo" item, Contacts or Calendar.
 *
 * No window: it reads the intent, hands the destination to [Navigation], says what
 * happened in a toast and finishes, leaving the driver in the app they shared from. A
 * separate activity rather than [ConnectionActivity], so that a share does not stack a
 * second connection screen on top of the sharing app's task.
 *
 * The toast may name the place; the event log and report only say which of `send` and
 * `view` it came through, and whether it was refused.
 */
class DestinationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        take(intent)
        finish()
    }

    private fun take(intent: Intent?) {
        val (source, parsed) = when (intent?.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                DestinationSource.SEND to
                    (text?.let(DestinationParser::parseText) ?: ParsedDestination.Refused(Refusal.NO_LOCATION))
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.dataString
                DestinationSource.VIEW to
                    (uri?.let(DestinationParser::parseUri) ?: ParsedDestination.Refused(Refusal.NO_LOCATION))
            }
            else -> return
        }
        val message = when (parsed) {
            is ParsedDestination.Found -> Navigation.set(this, parsed.destination, source)
            is ParsedDestination.Refused -> {
                Navigation.refuse(parsed.reason, source)
                getString(
                    if (parsed.reason == Refusal.ADDRESS_ONLY) {
                        R.string.destination_address_only
                    } else {
                        R.string.destination_no_location
                    },
                )
            }
        }
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }
}
