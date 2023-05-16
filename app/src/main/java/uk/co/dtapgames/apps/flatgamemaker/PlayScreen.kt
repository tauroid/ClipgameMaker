package uk.co.dtapgames.apps.flatgamemaker

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.media.MediaPlayer
import android.net.Uri
import android.os.AsyncTask
import android.os.Bundle
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.*
import android.widget.RelativeLayout
import android.widget.Toast
import kotlinx.android.synthetic.main.playscreen.*
import java.io.File
import java.util.*
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Created by adam on 11/02/18.
 */

class PlayScreen : AppCompatActivity() {
    var flatGame: Flatgame? = null

    val clipNames = mutableListOf<String>()
    //val clipViews = mutableMapOf<String, ClipView>()
    var surfaceView: PlayClipView? = null
    val mediaPlayers = mutableMapOf<String, MediaPlayer>()

    var playClock: PlayClock? = null

    var controlLayer: PlayControlLayer? = null

    var configFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val configpath = intent.getStringExtra("cfg")

        if (configpath == null) {
            finish(); return
        }

        val configfile = File(configpath)
        configFile = configfile

        val flatgame = Flatgame.read(configfile)
        flatGame = flatgame

        if (flatgame == null) { finish(); return }

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                        WindowManager.LayoutParams.FLAG_FULLSCREEN)

        setContentView(R.layout.playscreen)

        surfaceView = PlayClipView(this)
        playscreen.addView(surfaceView, RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT))

        if (flatgame.startFrame == null) {
            Toast.makeText(this, R.string.no_start_frame, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val clipSet = mutableSetOf<String>()
        clipSet.addAll(flatgame.clips.keys)
        clipSet.addAll(flatgame.clips.map{ (_, clip) ->
            clip.links.map{ link ->
                when (link.destination) {
                    is SimpleDestination -> setOf(link.destination.clip)
                    is RandomDestination -> link.destination.dests.map { dest -> dest.clip }.toSet()
                }
            }.flatten()
        }.flatten())
        clipSet.add(flatgame.startFrame.clip)
        clipNames.addAll(clipSet)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStart() {
        super.onStart()

        val flatgame = flatGame ?: return
        val startframe = flatgame.startFrame ?: return
        val surfaceview = surfaceView ?: return
        val configfile = configFile ?: return

        mediaPlayers.clear()

        surfaceview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (playClock?.isCancelled == false) playClock?.cancel(true)
                controlLayer?.surfacedestroyed = true
            }

            override fun surfaceCreated(holder: SurfaceHolder) {
                clipNames.forEach{ name ->
                    val mediaplayer = MediaPlayer()
                    mediaplayer.setDataSource(this@PlayScreen, Uri.fromFile(File(File(configfile.canonicalFile.parentFile, Flatgame.clipsdirname), name)))
                    mediaplayer.prepare()
                    mediaplayer.setOnPreparedListener {
                        mediaPlayers[name] = mediaplayer
                        if (mediaPlayers.size == clipNames.size) {
                            val controllayer = PlayControlLayer(this@PlayScreen, flatgame, surfaceview, mediaPlayers, startframe)
                            controlLayer = controllayer
                            playscreen.addView(
                                    controllayer,
                                    RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT)
                            )
                            playClock = PlayClock()
                            playClock?.execute(controllayer)
                        }
                        playscreen.requestLayout()
                    }
                    mediaplayer.isLooping = true
                }
            }

        })

    }

    override fun onStop() {
        super.onStop()

        if (playClock?.isCancelled == false) playClock?.cancel(true)

        controlLayer?.playersreleased = true
        mediaPlayers.forEach{ (_, mediaplayer) -> mediaplayer.release() }
    }
}

