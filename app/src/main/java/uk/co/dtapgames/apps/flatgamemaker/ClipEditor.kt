package uk.co.dtapgames.apps.flatgamemaker

import android.animation.*
import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.support.design.widget.FloatingActionButton
import android.support.v4.content.ContextCompat
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.util.AttributeSet
import android.util.Log
import android.view.*
import android.widget.*
import kotlinx.android.synthetic.main.clipeditor.*
import java.io.File
import java.util.*
import kotlin.math.*

/**
 * Created by adam on 31/01/18.
 */
class ClipEditor : AppCompatActivity() {
    var clipsDir: File? = null
    var clipUri: Uri? = null
    var flatGame: Flatgame? = null
    var configFile: File? = null

    var filmStrip: Filmstrip? = null

    private var position: Float = 0F

    var clipData: ClipData? = null

    var queryLinkDirect: LinkSource? = null

    private var seeking = false
    private var seekPip: ClipPane.Pip? = null

    private var mediaPlayer: MediaPlayer? = null

    enum class Mode {
        EDIT_MODE,
        PICK_TIME_MODE
    }

    enum class Request {
        PICK_TIME_DIRECT_LINK_REQUEST
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val configpath = intent.getStringExtra("cfg")
        val mode = intent.getIntExtra("mode", Mode.EDIT_MODE.ordinal)

        if (intent.data == null || configpath == null) { finish(); return }

        val config = File(configpath)
        configFile = config

        val clipuri = intent.data
        clipUri = clipuri

        clipData = getClipData(this, clipuri)
        val clipdata = clipData

        if (clipdata == null) { finish(); return }

        if (clipdata is VideoClipData) {
            val createtime = intent.getLongExtra("time", 0L)
            position = createtime.toFloat() / clipdata.duration
        } else if (mode == Mode.PICK_TIME_MODE.ordinal) {
            if (clipdata is ImageClipData) {
                val intent = Intent()
                intent.putExtra("clip", clipdata.name)
                intent.putExtra("time", 0L)
                setResult(Activity.RESULT_OK, intent)
                Toast.makeText(this, R.string.set_dest_to_image, Toast.LENGTH_LONG).show()
                Timer().schedule(object:TimerTask(){override fun run(){this@ClipEditor.finish()}}, 2000)
            } else {
                finish()
            }
        } // otherwise TODO decide what the fuck to do with images

        flatGame = Flatgame.read(config)
        val flatgame = flatGame

        if (flatgame == null) { finish(); return }

        clipsDir = Flatgame.internalClipsDir(this, flatgame)

        setContentView(R.layout.clipeditor)

        glyph_layer.setOnCreateParams(mode != Mode.EDIT_MODE.ordinal)

        if (clipdata is VideoClipData) {
            filmStrip = filmstrip

            seekPip =
                    if (mode == Mode.PICK_TIME_MODE.ordinal)
                        clip_pane.createPip(position,
                                { pos -> seekTo(pos) },
                                {},
                                { pos ->
                                    val intent = Intent()
                                    intent.putExtra("clip", clipdata.name)
                                    intent.putExtra("time", (pos * clipdata.duration).toLong())
                                    setResult(Activity.RESULT_OK, intent)
                                    finish()
                                },
                                false, R.drawable.ic_check_black_24dp, R.color.gestureBarColor)
                    else null

            filmStrip?.setOnCreateParams(clipuri, mode == Mode.PICK_TIME_MODE.ordinal, mode != Mode.EDIT_MODE.ordinal)
        } else {
            container.removeView(filmstrip)
        }
    }

    fun setMediaPlayer(mediaplayer: MediaPlayer) {
        mediaPlayer = mediaplayer
        mediaplayer.setOnSeekCompleteListener {
            seeking = false
        }

        onUpdateFlatgameConfig()
    }

    override fun onResume() {
        super.onResume()

        clip_view.setClipData(clipData!!)
        //clip_view.setMediaController(MediaController(this))
    }

    override fun onPause() {
        super.onPause()

        mediaPlayer = null
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) return

        when (requestCode) {
            ClipEditor.Request.PICK_TIME_DIRECT_LINK_REQUEST.ordinal -> addDirectLink(data.getStringExtra("clip"), data.getLongExtra("time", 0L))
        }
    }

    fun setStartFrame(time: Long) {
        val flatgame = flatGame ?: return
        val clipdata = clipData ?: return

        val newflatgame = flatgame.copy(startFrame = StartFrame(time, clipdata.name))

        Flatgame.writeInternal(this, newflatgame)

        onUpdateFlatgameConfig()
    }

    fun addStopFrame(time: Long) {
        val flatgame = flatGame ?: return
        val clipdata = clipData ?: return

        flatgame.stopFrames.add(StopFrame(time, clipdata.name))

        Flatgame.writeInternal(this, flatgame)

        onUpdateFlatgameConfig()
    }

    private fun addDirectLink(clip: String, time: Long) {
        val flatgame = flatGame ?: return
        val clipdata = clipData ?: return
        val querylinkdirect = queryLinkDirect ?: return

        val link = Link(querylinkdirect, SimpleDestination(clip, time))

        val links = flatgame.clips[clipdata.name]

        if (links == null) {
            flatgame.clips[clipdata.name] = Clip(mutableListOf(link))
        } else {
            val newlinks = links.links.filter{ l -> l.source != querylinkdirect }
            links.links.clear()
            links.links.addAll(newlinks)
            links.links.add(link)
        }

        queryLinkDirect = null

        Flatgame.writeInternal(this, flatgame)

        onUpdateFlatgameConfig()
    }

    fun deleteLink(index: Int) {
        val flatgame = flatGame ?: return
        val clipdata = clipData ?: return
        val clip = flatgame.clips[clipdata.name] ?: return

        clip.links.removeAt(index)

        Flatgame.writeInternal(this, flatgame)

        onUpdateFlatgameConfig()
    }

    fun deleteStartFrame() {
        val flatgame = flatGame ?: return

        Flatgame.writeInternal(this, flatgame.copy(startFrame = null))

        onUpdateFlatgameConfig()
    }

    fun deleteStopFrame(stopFrame: StopFrame) {
        val flatgame = flatGame ?: return

        flatgame.stopFrames.remove(stopFrame)

        Flatgame.writeInternal(this, flatgame)

        onUpdateFlatgameConfig()
    }

    fun modifyLinkSource(index: Int, newlinksource: LinkSource) {
        val flatgame = flatGame ?: return
        val clipdata = clipData ?: return
        val clip = flatgame.clips[clipdata.name] ?: return

        clip.links[index] = clip.links[index].copy(source = newlinksource)

        Flatgame.writeInternal(this, flatgame)

        onUpdateFlatgameConfig()
    }

    fun modifyStopFrame(oldStopFrame: StopFrame, newStopFrame: StopFrame) {
        val flatgame = flatGame ?: return

        flatgame.stopFrames.remove(oldStopFrame)
        flatgame.stopFrames.add(newStopFrame)

        Flatgame.writeInternal(this, flatgame)

        onUpdateFlatgameConfig()
    }

    fun onUpdateFlatgameConfig() {
        val oldflatgame = flatGame ?: return
        val config = configFile ?: return
        val clipdata = clipData ?: return

        if (Flatgame.configFile(this, oldflatgame).canonicalFile != config.canonicalFile) {
            Log.w("flatgame", "Flatgame does not map to config, exiting")
            finish()
            return
        }

        val newflatgame = Flatgame.read(config) ?: return
        flatGame = newflatgame

        val startFrame = if (newflatgame.startFrame?.clip == clipdata.name) newflatgame.startFrame else null
        val stopFrames = newflatgame.stopFrames.filter{ stopFrame -> stopFrame.clip == clipdata.name }

        val clip = newflatgame.clips[clipdata.name]

        filmStrip?.setClipFlatgameData(clip, startFrame, stopFrames)
        glyph_layer.setClipFlatgameData(clip)

        filmStrip?.barsview?.post{
            filmStrip?.barsview?.resetPositions()
            filmStrip?.barsview?.resetPipPositions()
        }
        glyph_layer.post{ glyph_layer.resetGlyphPositions() }

        seekTo(position)
    }

    fun seekTo(pos: Float) {
        position = pos

        val seekpip = seekPip

        seekPip?.setPosition(position)

        glyph_layer.seekTo(position)
        filmStrip?.barsview?.seekTo(position)

        if (seekpip != null && !seekpip.up) seekpip.popUp()

        if (seeking) return

        val mp = mediaPlayer ?: return

        val msec = (position*mp.duration).toLong()

        seeking = true

        if (Build.VERSION.SDK_INT >= 26) {
            mp.seekTo(msec, MediaPlayer.SEEK_CLOSEST)
        } else {
            mp.seekTo(msec.toInt())
        }
    }

}

