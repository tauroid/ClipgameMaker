package uk.co.dtapgames.apps.flatgamemaker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RadialGradient
import android.net.Uri
import android.os.Parcelable
import android.util.Log
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.module.kotlin.*
import java.io.*
import kotlin.math.*

/**
 * Created by adam on 29/01/18.
 */

data class Flatgame (val name: String, val version: String, val draft: Boolean, val startFrame: StartFrame?, val stopFrames: MutableList<StopFrame>, val clips: MutableMap<String, Clip>) {
    @JsonIgnore val linksRising : Map<String, List<Int>> = clips.mapValues { (_, clip) -> getLinksRising(clip) } // rising edges (links becoming active)
    @JsonIgnore val linksFalling : Map<String, List<Int>> = clips.mapValues { (_, clip) -> getLinksFalling(clip) } // falling edges (links falling inactive)

    companion object {
        private fun getLinksRising (clip: Clip) : List<Int> {
            return clip.links.mapIndexed { index, link ->
                when (link.source) {
                    is LinkGesture -> Pair(index, link.source.period.start)
                    is LinkFrame -> Pair(index, link.source.time)
                }
            }.sortedByDescending { (_, time) -> time }.map { (index, _) -> index } // descending because fuck you!!!  ... i mean because then removal is O(1)
        }
        private fun getLinksFalling (clip: Clip) : List<Int> {
            return clip.links.mapIndexedNotNull { index, link ->
                when (link.source) {
                    is LinkGesture -> Pair(index, link.source.period.end)
                    is LinkFrame -> null
                }
            }.sortedByDescending { (_, time) -> time }.map { (index, _) -> index }
        }

        /* retired for now
        fun readInternal (ctx: Context, name: String, version: String) : Flatgame? =
                read(configFile(ctx, name, version))
         */

        fun read (config: File) : Flatgame? {
            if (!config.exists()) return null

            val fin = FileInputStream(config)
            val flatgame = read(fin)
            fin.close()

            return flatgame
        }

        fun read (config: InputStream) : Flatgame? {
            val mapper = jacksonObjectMapper()

            mapper.enableDefaultTyping()

            val json = config.reader(Charsets.UTF_8).readText()

            val flatgame : Flatgame? = mapper.readValue(json, Flatgame::class.java)

            return if (flatgame != null) flatgame else null
        }

        fun writeInternal (ctx: Context, flatgame: Flatgame) {
            val configFile = configFile(ctx, flatgame)

            if (!configFile.parentFile.exists()) configFile.parentFile.mkdirs()

            val fout = FileOutputStream(configFile)
            write(fout, flatgame)
            fout.close()
        }

        fun write (config: OutputStream, flatgame: Flatgame) {
            // It's possible that the flatgame received refers to clips which do not exist (in the clips
            // directory relative to the given file)
            // Unfortunately I don't care and the whole thing can just break when it tries to read them
            // I'll try to not make it possible to generate an invalid config and it's the apps
            // private files so it should be fine

            val mapper = jacksonObjectMapper()

            mapper.enableDefaultTyping()

            val json = mapper.writeValueAsString(flatgame)

            Log.w("flatgame", "writing $json")

            config.write(json.toByteArray(Charsets.UTF_8))
        }

        fun getConfigs(directory: File) : List<Flatgame> {
            return directory.listFiles{_, name -> name.endsWith(Flatgame.confsuffix)}.toList()
                    .mapNotNull{cfg -> Flatgame.read(cfg)}
        }

        fun internalGamesDir(ctx: Context) = File(ctx.filesDir, gamesdir)

        fun internalGamesList(ctx: Context) = internalGamesDir(ctx).list().toList()

        fun internalClipsDir(ctx: Context, flatgame: Flatgame) = File(File(internalGamesDir(ctx), flatgame.name), clipsdirname)

        fun internalClipUri(ctx: Context, flatgame: Flatgame, name: String) : Uri = Uri.fromFile(File(internalClipsDir(ctx, flatgame), name))

        fun externalGamesDir(ctx: Context) = File(ctx.filesDir, extgamesdir)

        fun externalGamesList(ctx: Context) = externalGamesDir(ctx).list().toList()

        private fun configFile(ctx: Context, name: String, version: String) =
                File(File(internalGamesDir(ctx), name), "$version$confsuffix")
        fun configFile(ctx: Context, flatgame: Flatgame) =
                configFile(ctx, flatgame.name, flatgame.version)

        fun containsClip(flatgame: Flatgame, clipname: String) = flatgame.clips[clipname] != null

        fun clipIsUsed(flatgame: Flatgame, name: String) : Boolean {
            val clip = flatgame.clips[name]

            return (clip != null && clip.links.size > 0) || flatgame.clips.any{ (_, clip) ->
                clip.links.any{ link ->
                    when (link.destination) {
                        is SimpleDestination -> link.destination.clip == name
                        is RandomDestination -> link.destination.dests.any { dest ->
                            dest.clip == name
                        }
                    }
                }
            }
        }

        fun defaultPeriodFromTime(time: Long) = Period(time - defaultPeriod/2, time + defaultPeriod/2)

        fun renameClip(flatgame: Flatgame, oldname: String, newname: String) : Flatgame {
            val clip = flatgame.clips[oldname]

            if (clip != null) {
                flatgame.clips.remove(oldname)
                flatgame.clips[newname] = clip
            }

            return flatgame.copy(
                    startFrame =
                    if (flatgame.startFrame != null && flatgame.startFrame.clip == oldname)
                        flatgame.startFrame.copy(clip = newname)
                    else flatgame.startFrame,
                    stopFrames =
                    flatgame.stopFrames.map{ stopFrame ->
                        if (stopFrame.clip == oldname) stopFrame.copy(clip = newname) else stopFrame
                    }.toMutableList(),
                    clips =
                    flatgame.clips.mapValues{ (_, clip) ->
                        Clip(clip.links.map{ link ->
                            when (link.destination) {
                                is SimpleDestination -> if (link.destination.clip == oldname) Link(link.source, link.destination.copy(clip = newname)) else link
                                is RandomDestination -> Link(link.source, link.destination.copy(dests = link.destination.dests.map{ dest ->
                                    if (dest.clip == oldname) dest.copy(clip = newname) else dest
                                }))
                            }
                        }.toMutableList())
                    }.toMutableMap()
            )
        }

        fun deleteClip(flatgame: Flatgame, name: String) : Flatgame {
            flatgame.clips.remove(name)

            return flatgame.copy(
                    startFrame =
                    if (flatgame.startFrame != null && flatgame.startFrame.clip == name) null
                    else flatgame.startFrame,
                    stopFrames =
                    flatgame.stopFrames.mapNotNull{ stopFrame ->
                        if (stopFrame.clip == name) null else stopFrame
                    }.toMutableList(),
                    clips =
                    flatgame.clips.mapValues{ (_, clip) ->
                        Clip(clip.links.mapNotNull{ link ->
                            when (link.destination) {
                                is SimpleDestination -> if (link.destination.clip == name) null else link
                                is RandomDestination -> Link(link.source, link.destination.copy(dests = link.destination.dests.mapNotNull{ dest ->
                                    if (dest.clip == name) null else dest
                                }))
                            }
                        }.toMutableList())
                    }.toMutableMap()
            )
        }

        fun linesCentroid(points: List<Point>) : Point {
            var xsum = 0F
            var ysum = 0F
            var linesum = 0F

            for (i in 0.until(points.size)) {
                val lastpoint = if (i>0) points[i-1] else points[points.size-1]
                val thispoint = points[i]

                val linex = thispoint.x - lastpoint.x
                val liney = thispoint.y - lastpoint.y

                val linelength = sqrt(linex*linex + liney*liney)

                xsum += (thispoint.x + lastpoint.x) * linelength/2F
                ysum += (thispoint.y + lastpoint.y) * linelength/2F
                linesum += linelength
            }

            return Point(xsum/linesum, ysum/linesum)
        }

        const val gamesdir = "flatgames"
        const val extgamesdir = "flatgames-ext"
        const val confsuffix = ".cfg"
        const val clipgamesuffix = ".clipgame"
        const val publicdirname = "Clipgames"
        const val clipsdirname = "clips"
        const val gestureRadius = 0.2F
        const val defaultPeriod = 1000000L
    }
}