class PlayControlLayer(private val ctx: Context, private val flatgame: Flatgame,
                       private val surfaceView: PlayClipView,
                       private val mediaPlayers: Map<String, MediaPlayer>,
                       startFrame: StartFrame) : View(ctx), GestureDetector.OnGestureListener {
    private var currentClip: String? = null
    private var currentMediaPlayer: MediaPlayer? = null

    private val upcomingUpLinks = mutableListOf<Link>()
    private val upcomingDownLinks = mutableListOf<Link>()

    private val upcomingStopFrames = mutableListOf<StopFrame>()

    private val activeLinks = mutableListOf<Link>()

    private val seekingTo = mutableMapOf<String, Long>()

    private val gestureDetector = GestureDetector(ctx, this)

    private var lastTime = 0L

    var surfacedestroyed = false
    var playersreleased = false

    init {
        // Use startFrame by default
        goToClip(startFrame.clip, startFrame.time)
    }

    private fun repopulate() {
        val time = currentMediaPlayer!!.currentPosition.toLong()*1000
        val clip = currentClip!!

        upcomingUpLinks.clear()
        if (flatgame.linksRising[clip] != null) {
            upcomingUpLinks.addAll(
                    flatgame.linksRising[clip]!!
                            .map { id -> flatgame.clips[clip]!!.links[id] }
                            .filter { link ->
                                when (link.source) {
                                    is LinkGesture -> link.source.period.start >= time
                                    is LinkFrame -> link.source.time >= time
                                }
                            }
            )
        }

        upcomingDownLinks.clear()
        if (flatgame.linksFalling[clip] != null) {
            upcomingDownLinks.addAll(
                    flatgame.linksFalling[clip]!!
                            .map { id -> flatgame.clips[clip]!!.links[id] }
                            .filter { link ->
                                when (link.source) {
                                    is LinkGesture -> link.source.period.end >= time
                                    is LinkFrame -> false // it won't have the chance to fall
                                }
                            }
            )
        }

        upcomingStopFrames.clear()
        upcomingStopFrames.addAll(
                flatgame.stopFrames.filter{ stopFrame -> stopFrame.clip == clip && stopFrame.time >= time }.sortedByDescending { it.time }
        )

        activeLinks.clear()
        if (flatgame.clips[clip] != null) {
            activeLinks.addAll(
                    flatgame.clips[clip]!!.links.filter { link ->
                        when (link.source) {
                            is LinkGesture -> link.source.period.start <= time && link.source.period.end >= time
                            is LinkFrame -> false
                        }
                    }
            )
        }

        seekDownstreamMediaPlayers()
    }

    private fun seekDownstreamMediaPlayers() {
        val activeDests = activeLinks.map{ link -> getLinkDests(link) }.flatten()
        val downstreamDests = upcomingUpLinks.map{ link -> getLinkDests(link) }.flatten()

        val seekDests = activeDests.union(downstreamDests)

        seekDests.forEach{ dest ->
            if (dest.clip != currentClip) {
                mediaPlayers[dest.clip]!!.seekTo((dest.time / 1000).toInt())
                seekingTo[dest.clip] = dest.time
            }
        }
    }

    private fun getLinkDests(link: Link) : List<SimpleDestination> {
        return when (link.destination) {
            is SimpleDestination -> mutableListOf(link.destination)
            is RandomDestination -> link.destination.dests
        }
    }

    fun update() {
        if (surfacedestroyed || playersreleased) return

        val time = currentMediaPlayer!!.currentPosition.toLong()*1000

        if (time - lastTime < 0L) repopulate()
        lastTime = time

        if (upcomingStopFrames.size > 0 && upcomingStopFrames.last().time < time) {
            (ctx as Activity).finish()
            return
        }

        var changedLinks = false

        while (upcomingDownLinks.size > 0 && (upcomingDownLinks.last().source as LinkGesture).period.end < time) {
            activeLinks.remove(upcomingDownLinks.last())
            upcomingDownLinks.removeAt(upcomingDownLinks.size-1)
            changedLinks = true
        }

        while (upcomingUpLinks.size > 0 && getLinkUpTime(upcomingUpLinks.last()) <= time) {
            val last = upcomingUpLinks.last()
            when (last.source) {
                is LinkGesture -> {
                    activeLinks.add(last)
                    changedLinks = true
                    upcomingUpLinks.removeAt(upcomingUpLinks.size-1)
                }
                is LinkFrame -> {
                    jump(last.destination)
                    return
                }
            }
        }

        if (changedLinks) seekDownstreamMediaPlayers()
    }

    fun getLinkUpTime(link: Link) : Long {
        return when (link.source) {
            is LinkGesture -> link.source.period.start
            is LinkFrame -> link.source.time
        }
    }

    fun jump(destination: LinkDestination) {
        val dest = when (destination) {
            is SimpleDestination -> destination
            is RandomDestination -> destination.dests[Random().nextInt(destination.dests.size)]
        }

        goToClip(dest.clip, dest.time)
    }

    fun goToClip(clip: String, time: Long) {
        currentMediaPlayer?.pause()
        currentMediaPlayer?.setDisplay(null)

        currentClip = clip
        val mp = mediaPlayers[clip] ?: return
        currentMediaPlayer = mp

        if (!surfaceView.holder.surface.isValid) return
        mp.setDisplay(surfaceView.holder)
        val seekingto = seekingTo[clip]
        if (seekingto == null || seekingto != time) {
            Log.w("flatgame", "have to seek")
            mp.seekTo((time/1000).toInt())
        }

        mp.start()

        repopulate()

        surfaceView.holder.setFixedSize(mp.videoWidth, mp.videoHeight)
        surfaceView.ratio = mp.videoHeight.toFloat()/mp.videoWidth.toFloat()

        if (parent != null && parent.parent != null) {
            val (mwidth, mheight) = getWidthHeight((parent.parent as View).width, (parent.parent as View).height)

            surfaceView.layoutParams = RelativeLayout.LayoutParams(mwidth, mheight)
            layoutParams = RelativeLayout.LayoutParams(mwidth, mheight)
        }

        //requestLayout()
    }

    fun getWidthHeight(specwidth: Int, specheight: Int) : Pair<Int, Int> {
        val mp = currentMediaPlayer!!

        val vwidth = mp.videoWidth; val vheight = mp.videoHeight

        val tooHigh = vheight * specwidth > specheight * vwidth

        val mwidth = if (tooHigh) ((vwidth * specheight).toFloat() / vheight).toInt() else specwidth
        val mheight = if (tooHigh) specheight else ((vheight * specwidth).toFloat() / vwidth).toInt()

        return Pair(mwidth, mheight)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val specwidth = MeasureSpec.getSize(widthMeasureSpec)
        val specheight = MeasureSpec.getSize(heightMeasureSpec)

        val (mwidth, mheight) = getWidthHeight(specwidth, specheight)

        setMeasuredDimension(mwidth, mheight)
    }

    override fun onTouchEvent(event: MotionEvent) : Boolean {
        return gestureDetector.onTouchEvent(event)
    }

    override fun onShowPress(e: MotionEvent?) {}

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        activeLinks.forEach{ link ->
            link.source as LinkGesture
            when (link.source.gesture) {
                is TapGesture -> {
                    when (link.source.region) {
                        is CircleRegion -> {
                            val largestdimension = max(width, height)

                            val viewradius = largestdimension * link.source.region.radius
                            val xdiff = e.x - link.source.region.origin.x * width
                            val ydiff = e.y - link.source.region.origin.y * height
                            if (sqrt(xdiff*xdiff + ydiff*ydiff) <= viewradius) {
                                jump(link.destination)
                                return true
                            }
                        }
                        is DrawnRegion -> {
                            if (link.source.region.isInRegion(Point(e.x/width, e.y/height))) {
                                jump(link.destination)
                                return true
                            }
                        }
                    }
                }
                else -> {}
            }
        }
        return false
    }

    override fun onDown(e: MotionEvent?): Boolean = true

    override fun onFling(e1: MotionEvent, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
        val flingx = e2.x-e1.x; val flingy = e2.y-e1.y

        val possibleflings = activeLinks.filter{ link ->
            link.source as LinkGesture
            when (link.source.gesture) {
                is FlingGesture -> {
                    when (link.source.region) {
                        is CircleRegion -> {
                            val largestdimension = max(width, height)

                            val viewradius = largestdimension * link.source.region.radius
                            val xdiff = e1.x - link.source.region.origin.x * width
                            val ydiff = e1.y - link.source.region.origin.y * height

                            sqrt(xdiff*xdiff + ydiff*ydiff) <= viewradius && link.source.gesture.acceptsFling(flingx, flingy)
                        }
                        is DrawnRegion -> link.source.region.isInRegion(Point(e1.x/width, e1.y/height)) && link.source.gesture.acceptsFling(flingx, flingy)
                    }
                }
                else -> false
            }
        }

        val jumplink = possibleflings.maxBy { link ->
            link.source as LinkGesture
            link.source.gesture as FlingGesture
            link.source.gesture.flingCloseness(flingx, flingy)
        }

        return if (jumplink != null) {
            jump(jumplink.destination); true
        } else false
    }

    override fun onScroll(e1: MotionEvent?, e2: MotionEvent?, distanceX: Float, distanceY: Float): Boolean = false

    override fun onLongPress(e: MotionEvent?) {}


    private val paint = Paint()

    init { paint.color = ContextCompat.getColor(ctx, R.color.clearBlue) }
}

class PlayClock : AsyncTask<PlayControlLayer, PlayControlLayer, Unit>() {
    override fun doInBackground(vararg controlLayer: PlayControlLayer) {
        while (true) {
            if (isCancelled) return
            Thread.sleep(100)
            publishProgress(controlLayer[0])
        }
    }

    override fun onProgressUpdate(vararg controlLayer: PlayControlLayer) {
        controlLayer[0].update()
    }
}

class PlayClipView(ctx: Context) : SurfaceView(ctx) {
    var ratio = 1F

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val specwidth = MeasureSpec.getSize(widthMeasureSpec)
        val specheight = MeasureSpec.getSize(heightMeasureSpec)

        val tooHigh = ratio > specheight.toFloat()/specwidth.toFloat()

        val mwidth = if (tooHigh) (specheight/ratio).toInt() else specwidth
        val mheight = if (tooHigh) specheight else (specwidth*ratio).toInt()

        setMeasuredDimension(mwidth, mheight)
    }
}