class ClipPane(private val ctx: Context, private val attrs: AttributeSet) : RelativeLayout(ctx, attrs) {
    fun createPip(pos: Float, onMoveCallback: (Float) -> Unit, onReleaseCallback: (Float) -> Unit, onClickCallback: (Float) -> Unit, twoSides: Boolean, image: Int, color: Int) : Pip {
        val pip = Pip(ctx, attrs, onMoveCallback, onReleaseCallback, onClickCallback, twoSides, image, color)

        val piplayout = RelativeLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        piplayout.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)

        addView(pip, piplayout)

        pip.setPosition(pos)

        pip.setEnlargement(0F)

        return pip
    }

    class Pip(private val ctx: Context, attrs: AttributeSet, private val onMoveCallback: (Float) -> Unit, private val onReleaseCallback: (Float) -> Unit, private val onClickCallback: (Float) -> Unit,
              private val twoSides: Boolean, private val image: Int?, private val color: Int) : FloatingActionButton(ctx, attrs), GestureDetector.OnDoubleTapListener, GestureDetector.OnGestureListener {
        init {
            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.gestureBarColor))
        }

        private var position: Float = 0F

        private var maxEnlargement = 1F

        private val gestureDetector = GestureDetector(ctx, this, Handler())

        private var scrolled = false

        override fun onShowPress(e: MotionEvent?) {}

        override fun onSingleTapUp(e: MotionEvent?): Boolean = false

        override fun onDown(e: MotionEvent?): Boolean = true

        override fun onFling(e1: MotionEvent?, e2: MotionEvent?, velocityX: Float, velocityY: Float): Boolean = false

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!twoSides || !flipside) {
                val pos = e2.rawX / (parent as ViewGroup).width
                setPosition(pos)
                onMoveCallback(pos)
                if (!scrolled) scrolled = true
            }

            return true
        }

        override fun onLongPress(e: MotionEvent?) {}

        override fun onDoubleTap(e: MotionEvent?): Boolean = false

        override fun onDoubleTapEvent(e: MotionEvent?): Boolean = false

        override fun onSingleTapConfirmed(e: MotionEvent?): Boolean {
            if (twoSides && !flipside) {
                flip()
            } else {
                onClickCallback(position)
            }

            return true
        }

        init {
            if (image != null && !twoSides) setImageDrawable(ContextCompat.getDrawable(ctx, image))
            if (twoSides) setImageDrawable(ContextCompat.getDrawable(ctx, R.drawable.scroll_pip_icon))
        }

        var up = false
        var flipside = false

        fun delete() {
            (parent as ClipPane).removeView(this)
        }

        fun popUp(animate: Boolean = true) {
            //show()

            if (animate) {
                val popupAnimator = ObjectAnimator.ofFloat(this, "enlargement", 0F, 1F)
                popupAnimator.duration = 300
                popupAnimator.start()
            } else {
                setEnlargement(1F)
            }

            up = true
        }

        private fun flip() {
            val startFlipAnimator = ObjectAnimator.ofFloat(this, "scaleX", maxEnlargement, 0F)
            startFlipAnimator.duration = 200

            val finishFlipAnimator = ObjectAnimator.ofFloat(this, "scaleX", 0F, maxEnlargement)
            finishFlipAnimator.duration = 200

            flipside = !flipside

            startFlipAnimator.addListener(object: Animator.AnimatorListener {
                override fun onAnimationRepeat(animation: Animator?) {}

                override fun onAnimationEnd(animation: Animator?) {
                    setImageDrawable(null)
                    if (!flipside) {
                        setImageDrawable(ContextCompat.getDrawable(ctx, R.drawable.scroll_pip_icon))
                        backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.gestureBarColor))
                    }
                    else if (image != null) {
                        setImageDrawable(ContextCompat.getDrawable(ctx, image))
                        backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, color))
                    }
                    finishFlipAnimator.start()
                }

                override fun onAnimationCancel(animation: Animator?) {}

                override fun onAnimationStart(animation: Animator?) {}
            })

            startFlipAnimator.start()
        }

        fun disappear(animate: Boolean = true) {
            if (animate) {
                val disappearAnimator = ObjectAnimator.ofFloat(this, "enlargement", 1F, 0F)
                disappearAnimator.duration = 300
                disappearAnimator.addListener(object : Animator.AnimatorListener {
                    override fun onAnimationRepeat(animation: Animator?) {}

                    override fun onAnimationEnd(animation: Animator?) {
                        //hide()
                    }

                    override fun onAnimationCancel(animation: Animator?) {}

                    override fun onAnimationStart(animation: Animator?) {}
                })
                disappearAnimator.start()
            } else {
                setEnlargement(0F)
            }

            if (twoSides && flipside) flip()

            up = false
        }

        fun setMaxEnlargement(fraction: Float) {
            maxEnlargement = fraction
            if (up) setEnlargement(fraction)
        }

        fun setEnlargement(fraction: Float) {
            Log.w("flatgame", "current max enlargement is $maxEnlargement")
            val enlargement = fraction * maxEnlargement
            translationY = 30F * (1F - enlargement)
            alpha = enlargement
            val scale = 0.3F + 0.7F * enlargement
            scaleX = scale
            scaleY = scaleX
        }

        fun setPosition(pos: Float, dontshow: Boolean = false) {
            position = pos
            translationX = (parent as ViewGroup).width.toFloat() * position - width / 2
        }

        fun getPosition() : Float {
            return position
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (!up) return false

            if (ev.action == MotionEvent.ACTION_UP) {
                if (scrolled && (!twoSides || !flipside)) {
                    onReleaseCallback(position)
                    scrolled = false
                }
            }

            return gestureDetector.onTouchEvent(ev)
        }
    }
}