data class StartFrame (val time: Long, val clip: String)
data class StopFrame (val time: Long, val clip: String)

data class Link (val source: LinkSource, val destination: LinkDestination)

sealed class LinkSource

data class LinkGesture (
        val gesture: Gesture,
        val region: LinkRegion,
        val period: Period
) : LinkSource()

data class LinkFrame (val time: Long) : LinkSource()

data class Clip (val links : MutableList<Link>)

sealed class LinkDestination
data class SimpleDestination (val clip: String, val time: Long) : LinkDestination()
data class RandomDestination (val dests: List<SimpleDestination>) : LinkDestination()

sealed class Gesture
data class TapGesture (val point: Point) : Gesture()
data class FlingGesture (val origin: Point, val angle: Float) : Gesture() {
    fun acceptsFling(x: Float, y: Float) : Boolean {
        return flingCloseness(x,y) > 0.2
    }

    fun flingCloseness(x: Float, y: Float) : Float {
        val vlen = sqrt(x*x + y*y)
        return (x*cos(angle) + y*sin(angle))/vlen
    }
}

sealed class LinkRegion
data class CircleRegion (val origin: Point, val radius: Float) : LinkRegion()
data class DrawnRegion (val points: MutableList<Point>) : LinkRegion() {
    val centroid = Flatgame.linesCentroid(points)

    fun isInRegion (point: Point) : Boolean {
        var anglesum = 0F

        for (i in 0.until(points.size)) {
            val lastpoint = if (i>0) points[i-1] else points[points.size-1]
            val thispoint = points[i]

            val lastlegx = lastpoint.x - point.x
            val lastlegy = lastpoint.y - point.y
            val thislegx = thispoint.x - point.x
            val thislegy = thispoint.y - point.y

            val lastnorm = sqrt(lastlegx*lastlegx + lastlegy*lastlegy)
            val thisnorm = sqrt(thislegx*thislegx + thislegy*thislegy)

            val normcross = (lastlegx*thislegy - lastlegy*thislegx)/lastnorm/thisnorm
            val normdot = (lastlegx*thislegx + lastlegy*thislegy)/lastnorm/thisnorm

            val asinangle = asin(normcross)
            val acosangle = acos(normdot)

            Log.w("flatgame", "normcross is $normcross, normdot is $normdot")
            Log.w("flatgame", "asinangle is $asinangle, acosangle is $acosangle")

            val angle = sign(asinangle)*acosangle

            anglesum += angle
        }

        Log.w("flatgame", "anglesum is $anglesum")

        return abs(anglesum) > 0.001
    }
}

data class Period (val start: Long, val end: Long)
data class Point (val x: Float, val y: Float)

data class VarPoint (var x: Float, var y: Float)