package app.calendarium.location

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Opens [query] in the device's maps app. Tries a `geo:` intent first (Google Maps, Organic Maps,
 * OsmAnd, …) and falls back to a Google Maps web search so a device without a dedicated maps app
 * still resolves the place in a browser. Only fires an intent — no network call of our own.
 */
fun openInMaps(context: Context, query: String) {
    val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(query)}"))
    try {
        context.startActivity(geo)
    } catch (_: ActivityNotFoundException) {
        val web = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(query)}"),
        )
        try {
            context.startActivity(web)
        } catch (_: ActivityNotFoundException) {
            // No maps app and no browser — nothing we can do; silently ignore rather than crash.
        }
    }
}