class ClipView : SurfaceView {
    private val ctx: Context

    constructor(ctx: Context, attrs: AttributeSet) : super(ctx, attrs) {
        this.ctx = ctx
    }
    constructor(ctx: Context) : super(ctx) {
        this.ctx = ctx
    }

    var clpData : ClipData? = null

    fun setClipData(clipdata: ClipData) {
        clpData = clipdata

        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder?) {}

            override fun surfaceCreated(holder: SurfaceHolder) {
                when (clipdata) {
                    is VideoClipData -> {
                        val mediaplayer = MediaPlayer()
                        mediaplayer.setDataSource(ctx, clipdata.uri)
                        mediaplayer.prepare()
                        mediaplayer.setOnPreparedListener{
                            mediaplayer.setDisplay(holder)
                            (ctx as ClipEditor).setMediaPlayer(mediaplayer)
                        }
                    }
                }
            }

        })
    }

    fun computeWidthHeight(widthMeasureSpec: Int, heightMeasureSpec: Int) : Pair<Int, Int> {
        val cd = clpData!!

        val specwidth = MeasureSpec.getSize(widthMeasureSpec)
        val specheight = MeasureSpec.getSize(heightMeasureSpec)

        val sideways = cd.rotation % 180 == 90

        val width = if (sideways) cd.height else cd.width
        val height = if (sideways) cd.width else cd.height

        val tooHigh = height * specwidth > specheight * width

        val mwidth = if (tooHigh) ((width * specheight).toDouble() / height).toInt() else specwidth
        val mheight = if (tooHigh) specheight else ((height * specwidth).toDouble() / width).toInt()

        return Pair(mwidth, mheight)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val specwidth = MeasureSpec.getSize(widthMeasureSpec)
        val specheight = MeasureSpec.getSize(heightMeasureSpec)

        val widthHeight = computeWidthHeight(specwidth, specheight)

        val mwidth = widthHeight.first
        val mheight = widthHeight.second

        setMeasuredDimension(mwidth, mheight)
    }
}

class Filmstrip(private val ctx: Context, attrs: AttributeSet) : RelativeLayout(ctx, attrs), GestureDetector.OnGestureListener {
    private var editLocked = true
    private var pickMode = false

    private var clipUri : Uri? = null

    private var displayview: FilmstripDisplay? = null
    var barsview: FilmstripBars? = null

    private val gestureDetector = GestureDetector(ctx, this)

    fun setOnCreateParams(clipuri: Uri, pickmodeenabled: Boolean, editlocked: Boolean) {
        clipUri = clipuri

        pickMode = pickmodeenabled
        editLocked = editlocked

        displayview = FilmstripDisplay(ctx, clipuri)
        barsview = FilmstripBars(ctx)

        val displayLayout = RelativeLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        displayLayout.topMargin = resources.getDimensionPixelSize(R.dimen.filmstrip_top_margin)

        addView(displayview, displayLayout)
        addView(barsview, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setClipFlatgameData(clip: Clip?, startFrame: StartFrame?, stopFrames: List<StopFrame>) {
        barsview?.setClipFlatgameData(clip, startFrame, stopFrames)
        barsview?.resetPositions()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event)
    }

    override fun onShowPress(e: MotionEvent?) {}

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        barsview?.togglePips()
        return true
    }

    override fun onDown(e: MotionEvent?): Boolean = true

    override fun onFling(e1: MotionEvent?, e2: MotionEvent?, velocityX: Float, velocityY: Float): Boolean = false

    override fun onScroll(e1: MotionEvent, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
        val pos = e2.x/measuredWidth.toFloat()
        barsview?.seekTo(pos)
        (ctx as ClipEditor).seekTo(pos)

        return true
    }

    override fun onLongPress(e: MotionEvent?) {}


    class FilmstripBars(private val ctx: Context) : RelativeLayout(ctx) {
        private val seekBar = Bar(ctx, 0F, R.color.seekbarColor)

        init {
            addView(seekBar)
        }

        private val gestureBars = mutableMapOf<Int, BarPeriod>()
        private val linkBars = mutableMapOf<Int, Bar>()
        private var startBar : Bar? = null
        private var stopBars = mutableListOf<Bar>()

        private var pipsUp = false

        fun setClipFlatgameData(clip: Clip?, startFrame: StartFrame?, stopFrames: List<StopFrame>) {
            clearAllButSeek()
            clip?.links?.forEachIndexed(::addLink)
            if (startFrame != null) addStartFrame(startFrame)
            stopFrames.forEach{ stopFrame -> addStopFrame(stopFrame) }
        }

        private fun clearAllButSeek() {
            for ((_, barperiod) in gestureBars) {
                barperiod.clearPips()
                removeView(barperiod)
            }
            gestureBars.clear()

            for ((_, bar) in linkBars) {
                bar.deletePip()
                removeView(bar)
            }
            linkBars.clear()

            if (startBar != null) {
                startBar?.deletePip()
                removeView(startBar)
                startBar = null
            }

            stopBars.forEach{ stopBar ->
                stopBar.deletePip()
                removeView(stopBar) }
            stopBars.clear()
        }

        fun resetPositions() {
            seekBar.resetPosition()
            gestureBars.forEach{ (_, gestureBar) -> gestureBar.resetPosition() }
            linkBars.forEach{ (_, linkBar) -> linkBar.resetPosition() }
            startBar?.resetPosition()
            stopBars.forEach{ stopBar -> stopBar.resetPosition() }
        }

        fun isolate(linkGesture: LinkGesture) {
            setPipsEnabled(false)
            gestureBars.forEach{ (_, barperiod) -> if (barperiod.linkGesture == linkGesture) {
                barperiod.setPipsEnabled(true)
                barperiod.setPipsStatus(true)
            } }
        }

        private fun addLink (index: Int, link: Link) {
            val deleteAction = { (ctx as ClipEditor).deleteLink(index) }

            when (link.source) {
                is LinkGesture -> addGesture(index, link.source, deleteAction)
                is LinkFrame -> addLinkFrame(index, link.source, deleteAction)
            }
        }

