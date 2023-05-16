package uk.co.dtapgames.apps.flatgamemaker

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.DialogInterface
import android.graphics.BitmapFactory
import android.support.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.support.v7.app.AlertDialog
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.TextView
import java.io.IOException
import java.net.URLConnection

/**
 * Created by adam on 31/01/18.
 */

fun dropExtension(string: String) : String {
    var split = string.split('.')

    return if (split.size > 1) split.dropLast(1).joinToString(".")
           else string
}

fun nameDialog(activity: Activity, message: Int?, positiveText: Int, negativeText: Int, positiveAction: (DialogInterface, String) -> Unit) {
    val view = activity.layoutInflater.inflate(R.layout.namedialog, null)

    val input : EditText = view.findViewById(R.id.name_input)

    val builder = AlertDialog.Builder(activity)

    builder.setView(view)
    if (message != null) builder.setTitle(message)
    builder.setPositiveButton(positiveText, { dialog, _ ->
                val name = input.text.toString()

                if (name.isNotEmpty()) {
                    positiveAction(dialog, name)
                } else {
                    dialog.cancel()
                    nameDialog(activity, R.string.write_a_name, positiveText, negativeText, positiveAction)
                }
            })
            .setNegativeButton(R.string.cancel, { dialog, _ -> dialog.cancel() })
    builder.create().show()
}

fun getClipData(ctx: Context, clipuri: Uri) : ClipData? {
    val type = if (clipuri.scheme == ContentResolver.SCHEME_CONTENT) {
        ctx.contentResolver.getType(clipuri)
    } else {
        val mime = MimeTypeMap.getSingleton()
        mime.getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(clipuri.path))
    }
    Log.w("flatgame", type ?: "nope")

    val typetype = if (type == null) null else type.split('/')[0]

    if (typetype == null || typetype == "video") {
        val metadataretriever = MediaMetadataRetriever()

        metadataretriever.setDataSource(ctx, clipuri)

        val name = clipuri.lastPathSegment
        val rotation = metadataretriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION).toInt()
        val width = metadataretriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
        val height = metadataretriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
        val duration = metadataretriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION).toLong() * 1000

        return VideoClipData(name, clipuri, rotation, width, height, duration)
    } else if (typetype == "image") {
        val inclip = ctx.contentResolver.openInputStream(clipuri)
        val bitmap = BitmapFactory.decodeStream(ctx.contentResolver.openInputStream(clipuri))
        val name = clipuri.lastPathSegment
        val rotation = try {
            val exif = ExifInterface(inclip)

            exif.rotationDegrees
        } catch (e: IOException) {0}
        inclip.close()

        return ImageClipData(name, clipuri, rotation, bitmap.width, bitmap.height)
    }

    return null
}

sealed class ClipData {
    abstract val name: String
    abstract val uri: Uri
    abstract val rotation: Int
    abstract val width: Int
    abstract val height: Int
}
data class VideoClipData (override val name: String, override val uri: Uri, override val rotation: Int, override val width: Int, override val height: Int, val duration: Long) : ClipData()
data class ImageClipData (override val name: String, override val uri: Uri, override val rotation: Int, override val width: Int, override val height: Int) : ClipData()