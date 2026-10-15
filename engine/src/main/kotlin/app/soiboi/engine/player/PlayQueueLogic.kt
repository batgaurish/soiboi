package app.soiboi.engine.player

import kotlin.random.Random

/** Where the currently playing item ends up after a move, and whether the song was not already queued. */
class QueueInsertResult(val currentIndex: Int, val wasNewlyInserted: Boolean)

@kotlinx.serialization.Serializable
enum class PlayMode { Order, Shuffle, Repeat }

/** Index bookkeeping for play-queue reordering: it only touches the queue list and the current index, so it needs no player. */
object PlayQueueLogic {
    /** Moves [id] to play right after [currentIndex], or inserts it there. Null if it is already the playing item. */
    fun <T> insertNext(queue: MutableList<T>, currentIndex: Int, item: T): QueueInsertResult? {
        val at = queue.indexOf(item)
        if (at != -1) {
            if (at == currentIndex) return null
            queue.removeAt(at)
            return if (at < currentIndex) { queue.add(currentIndex, item); QueueInsertResult(currentIndex - 1, false) }
            else { queue.add(currentIndex + 1, item); QueueInsertResult(currentIndex, false) }
        }
        if (queue.isEmpty()) queue.add(item) else queue.add(currentIndex + 1, item)
        return QueueInsertResult(currentIndex, true)
    }

    /** Moves [item] to the end of the queue, or appends it. Null if it is already the playing item. */
    fun <T> addLast(queue: MutableList<T>, currentIndex: Int, item: T): QueueInsertResult? {
        val at = queue.indexOf(item)
        if (at != -1) {
            if (at == currentIndex) return null
            val newIndex = if (at < currentIndex) currentIndex - 1 else currentIndex
            queue.removeAt(at); queue.add(item)
            return QueueInsertResult(newIndex, false)
        }
        queue.add(item)
        return QueueInsertResult(currentIndex, true)
    }

    /** Whether the queue is in shuffle order, so songs added to it also belong in the pre-shuffle queue. Repeat keeps the order of the mode it replaced. */
    fun isShuffled(mode: PlayMode, modeBeforeRepeat: PlayMode) = mode == PlayMode.Shuffle || (mode == PlayMode.Repeat && modeBeforeRepeat == PlayMode.Shuffle)

    /** [queue] shuffled with the song at [currentIndex] kept first, so the track playing now carries on and everything after it is random. */
    fun <T> shuffledAround(queue: List<T>, currentIndex: Int, random: Random = Random): List<T> {
        val others = queue.toMutableList().also { it.removeAt(currentIndex) }.shuffled(random)
        return listOf(queue[currentIndex]) + others
    }

    /** The index to start a new queue of [length] songs at: the top in order, anywhere when shuffling. */
    fun startIndex(mode: PlayMode, length: Int, random: Random = Random) = if (mode == PlayMode.Order) 0 else random.nextInt(length)

    fun nextIndex(currentIndex: Int, length: Int) = (currentIndex + 1) % length
    fun previousIndex(currentIndex: Int, length: Int) = (currentIndex + length - 1) % length

    /** [queue] with each id swapped for its current copy in [valid], dropping ids the library no longer has. Used after a rescan. */
    fun remap(queue: List<String>, valid: Set<String>) = queue.filter { it in valid }
}