        private fun addGesture(index: Int, linkGesture: LinkGesture, deleteAction: () -> Unit) {
            val clipdata = (ctx as ClipEditor).clipData as? VideoClipData ?: return

            val start = (linkGesture.period.start.toDouble() / clipdata.duration.toDouble()).toFloat()
            val end = (linkGesture.period.end.toDouble() / clipdata.duration.toDouble()).toFloat()

            Log.w("flatgame", "adding a gesture of period $start - $end")

            val gesturebar = BarPeriod(ctx, linkGesture, start, end, R.color.gestureBarColor, R.color.gestureWindowColor)
            addView(gesturebar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            gestureBars[index] = gesturebar

            gesturebar.createPips({startpos, endpos ->
                ctx.modifyLinkSource(index, linkGesture.copy(period = Period((startpos.toDouble()*clipdata.duration).toLong(), (endpos.toDouble()*clipdata.duration).toLong())))
            }, deleteAction)

            gesturebar.setPipsStatus(pipsUp, false)
        }

        private fun addStartFrame(startFrame: StartFrame) {
            val clipdata = (ctx as ClipEditor).clipData as? VideoClipData ?: return

            val time = (startFrame.time.toDouble() / clipdata.duration.toDouble()).toFloat()
            val startbar = Bar(ctx, time, R.color.startFrameColor)
            addView(startbar)
            startBar = startbar

            startbar.setPip(
                    ctx.clip_pane.createPip(
                            time,
                            {pos -> startbar.setPosition(pos)},
                            {pos -> ctx.setStartFrame((pos.toDouble()*clipdata.duration).toLong())},
                            {ctx.deleteStartFrame()},
                            true,
                            R.drawable.delete_icon,
                            R.color.stopFrameColor
                    )
            )

            startbar.setPipStatus(pipsUp, false)
        }

        private fun addLinkFrame(index: Int, linkFrame: LinkFrame, deleteAction: () -> Unit) {
            val clipdata = (ctx as ClipEditor).clipData as? VideoClipData ?: return

            val time = (linkFrame.time.toDouble() / clipdata.duration.toDouble()).toFloat()

            val linkbar = Bar(ctx, time, R.color.linkFrameColor)
            addView(linkbar)
            linkBars[index] = linkbar

            linkbar.setPip(
                    ctx.clip_pane.createPip(
                            time,
                            {pos -> linkbar.setPosition(pos)},
                            {pos -> ctx.modifyLinkSource(index, linkFrame.copy(time = (pos.toDouble()*clipdata.duration).toLong()))},
                            {deleteAction()},
                            true,
                            R.drawable.delete_icon,
                            R.color.stopFrameColor
                    )
            )

            linkbar.setPipStatus(pipsUp, false)
        }

        private fun addStopFrame(stopFrame: StopFrame) {
            val clipdata = (ctx as ClipEditor).clipData as? VideoClipData ?: return

            val time = (stopFrame.time.toDouble() / clipdata.duration.toDouble()).toFloat()
            val stopbar = Bar(ctx, time, R.color.stopFrameColor)
            addView(stopbar)
            stopBars.add(stopbar)

            stopbar.setPip(
                    ctx.clip_pane.createPip(
                            time,
                            {pos -> stopbar.setPosition(pos)},
                            {pos -> ctx.modifyStopFrame(stopFrame, stopFrame.copy(time = (pos.toDouble()*clipdata.duration).toLong()))},
                            {ctx.deleteStopFrame(stopFrame)},
                            true,
                            R.drawable.delete_icon,
                            R.color.stopFrameColor
                    )
            )

            stopbar.setPipStatus(pipsUp, false)
        }

        fun resetPipPositions() {
            gestureBars.forEach{ (_, barperiod) -> barperiod.resetPipPositions() }
            linkBars.forEach{ (_, linkbar) -> linkbar.resetPipPosition() }
            startBar?.resetPipPosition()
            stopBars.forEach{ stopbar -> stopbar.resetPipPosition() }
        }

        fun setPipsStatus(up: Boolean, animate: Boolean = true) {
            pipsUp = up
            gestureBars.forEach{ (_, barperiod) -> barperiod.setPipsStatus(up, animate) }
            linkBars.forEach{ (_, linkbar) -> linkbar.setPipStatus(up, animate) }
            startBar?.setPipStatus(up, animate)
            stopBars.forEach{ stopbar -> stopbar.setPipStatus(up, animate) }
        }

        fun setPipsEnabled(enabled: Boolean) {
            if (!enabled) pipsUp = false
            gestureBars.forEach{ (_, barperiod) -> barperiod.setPipsEnabled(enabled) }
            linkBars.forEach{ (_, linkbar) -> linkbar.setPipEnabled(enabled) }
            startBar?.setPipEnabled(enabled)
            stopBars.forEach{ stopbar -> stopbar.setPipEnabled(enabled) }
        }

        fun togglePips() {
            pipsUp = !pipsUp
            gestureBars.forEach{ (_, barperiod) -> barperiod.togglePips() }
            linkBars.forEach{ (_, linkbar) -> linkbar.togglePip() }
            startBar?.togglePip()
            stopBars.forEach{ stopbar -> stopbar.togglePip() }
        }

        fun seekTo (position: Float) {
            seekBar.setPosition(position)

            gestureBars.forEach{ (_, barperiod) -> barperiod.updatePips(position) }
            linkBars.forEach{ (_, linkbar) -> linkbar.updatePip(position) }
            startBar?.updatePip(position)
            stopBars.forEach{ stopbar -> stopbar.updatePip(position) }
        }

        class BarPeriod(private val ctx: Context, val linkGesture: LinkGesture, private var startPos: Float, private var endPos: Float, color: Int, backgroundColor: Int) : RelativeLayout(ctx) {
            private val barWidth = resources.getDimensionPixelSize(R.dimen.filmstrip_bar_width)

            private val startBar = Bar(ctx, 0F, color)
            private val endBar = Bar(ctx, 1F, color)
            private val window = Bar(ctx, 0F, backgroundColor, true)

            private var startPip: ClipPane.Pip? = null
            private var endPip: ClipPane.Pip? = null

            init {
                addView(window)
                addView(startBar)
                addView(endBar)
            }

            fun createPips(changeRange: (Float, Float) -> Unit, deleteAction: () -> Unit) {
                ctx as ClipEditor
                val startpip =
                        ctx.clip_pane.createPip(
                                startPos,
                                {pos ->
                                    startPos = pos
                                    resetPosition()
                                },
                                {pos -> changeRange(pos, endPos)},
                                {deleteAction()},
                                true,
                                R.drawable.delete_icon,
                                R.color.stopFrameColor
                        )
                val endpip =
                        ctx.clip_pane.createPip(
                                endPos,
                                {pos ->
                                    endPos = pos
                                    resetPosition()
                                },
                                {pos -> changeRange(startPos, pos)},
                                {deleteAction()},
                                true,
                                R.drawable.delete_icon,
                                R.color.stopFrameColor
                        )

                startBar.setPip(startpip)
                endBar.setPip(endpip)
            }

            fun setPipsEnabled(enabled: Boolean) {
                startBar.setPipEnabled(enabled)
                endBar.setPipEnabled(enabled)
            }

            fun updatePips(seekpos: Float) {
                startBar.updatePip(seekpos)
                endBar.updatePip(seekpos)
            }

            fun clearPips() {
                startBar.deletePip()
                endBar.deletePip()
            }

            fun togglePips() {
                startBar.togglePip()
                endBar.togglePip()
            }

            fun setPipsStatus(up: Boolean, animate: Boolean = true) {
                startBar.setPipStatus(up, animate)
                endBar.setPipStatus(up, animate)
            }

            fun resetPipPositions() {
                startBar.setPipPosition(startPos)
                endBar.setPipPosition(endPos)
            }

            fun resetPosition() {
                val p = parent
                p as View
                translationX = p.width.toFloat() * startPos - barWidth.toFloat() / 2
                layoutParams = RelativeLayout.LayoutParams(((endPos - startPos) * p.width + barWidth).toInt(), RelativeLayout.LayoutParams.MATCH_PARENT)

                window.resetPosition()
                startBar.resetPosition()
                endBar.resetPosition()
            }

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val specwidth = MeasureSpec.getSize(widthMeasureSpec)
                val widthmode = MeasureSpec.getMode(widthMeasureSpec)

                val periodwidth = if (widthmode == MeasureSpec.EXACTLY) specwidth
                                  else ((endPos - startPos) * specwidth.toFloat() + barWidth).toInt()

                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                setMeasuredDimension(periodwidth, MeasureSpec.getSize(heightMeasureSpec))
            }
        }

        class Bar(ctx: Context, private var position: Float, color: Int, private val fillParent: Boolean = false) : View (ctx) {
            private val barWidth = ctx.resources.getDimensionPixelSize(R.dimen.filmstrip_bar_width)

            private var pip: ClipPane.Pip? = null

            private var pipup = false
            private var pipenabled = true

            fun setPip(p: ClipPane.Pip?) {
                pip = p
            }

            fun updatePip(seekpos: Float) {
                val p = pip

                if (p != null) {
                    val dist = seekpos - p.getPosition()
                    p.setMaxEnlargement(cos(min(abs(dist*2.5F), PI.toFloat()/2F)))
                }
            }

            fun deletePip() {
                pip?.delete()
            }

            fun togglePip() {
                setPipStatus(!pipup)
            }

            fun setPipEnabled(enabled: Boolean) {
                if (!enabled) setPipStatus(false)

                pipenabled = enabled
            }

            fun setPipStatus(up : Boolean, animate: Boolean = true) {
                if (!pipenabled) return

                val p = pip ?: return

                if (up != pipup) {
                    if (up) p.popUp(animate)
                    else p.disappear(animate)

                    pipup = up
                }
            }

            fun setPipPosition(pos: Float) {
                val p = pip ?: return
                p.setPosition(pos)
            }

            fun resetPipPosition() {
                setPipPosition(position)
            }

            fun resetPosition() {
                val p = parent as View
                translationX = (p.width.toFloat() - barWidth) * position
            }

            fun setPosition(pos: Float) {
                position = pos
                resetPosition()
            }

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val specwidth = MeasureSpec.getSize(widthMeasureSpec)
                val specheight = MeasureSpec.getSize(heightMeasureSpec)

                setMeasuredDimension(if (fillParent) specwidth else barWidth, specheight)
            }

            private val paint = Paint()

            init {
                paint.color = ContextCompat.getColor(ctx, color)
            }

            override fun onDraw(canvas: Canvas) {
                val width = canvas.width
                val height = canvas.height

                canvas.drawRect(0F, 0F, width.toFloat(), height.toFloat(), paint)
            }
        }
    }

    class FilmstripDisplay(private val ctx: Context, private val clipuri: Uri) : View(ctx) {
        private var bitmaps = mutableListOf<Bitmap>()

        fun fillBitmaps () {
            val cd = (ctx as ClipEditor).clipData!! as? VideoClipData ?: return

            bitmaps.clear()

            val sideways = cd.rotation % 180 == 90

            val vwidth = if (sideways) cd.height else cd.width
            val vheight = if (sideways) cd.width else cd.height

            val bitwidth = (height.toDouble()/vheight.toDouble()*vwidth.toDouble()).toInt()

            var offset = 0

            val metadataRetriever = MediaMetadataRetriever()
            metadataRetriever.setDataSource(ctx, clipuri)

            while (offset < width) {
                val timeoffset = (cd.duration * (offset + bitwidth/2).toFloat()/width.toFloat()).toLong()
                val frame = metadataRetriever.getFrameAtTime(timeoffset, MediaMetadataRetriever.OPTION_CLOSEST)
                if (frame != null) bitmaps.add(Bitmap.createScaledBitmap(frame, bitwidth, height, true))
                offset += bitwidth
            }
        }

        override fun onDraw(canvas: Canvas) {
            if (bitmaps.size == 0) fillBitmaps()

            var offset = 0F
            val matrix = Matrix()

            Log.w("flatgame", "there are ${bitmaps.size} bitmaps")
            for (bitmap in bitmaps) {
                matrix.setTranslate(offset, 0F)
                canvas.drawBitmap(bitmap, matrix, null)
                offset += bitmap.width
            }
        }
    }
}

class GlyphLayer(private val ctx: Context, attrs: AttributeSet) : RelativeLayout(ctx, attrs),
    GestureDetector.OnGestureListener, ValueAnimator.AnimatorUpdateListener {

    private val baseLayer = RelativeLayout(ctx)
    private val darkOverlay = DarkOverlay(ctx)
    private val editLayer = RelativeLayout(ctx)

    init {
        setWillNotDraw(false)

        addView(baseLayer, RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT))
        addView(darkOverlay, RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT))
        addView(editLayer, RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT))
    }

    private var editLocked = true

    private var cliptime : Long = 0L

    private val gestureDetector = GestureDetector(ctx, this, Handler())

    private var editing : Glyph? = null
    private var darkness : Float = 0F

    private var flingLine : Pair<VarPoint, VarPoint>? = null

    private val glyphs = mutableListOf<Glyph>()

    fun setOnCreateParams(locked: Boolean) {
        editLocked = locked
    }

    fun setClipFlatgameData(clip: Clip?) {
        baseLayer.removeAllViews()
        editLayer.removeAllViews()
        glyphs.clear()
        clip?.links?.forEachIndexed{ index, link ->
            when (link.source) {
                is LinkGesture -> {
                    val glyph = Glyph(ctx, index, link.source, link.destination)
                    glyphs.add(glyph)
                    editLayer.addView(glyph, RelativeLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                }
            }
        }
        updateGlyphVisibility(cliptime)
        resetGlyphPositions()
        val e = editing
        if (e != null) {
            val newglyphs = glyphs.filter{ g -> g.linkGesture.region == e.linkGesture.region }
            if (newglyphs.isNotEmpty()) editGlyph(newglyphs[0])
        }
    }

    fun resetGlyphPositions() {
        glyphs.forEach{ glyph -> glyph.resetPosition() }
    }

    private fun updateGlyphVisibility(time: Long) {
        glyphs.forEach{ glyph ->
            if (time >= glyph.linkGesture.period.start && time <= glyph.linkGesture.period.end) {
                if (glyph.visibility == View.INVISIBLE) glyph.visibility = View.VISIBLE
            } else {
                if (glyph.visibility == View.VISIBLE) glyph.visibility = View.INVISIBLE
            }
        }
    }

    private fun editGlyph(glyph: Glyph) {
        glyphs.forEach{ g ->
            if (g !== glyph) {
                editLayer.removeView(g)
                baseLayer.addView(g, RelativeLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }
        }

        val animator = AnimatorInflater.loadAnimator(ctx, R.animator.glyph_edit_enable)
        animator.setTarget(darkOverlay)
        animator.start()
        editing = glyph
        glyph.startEditing()
        (ctx as ClipEditor).filmStrip?.barsview?.isolate(glyph.linkGesture)
    }

    private fun stopEditing(action: () -> Unit) {
        val e = editing ?: return

        editing = null
        e.stopEditing()

        val animator = AnimatorInflater.loadAnimator(ctx, R.animator.glyph_edit_disable)
        animator.setTarget(darkOverlay)
        animator.addListener(object : Animator.AnimatorListener {
            override fun onAnimationRepeat(animation: Animator?) {}

            override fun onAnimationEnd(animation: Animator?) {
                glyphs.forEach{ g ->
                    if (g !== e) {
                        baseLayer.removeView(g)
                        editLayer.addView(g, RelativeLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                    }
                }

                action()
            }

            override fun onAnimationCancel(animation: Animator?) {}

            override fun onAnimationStart(animation: Animator?) {}
        })
        animator.start()
        (ctx as ClipEditor).filmStrip?.barsview?.setPipsEnabled(true)
        ctx.filmStrip?.barsview?.setPipsStatus(false)
    }

    // Gesture listener

    override fun onShowPress(p0: MotionEvent?) {

    }

    override fun onSingleTapUp(event: MotionEvent): Boolean {
        if (editing == null) {
            createTapGesture((event.x - left) / width, (event.y - top) / height)
        }
        return true
    }

    override fun onDown(p0: MotionEvent?): Boolean = true

    override fun onFling(e1: MotionEvent, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
        if (editing == null) {
            createFlingGesture((e1.x - left) / width, (e1.y - top) / height, atan2(velocityY, velocityX))
        }
        return true
    }

    override fun onScroll(p0: MotionEvent, p1: MotionEvent, p2: Float, p3: Float): Boolean {
        val flingline = flingLine
        if (flingline == null) flingLine = Pair(VarPoint(p0.x,  p0.y), VarPoint(p1.x, p1.y))
        else {
            flingline.first.x = p0.x
            flingline.first.y = p0.y
            flingline.second.x = p1.x
            flingline.second.y = p1.y
        }

        invalidate()

        return false
    }

    override fun onLongPress(p0: MotionEvent?) {
        val builder = AlertDialog.Builder(ctx)

        builder.setTitle(R.string.inserting_control_frame)
        builder.setItems(R.array.chooseframelink, { _, id ->
            when (id) {
                0 -> createStartFrame(cliptime)
                1 -> createLinkFrame(cliptime)
                2 -> createStopFrame(cliptime)
            }
        }).create().show()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            flingLine = null
            invalidate()
        }
        return if (!editLocked) gestureDetector.onTouchEvent(event)
               else false
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        return if (Build.VERSION.SDK_INT >= 23) gestureDetector.onGenericMotionEvent(event)
               else false
    }

    fun seekTo(position: Float) {
        val clipdata = (ctx as ClipEditor).clipData!! as? VideoClipData ?: return
        val time = (position * clipdata.duration).toLong()
        updateGlyphVisibility(time)
        cliptime = time
    }

    // Gestures

    private fun createTapGesture(x: Float, y: Float) {
        val linkGesture = LinkGesture(TapGesture(Point(x, y)),
                                                 CircleRegion(Point(x, y),
                                                 Flatgame.gestureRadius),
                                      Flatgame.defaultPeriodFromTime(cliptime))

        createLinkFromSource(linkGesture)
    }

    private fun createFlingGesture(x: Float, y: Float, angle: Float) {
        val linkGesture = LinkGesture(FlingGesture(Point(x,y), angle),
                                                   CircleRegion(Point(x,y),
                                                   Flatgame.gestureRadius),
                                      Flatgame.defaultPeriodFromTime(cliptime))

        createLinkFromSource(linkGesture)
    }

    // Frames

    private fun createStartFrame(time: Long) {
        (ctx as ClipEditor).setStartFrame(time)
    }

    private fun createLinkFrame(time: Long) {
        createLinkFromSource(LinkFrame(time))
    }

    private fun createStopFrame(time: Long) {
        (ctx as ClipEditor).addStopFrame(time)
    }

    // Animator

    override fun onAnimationUpdate(animation: ValueAnimator) {
        darkness = animation.animatedValue as Float
    }

    /////////

    private fun createLinkFromSource(linkSource: LinkSource) {
        // Create new link id paired with source, set as member queryLink
        // When the selection activity returns call setQueryLinkDestination(linkdestination)

        val linkcreatetext =
                when (linkSource) {
                    is LinkFrame -> R.string.creating_link_frame
                    is LinkGesture -> when (linkSource.gesture) {
                        is TapGesture -> R.string.creating_tap_gesture
                        is FlingGesture -> R.string.creating_fling_gesture
                    }
                }

        val builder = AlertDialog.Builder(ctx)

        builder.setTitle(linkcreatetext)
        builder.setItems(R.array.chooselinktype, {_, which ->
            when (which) {
                0 -> createLinkWithDirectLinkDestination(linkSource)
                1 -> createLinkWithRandomLinkDestination(linkSource)
            }
        }).create().show()
    }

    private fun createLinkWithDirectLinkDestination(linkSource: LinkSource) {
        val builder = AlertDialog.Builder(ctx)

        builder.setTitle(R.string.creating_direct_link)
        val listview = ListView(ctx)
        val dialog = builder.setView(listview).create()

        val adapter = DirectLinkAdapter(ctx as ClipEditor, Handler(), dialog, linkSource)

        listview.adapter = adapter

        dialog.show()
    }

    private fun createLinkWithRandomLinkDestination(linkSource: LinkSource) {

    }

    override fun onMeasure (widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cv = (ctx as ClipEditor).clip_view ?: return

        val specwidth = MeasureSpec.getSize(widthMeasureSpec)
        val specheight = MeasureSpec.getSize(heightMeasureSpec)

        val widthHeight = cv.computeWidthHeight(specwidth, specheight)

        super.onMeasure(MeasureSpec.makeMeasureSpec(widthHeight.first, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(widthHeight.second, MeasureSpec.EXACTLY))
    }

    private val paint = Paint()

    override fun onDraw(canvas: Canvas) {
        paint.color = ContextCompat.getColor(ctx, R.color.gestureBarColor)
        paint.strokeWidth = resources.getDimensionPixelSize(R.dimen.filmstrip_bar_width).toFloat()

        val flingline = flingLine
        if (flingline != null) {
            canvas.drawLine(flingline.first.x, flingline.first.y,
                            flingline.second.x, flingline.second.y, paint)
        }
    }

    class Glyph (private val ctx: Context, private val index: Int, val linkGesture: LinkGesture, private val linkDestination: LinkDestination) : View(ctx),
            GestureDetector.OnGestureListener, GestureDetector.OnDoubleTapListener, ScaleGestureDetector.OnScaleGestureListener {
        var scale = 1F

        var xMove = 0F
        var yMove = 0F

        private var editing = false
        private var disabled = false

        private var onEditModePress = false

        private val moveDeadZone = resources.getDimensionPixelSize(R.dimen.glyph_move_dead_zone)

        private val gestureDetector = GestureDetector(ctx, this)
        private val scaleGestureDetector = ScaleGestureDetector(ctx, this)

        private val drawnAngleLimit = PI/64
        private val drawnMaxRadius = resources.getDimensionPixelSize(R.dimen.drawn_max_radius)
        private val drawnMinRadius = resources.getDimensionPixelSize(R.dimen.drawn_min_radius)

        private val currentPoints = mutableListOf<Point>()
        private val defaultCentroid = Point(0F,0F)

        fun resetPosition() {
            invalidate()

            Log.w("flatgame", "translationX is $translationX, translationY is $translationY")
        }

        private val paint = Paint()
        private val lineWidth = resources.getDimensionPixelSize(R.dimen.filmstrip_bar_width)

        private val tapIcon = ContextCompat.getDrawable(ctx, R.drawable.tap_icon)!!
        private val flingArrow = ContextCompat.getDrawable(ctx, R.drawable.fling_arrow)!!

        private val tapIconWidth = (tapIcon.intrinsicWidth * 0.7F).toInt()
        private val tapIconHeight = (tapIcon.intrinsicHeight * 0.7F).toInt()

        private val flingArrowWidth = (flingArrow.intrinsicWidth * 0.7F).toInt()
        private val flingArrowHeight = (flingArrow.intrinsicHeight * 0.7F).toInt()

        private val tapIconXOffset = -0.48F*tapIconWidth
        private val tapIconYOffset = -0.32F*tapIconHeight

        private val flingArrowXOffset = -0.12F*flingArrowWidth
        private val flingArrowYOffset = 0.5F*flingArrowHeight

        private val deleteIcon = ContextCompat.getDrawable(ctx, R.drawable.delete_icon)!!
        private val deleteCircleRadius = resources.getDimensionPixelSize(R.dimen.glyph_delete_circle_radius).toFloat()

        init {
            paint.color = ContextCompat.getColor(ctx, R.color.gestureBarColor)
            paint.strokeWidth = lineWidth.toFloat()
            paint.style = Paint.Style.STROKE

            tapIcon.bounds = Rect(0, 0, tapIconWidth, tapIconHeight)
            flingArrow.bounds = Rect(0, 0, flingArrowWidth, flingArrowHeight)
            deleteIcon.bounds = Rect(0, 0, deleteCircleRadius.toInt()*2, deleteCircleRadius.toInt()*2)
        }

        fun startEditing() {
            editing = true
            invalidate()
        }

        fun stopEditing() {
            editing = false
            onEditModePress = false
            invalidate()
        }

        fun regionFromEdits() : LinkRegion {
            val oldregion = linkGesture.region

            return if (currentPoints.size > 0) {
                DrawnRegion(currentPoints.map{ point -> scrToPt(point.x, point.y) }.toMutableList())
            } else when (oldregion) {
                is CircleRegion -> CircleRegion(Point(oldregion.origin.x + xMove / width, oldregion.origin.y + yMove / height), oldregion.radius * scale)
                is DrawnRegion -> {
                    DrawnRegion(oldregion.points.map { point ->
                        Point((point.x + xMove / width - oldregion.centroid.x) * scale + oldregion.centroid.x,
                                (point.y + yMove / height - oldregion.centroid.y) * scale + oldregion.centroid.y)
                    }.toMutableList())
                }
            }
        }

        override fun onDraw (canvas: Canvas) {
            val x = when (linkGesture.region) {
                is CircleRegion -> width * linkGesture.region.origin.x + xMove
                is DrawnRegion -> width * linkGesture.region.centroid.x + xMove
            }

            val y = when (linkGesture.region) {
                is CircleRegion -> height * linkGesture.region.origin.y + yMove
                is DrawnRegion -> height * linkGesture.region.centroid.y + yMove
            }

            if (currentPoints.size > 0) {
                drawPolygon(canvas, currentPoints, defaultCentroid, false, true)
            } else when (linkGesture.region) {
                is CircleRegion -> {
                    val largestdimension = max(width, height)
                    val viewradius = largestdimension * linkGesture.region.radius * scale

                    canvas.drawCircle(x, y, viewradius, paint)
                }
                is DrawnRegion -> {
                    drawPolygon(canvas, linkGesture.region.points, linkGesture.region.centroid, true, false)
                }
            }

            if (editing) {
                canvas.drawCircle(x, y, deleteCircleRadius, paint)
                canvas.save()
                canvas.translate(x - deleteCircleRadius, y - deleteCircleRadius)
                deleteIcon.draw(canvas)
                canvas.restore()
            } else {
                when (linkGesture.gesture) {
                    is TapGesture -> {
                        canvas.save()
                        canvas.translate(x, y)
                        canvas.translate(tapIconXOffset,tapIconYOffset) // adjust to get finger in middle
                        tapIcon.draw(canvas)
                        canvas.restore()
                    }
                    is FlingGesture -> {
                        val angle = linkGesture.gesture.angle

                        canvas.save()
                        canvas.translate(x, y)
                        canvas.translate(tapIconXOffset,tapIconYOffset) // adjust to get finger in middle
                        tapIcon.draw(canvas)
                        canvas.restore()

                        canvas.save()
                        canvas.translate(x, y)
                        canvas.translate(-flingArrowXOffset, -flingArrowYOffset)
                        canvas.rotate(angle / PI.toFloat() * 180F, flingArrowXOffset, flingArrowYOffset)
                        flingArrow.draw(canvas)
                        canvas.restore()
                    }
                }
            }
        }

        private fun drawPolygon(canvas: Canvas, points: List<Point>, centroid: Point, closed: Boolean, screenspace: Boolean) {
            if (points.isEmpty()) return

            val xscale = if (screenspace) 1F else width.toFloat()
            val yscale = if (screenspace) 1F else height.toFloat()

            for (i in 1.until(points.size)) {
                canvas.drawLine(((points[i].x-centroid.x)*scale + centroid.x)*xscale + xMove, ((points[i].y-centroid.y)*scale + centroid.y)*yscale + yMove,
                        ((points[i-1].x-centroid.x)*scale + centroid.x)*xscale + xMove, ((points[i-1].y-centroid.y)*scale + centroid.y)*yscale + yMove, paint)
            }
            val lastpoint = points[points.size-1]
            val firstpoint = points[0]
            if (closed) canvas.drawLine(((lastpoint.x-centroid.x)*scale + centroid.x)*xscale + xMove, ((lastpoint.y-centroid.y)*scale + centroid.y)*yscale + yMove,
                    ((firstpoint.x-centroid.x)*scale + centroid.x)*xscale + xMove, ((firstpoint.y-centroid.y)*scale + centroid.y)*yscale + yMove, paint)
        }

        private fun scrToPt(x: Float, y: Float) : Point {
            return Point(x/width, y/height)
        }

        private fun isInGlyph(x: Float, y: Float) : Boolean {
            return when (linkGesture.region) {
                is CircleRegion -> {
                    val largestdimension = max(width, height)
                    val viewradius = largestdimension * linkGesture.region.radius * scale

                    val glyphx = x - linkGesture.region.origin.x * width - xMove
                    val glyphy = y - linkGesture.region.origin.y * height - yMove

                    sqrt(glyphx*glyphx + glyphy*glyphy) < viewradius
                }
                is DrawnRegion -> linkGesture.region.isInRegion(scrToPt(x,y))
            }
        }

        private fun isInDelete(x: Float, y : Float) : Boolean {
            return when (linkGesture.region) {
                is CircleRegion -> {
                    val glyphx = x - linkGesture.region.origin.x * width - xMove
                    val glyphy = y - linkGesture.region.origin.y * height - yMove

                    sqrt(glyphx*glyphx + glyphy*glyphy) < deleteCircleRadius
                }
                is DrawnRegion -> {
                    val glyphx = x - linkGesture.region.centroid.x * width - xMove
                    val glyphy = y - linkGesture.region.centroid.y * height - yMove

                    sqrt(glyphx*glyphx + glyphy*glyphy) < deleteCircleRadius
                }
            }
        }

        private fun hasChanged() = xMove != 0F || yMove != 0F || scale != 1F || currentPoints.size > 0

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (disabled || (parent.parent as GlyphLayer).editLocked) return false

            if (event.action == MotionEvent.ACTION_UP) {
                val haschanged = hasChanged()
                if (currentPoints.size > 0 || onEditModePress && haschanged) {
                    (parent.parent as GlyphLayer).stopEditing{
                        (ctx as ClipEditor).modifyLinkSource(index, linkGesture.copy(region = regionFromEdits()))
                    }
                    disabled = true
                    return true
                } else if (onEditModePress) {
                    onEditModePress = false
                }
            } else if (editing && event.action == MotionEvent.ACTION_DOWN && isInDelete(event.x, event.y)) {
                (parent.parent as GlyphLayer).stopEditing{
                    (ctx as ClipEditor).deleteLink(index)
                }
                visibility = GONE
                disabled = true
                return true
            }

            return if (editing || isInGlyph(event.x, event.y)) {
                scaleGestureDetector.onTouchEvent(event)
                scaleGestureDetector.isInProgress || gestureDetector.onTouchEvent(event)
            }
            else false
        }

        // Standard gestures

        override fun onShowPress(e: MotionEvent?) {

        }

        override fun onSingleTapUp(e: MotionEvent?): Boolean = false

        override fun onDown(e: MotionEvent?): Boolean = true

        override fun onFling(e1: MotionEvent?, e2: MotionEvent?, velocityX: Float, velocityY: Float): Boolean = false

        override fun onScroll(e1: MotionEvent, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!editing || onEditModePress) {
                val x = e2.x - e1.x
                val y = e2.y - e1.y
                if (xMove != 0F || abs(x) > moveDeadZone) xMove = x
                if (yMove != 0F || abs(y) > moveDeadZone) yMove = y
                if (hasChanged()) {
                    if (!editing) {
                        onEditModePress = true
                        (parent.parent as GlyphLayer).editGlyph(this)
                    }
                    invalidate()
                }
                return true
            } else if (editing) {
                if (currentPoints.size == 0) currentPoints.add(Point(e1.x, e1.y))
                else {
                    val ep = Point(e2.x, e2.y)

                    if (currentPoints.size == 1) {
                        currentPoints.add(ep)
                    } else {
                        val p = currentPoints[currentPoints.size-2]
                        val xd = ep.x-p.x; val yd = ep.y-p.y
                        val distance = sqrt(xd*xd+yd*yd)

                        if (currentPoints.size == 2) {
                            if (distance > drawnMinRadius) currentPoints.add(ep)
                            else currentPoints[currentPoints.size - 1] = ep
                        } else {
                            val pp = currentPoints[currentPoints.size-3]

                            val a01 = atan2(p.y-pp.y, p.x-pp.x)
                            val a02 = atan2(ep.y-p.y, ep.x-p.x)

                            val adiff = a02-a01
                            val angle = if (abs(adiff) > PI) (adiff - sign(adiff)*2*PI).toFloat() else adiff

                            if ((abs(angle) > drawnAngleLimit || distance > drawnMaxRadius) && distance > drawnMinRadius) {
                                Log.w("flatgame", "that angle was $angle")
                                currentPoints.add(ep)
                            }
                            else currentPoints[currentPoints.size-1] = ep
                        }
                    }
                }
                invalidate()
                return true
            }
            return false
        }

        override fun onLongPress(e: MotionEvent) {
            if (!editing) (parent.parent as GlyphLayer).editGlyph(this)
            else (parent.parent as GlyphLayer).stopEditing(if (hasChanged()) ({(ctx as ClipEditor).modifyLinkSource(index, linkGesture.copy(region = regionFromEdits()))}) else ({}))
        }

        // Double tap gestures

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (!editing && linkDestination is SimpleDestination) {
                ctx as ClipEditor
                val flatgame = ctx.flatGame ?: return false
                val configfile = ctx.configFile ?: return false

                val intent = Intent(ctx, ClipEditor::class.java)
                intent.data = Flatgame.internalClipUri(ctx, flatgame, linkDestination.clip)
                intent.putExtra("cfg", configfile.path)
                intent.putExtra("time", linkDestination.time)
                intent.putExtra("mode", ClipEditor.Mode.PICK_TIME_MODE.ordinal)
                ctx.queryLinkDirect = linkGesture
                ctx.startActivityForResult(intent, ClipEditor.Request.PICK_TIME_DIRECT_LINK_REQUEST.ordinal)
                return true
            }
            return false
        }

        override fun onDoubleTapEvent(e: MotionEvent?): Boolean = false

        override fun onSingleTapConfirmed(e: MotionEvent?): Boolean = false

        // Scale gestures

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            Log.w("flatgame", "scale begin")
            if (!editing || onEditModePress) {
                if (!editing) {
                    onEditModePress = true
                    (parent.parent as GlyphLayer).editGlyph(this)
                }
            }
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            Log.w("flatgame", "scale end")
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            Log.w("flatgame", "scale")
            if (!editing || onEditModePress) scale *= detector.scaleFactor // arrgh love me dead reckoning
            invalidate()
            return true
        }

    }
    class DarkOverlay (ctx: Context, darkness : Float = 0F) : View(ctx) {
        init {
            alpha = darkness
        }
        private val paint = Paint()

        override fun onTouchEvent(event: MotionEvent) : Boolean {
            val p = parent
            p as GlyphLayer
            if (p.editing != null) {
                p.stopEditing({})
                return true
            }
            return false
        }

        init {
            paint.color = ContextCompat.getColor(ctx, R.color.justBlack)
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0F, 0F, canvas.width.toFloat(), canvas.height.toFloat(), paint)
        }
    }
}

class DirectLinkAdapter(private val clipEditor: ClipEditor, handler: Handler, private val dialog: DialogInterface, private val linkSource: LinkSource) :
    DirectoryAdapter<File>(handler, clipEditor.clipsDir!!, {dir -> dir.listFiles().toList()}) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView as? TextView ?: TextView(clipEditor)
        val strip = items[position].name.dropLastWhile { it != '.' }
        if (strip.length > 1) {
            view.text = strip.dropLast(1)
        } else {
            view.text = items[position].name
        }
        view.textSize = 28F
        view.setPadding(30, 30, 30, 30)
        view.setOnClickListener {
            val intent = Intent(clipEditor, ClipEditor::class.java)
            intent.data = Uri.fromFile(items[position])
            intent.putExtra("mode", ClipEditor.Mode.PICK_TIME_MODE.ordinal)
            intent.putExtra("cfg", clipEditor.configFile!!.path)

            clipEditor.queryLinkDirect = linkSource

            clipEditor.startActivityForResult(intent, ClipEditor.Request.PICK_TIME_DIRECT_LINK_REQUEST.ordinal)

            dialog.dismiss()
        }

        return view
    }

}