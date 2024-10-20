/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.buffer;

import io.netty.util.internal.LongCounter;
import io.netty.util.internal.PlatformDependent;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.PriorityQueue;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Description of algorithm for PageRun/PoolSubpage allocation from PoolChunk
 *
 * Notation: The following terms are important to understand the code
 * > page  - a page is the smallest unit of memory chunk that can be allocated  page 是可以分配的内存块的最小单位
 * > run   - a run is a collection of pages                                     run 是 page 的集合
 * > chunk - a chunk is a collection of runs                                    chunk是 run 的集合
 * > in this code chunkSize = maxPages * pageSize                               chunk大小 = 页数*页大小
 *
 * To begin we allocate a byte array of size = chunkSize
 * Whenever a ByteBuf of given size needs to be created we search for the first position
 * in the byte array that has enough empty space to accommodate the requested size and
 * return a (long) handle that encodes this offset information, (this memory segment is then
 * marked as reserved so it is always used by exactly one ByteBuf and no more)
 * 每当需要创建给定大小的ByteBuf时，我们都会分配一个大小为 chunkSize（16M）的字节数组，然后在字节数组中搜索第一个能够满足请求大小的空白空间位置，
 * 并返回一个(long)句柄来编码此偏移量信息(该内存段随后被标记为reserved，因此它（指这个内存段）总是被一个ByteBuf使用，而不是更多)。
 *
 * For simplicity all sizes are normalized according to {@link PoolArena#size2SizeIdx(int)} method.
 * This ensures that when we request for memory segments of size > pageSize the normalizedCapacity
 * equals the next nearest size in {@link SizeClasses}.
 * 为简单起见，所有大小都根据 PoolArena#size2SizeIdx(int) 方法进行规格化。这确保了当我们请求 size > pageSize 的内存段时，
 * 标准化容量等于{@link SizeClasses}中的下一个最近的大小。
 *
 *
 *  A chunk has the following layout: 一个chunk有如下布局：
 *
 *     /-----------------\
 *     | run             |
 *     |                 |
 *     |                 |
 *     |-----------------|
 *     | run             |
 *     |                 |
 *     |-----------------|
 *     | unalloctated    |
 *     | (freed)         |
 *     |                 |
 *     |-----------------|
 *     | subpage         |
 *     |-----------------|
 *     | unallocated     |
 *     | (freed)         |
 *     | ...             |
 *     | ...             |
 *     | ...             |
 *     |                 |
 *     |                 |
 *     |                 |
 *     \-----------------/
 *
 *
 * handle（句柄）:
 * -------
 * a handle is a long number, the bit layout of a run looks like: 句柄是一个long 数字，一个run的bit布局看起来像这样:
 *
 * oooooooo ooooooos ssssssss ssssssue bbbbbbbb bbbbbbbb bbbbbbbb bbbbbbbb
 *
 * o: runOffset (page offset in the chunk), 15bit             run的偏移量。即chunk中这个run是从第几个page的开始的，15bit。
 * s: size (number of pages) of this run, 15bit               run的大小，实际上就是这个run中页的数量，15bit
 * u: isUsed?, 1bit                                           是否使用，1bit
 * e: isSubpage?, 1bit                                        是否为 Subpage，1bit, 即这个run是否被一个PoolSubpage对象使用,  实际上就是标识run 是否用于 Small 级别内存分配
 * b: bitmapIdx of subpage, zero if it's not subpage, 32bit   subpage的 bitmapIdx，如果不是subpage则为0, 32bit
 *
 * runsAvailMap:
 * ------
 * a map which manages all runs (used and not in used).       管理所有run(已使用和未使用)的map
 * For each run, the first runOffset and last runOffset are stored in runsAvailMap.
 *                                                            对于每一个run，第一个runnoffset（chunk中这个run是从第几个page的开始的）和最后一个runnoffset存储在runsAvailMap中。
 * key: runOffset                                             runsAvailMap中key是这个run的runOffset
 * value: handle                                              runsAvailMap中value是这个run的handle
 *
 * runsAvail:
 * ----------
 * an array of {@link PriorityQueue}.                         一个 PriorityQueue 构成的数组
 * Each queue manages same size of runs.                      每个 PriorityQueue 管理相同大小的run
 * Runs are sorted by offset, so that we always allocate runs with smaller offset.
 *                                                            Run是按偏移量排序的，因此我们总是以较小的偏移量分配run。
 *
 *
 * Algorithm:
 * ----------
 *
 *   As we allocate runs, we update values stored in runsAvailMap and runsAvail so that the property is maintained.
 *                                                             当我们分配run时，我们更新存储在 runsAvailMap 和 runsAvail 中的值，以便维护run属性。
 * Initialization -
 *  In the beginning we store the initial run which is the whole chunk.
 *                                                             在开始时，我们存储了初始化的run，也就是整个chunk。
 *  The initial run:                                           初始的run状态如下：
 *  runOffset = 0                                                  初始的run其偏移量为0，即从第0个page开始
 *  size = chunkSize                                               初始的run的大小为整个chunk的大小，整个chunk就是一个run
 *  isUsed = no                                                    初始的run其标识为未使用
 *  isSubpage = no                                                 非subpage
 *  bitmapIdx = 0                                                  subpage的 bitmapIdx， 非subpage则为0
 *
 *
 * Algorithm: [allocateRun(size)] 下面是PoolChunk中的一些重要方法的算法：
 * ----------
 * 1) find the first avail run using in runsAvails according to size
 *                                                             1) 根据大小找到在 runsAvails 中使用的第一个可用的run
 * 2) if pages of run is larger than request pages then split it, and save the tailing run
 *    for later using                                          2) 如果run的pages大于请求pages(run是以page计数的，所以这里以pages作为单位)，则拆分它，并保存剩下的run以供以后使用
 *
 * Algorithm: [allocateSubpage(size)]
 * ----------
 * 1) find a not full subpage according to size.
 *    if it already exists just return, otherwise allocate a new PoolSubpage and call init()
 *    note that this subpage object is added to subpagesPool in the PoolArena when we init() it
 *                                                             1)根据大小找到一个不完整的 subpage。如果它已经存在只是返回，否则分配一个新的 PoolSubpage 并调用 init()
 *                                                               注意，这个 subpage 对象是在 PoolArena  init() 时添加到 subpagesPool 中的
 * 2) call subpage.allocate()                                  2)调用subpage.allocate()
 *
 * Algorithm: [free(handle, length, nioBuffer)]
 * ----------
 * 1) if it is a subpage, return the slab back into this subpage
 *                                                             1) 如果它是一个subpage，返回slab回到这个subpage
 * 2) if the subpage is not used or it is a run, then start free this run
 *                                                             2) 如果subpage未使用或它是一个run，然后开始释放这个run
 * 3) merge continuous avail runs                              3) 合并连续的 avail run
 * 4) save the merged run                                      4) 保存合并的 run
 *
 * PoolChunck——Netty向OS申请的最小内存
 *
 * 为了减少频繁的向操作系统申请内存的情况，Netty会一次性申请一块较大的内存。而后对这块内存进行管理，每次按需将其中的一部分分配给内存使用者(即ByteBuf)。
 * 这里的内存就是PoolChunk,其大小由ChunkSize决定(默认为16M，即一次向OS申请16M的内存)。
 * 而Jemalloc中，`arena`也是以`chunk`为单位向操作系统申请内存空间，默认为`4MB`
 *
 */
final class PoolChunk<T> implements PoolChunkMetric {
    private static final int SIZE_BIT_LENGTH = 15;
    private static final int INUSED_BIT_LENGTH = 1;
    private static final int SUBPAGE_BIT_LENGTH = 1;
    private static final int BITMAP_IDX_BIT_LENGTH = 32;

    static final int IS_SUBPAGE_SHIFT = BITMAP_IDX_BIT_LENGTH;
    static final int IS_USED_SHIFT = SUBPAGE_BIT_LENGTH + IS_SUBPAGE_SHIFT;
    static final int SIZE_SHIFT = INUSED_BIT_LENGTH + IS_USED_SHIFT;
    static final int RUN_OFFSET_SHIFT = SIZE_BIT_LENGTH + SIZE_SHIFT;

    final PoolArena<T> arena;
    final Object base;
    final T memory;
    final boolean unpooled;

    /**
     * store the first page and last page of each avail run
     * 存储每个可用run的第一页和最后一页.run 是由若干个连续的 page 组成的内存块的代称，可以被 long 型的 handle 表示。随着内存块的分配和回收，PoolChunk 会管理着若干个不连续的 run。
     *
     * LongLongHashMap 是一个特殊的存储 long 原型的 HashMap，底层采用线性探测法。
     * Netty 使用 LongLongHashMap 存储某个 run 的第一个runOffset和handler的映射关系、最后一个runOffset偏移量和handler的映射关系。
     * 至于为什么这么存储，这是为了在向前、向后合并的过程中能通过 pageOffset 偏移量获取句柄值，进而判断是否可以进行向前合并操作。具体通过源码再详细说明。
     *
     */
    private final LongLongHashMap runsAvailMap;

    /**
     * manage all avail runs
     * 一个 PriorityQueue 构成的数组, 每个 PriorityQueue 管理相同大小的run
     *
     * runsAvail中Run是按runOffset升序排序的，因此我们总是从较小的偏移量开始分配run。
     *
     * IntPriorityQueue 是 Netty 内部实现的关于 int 基本类型的优先队列，关于 IntPriorityQueue 和 LongLongHashMap 的动机请看: https://github.com/netty/netty/commit/c41d46111dc37aaf9c8ee7aec162d87221df1d70，
     * 它是基于二叉堆实现。关于 Netty 如何使用二叉树实现优先队列在这里就不详细讲解了，这里我们只关注 IntPriorityQueue 存储着什么，可以用来做些什么?
     * IntPriorityQueue 属于小顶堆(这意味着其中的run是升序排序)，存储 int （非 Integer）型的句柄值，通过 IntPriorityQueue#poll() 方法每次都能获取小顶堆内部的最小的 handle 值。
     * 这表示我们每次申请内存都是从最低位地址开始分配。而在 PoolChunk 内部有一个 IntPriorityQueue[] 数组，所有存储在 IntPriorityQueue 对象的 handle 都表示一个可用的 run，
     * handle 它的默认长度为 40，为什么是 40 会在源码讲解时解释。
     */
    private final IntPriorityQueue[] runsAvail;

    private final ReentrantLock runsAvailLock;

    /**
     * manage all subpages in this chunk
     */
    private final PoolSubpage<T>[] subpages;

    /**
     * Accounting of pinned memory – memory that is currently in use by ByteBuf instances.
     */
    private final LongCounter pinnedBytes = PlatformDependent.newLongCounter();

    private final int pageSize;
    private final int pageShifts;
    private final int chunkSize;

    // Use as cache for ByteBuffer created from the memory. These are just duplicates and so are only a container
    // around the memory itself. These are often needed for operations within the Pooled*ByteBuf and so
    // may produce extra GC, which can be greatly reduced by caching the duplicates.
    //
    // This may be null if the PoolChunk is unpooled as pooling the ByteBuffer instances does not make any sense here.
    // 用作从内存中创建的ByteBuffer的缓存。这些只是重复的，所以只是内存本身的一个容器。
    // 这些通常是PooledByteBuf内部的操作所需要的，因此可能会产生额外的GC，通过缓存重复项可以大大减少。
    // 如果PoolChunk未池化，则此值可能为空，因为池化ByteBuffer实例在这里没有任何意义。
    private final Deque<ByteBuffer> cachedNioBuffers;

    int freeBytes;

    PoolChunkList<T> parent;
    PoolChunk<T> prev;
    PoolChunk<T> next;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    @SuppressWarnings("unchecked")
    PoolChunk(PoolArena<T> arena, Object base, T memory, int pageSize, int pageShifts, int chunkSize, int maxPageIdx) {
        unpooled = false;
        this.arena = arena;
        this.base = base;
        this.memory = memory;
        this.pageSize = pageSize;
        this.pageShifts = pageShifts;
        this.chunkSize = chunkSize;
        freeBytes = chunkSize;

        runsAvail = newRunsAvailqueueArray(maxPageIdx);
        runsAvailLock = new ReentrantLock();
        runsAvailMap = new LongLongHashMap(-1);
        subpages = new PoolSubpage[chunkSize >> pageShifts];

        //insert initial run, offset = 0, pages = chunkSize / pageSize
        int pages = chunkSize >> pageShifts;
        long initHandle = (long) pages << SIZE_SHIFT;
        insertAvailRun(0, pages, initHandle);

        cachedNioBuffers = new ArrayDeque<ByteBuffer>(8);
    }

    /** Creates a special chunk that is not pooled. */
    PoolChunk(PoolArena<T> arena, Object base, T memory, int size) {
        unpooled = true;
        this.arena = arena;
        this.base = base;
        this.memory = memory;
        pageSize = 0;
        pageShifts = 0;
        runsAvailMap = null;
        runsAvail = null;
        runsAvailLock = null;
        subpages = null;
        chunkSize = size;
        cachedNioBuffers = null;
    }

    private static IntPriorityQueue[] newRunsAvailqueueArray(int size) {
        IntPriorityQueue[] queueArray = new IntPriorityQueue[size];
        for (int i = 0; i < queueArray.length; i++) {
            queueArray[i] = new IntPriorityQueue();
        }
        return queueArray;
    }

    /**
     * 更新 {@link PoolChunk#runsAvail} 和 {@link PoolChunk#runsAvailMap} 数据结构
     * @param runOffset 偏移量
     * @param pages     页数量
     * @param handle    句柄值
     */
    private void insertAvailRun(int runOffset, int pages, long handle) {
        // #1 将句柄信息写入对应的小顶堆
        // 根据页数量向下取整，获得「pageIdxFloor」，这个值即将写入对应runsAvail数组索引的值
        int pageIdxFloor = arena.pages2pageIdxFloor(pages);
        IntPriorityQueue queue = runsAvail[pageIdxFloor];
        assert isRun(handle);
        queue.offer((int) (handle >> BITMAP_IDX_BIT_LENGTH));

        // #2 将首页和末页的偏移量和句柄值记录在runsAvailMap对象，待合并run时使用
        //insert first page of run
        insertAvailRun0(runOffset, handle);
        if (pages > 1) {
            //insert last page of run
            // 当页数量超过1时才会记录末页的偏移量和句柄值
            insertAvailRun0(lastPage(runOffset, pages), handle);
        }
    }

    private void insertAvailRun0(int runOffset, long handle) {
        long pre = runsAvailMap.put(runOffset, handle);
        assert pre == -1;
    }

    private void removeAvailRun(long handle) {
        int pageIdxFloor = arena.pages2pageIdxFloor(runPages(handle));
        runsAvail[pageIdxFloor].remove((int) (handle >> BITMAP_IDX_BIT_LENGTH));
        removeAvailRun0(handle);
    }

    private void removeAvailRun0(long handle) {
        int runOffset = runOffset(handle);
        int pages = runPages(handle);
        //remove first page of run
        runsAvailMap.remove(runOffset);
        if (pages > 1) {
            //remove last page of run
            runsAvailMap.remove(lastPage(runOffset, pages));
        }
    }

    private static int lastPage(int runOffset, int pages) {
        return runOffset + pages - 1;
    }

    private long getAvailRunByOffset(int runOffset) {
        return runsAvailMap.get(runOffset);
    }

    @Override
    public int usage() {
        final int freeBytes;
        if (this.unpooled) {
            freeBytes = this.freeBytes;
        } else {
            runsAvailLock.lock();
            try {
                freeBytes = this.freeBytes;
            } finally {
                runsAvailLock.unlock();
            }
        }
        return usage(freeBytes);
    }

    private int usage(int freeBytes) {
        if (freeBytes == 0) {
            return 100;
        }

        int freePercentage = (int) (freeBytes * 100L / chunkSize);
        if (freePercentage == 0) {
            return 99;
        }
        return 100 - freePercentage;
    }

    /**
     * 内存分配。可以完成Small&Normal两种级别的内存分配
     * @param buf           ByteBuf承载对象
     * @param reqCapacity   用户所需真实的内存大小
     * @param sizeIdx       内存大小对应{@link SizeClasses} 数组的索引值
     * @param cache         本地线程缓存
     * @return              {code true}: 内存分配成功，否则内存分配失败
     */
    boolean allocate(PooledByteBuf<T> buf, int reqCapacity, int sizeIdx, PoolThreadCache cache) {
        // long型的handle表示分配成功的内存块的句柄值，它与jemalloc3所表示的含义不一样
        final long handle;
        // #1 当sizeIdx<=38（38是默认值）时，表示当前分配的内存规格是Small
        if (sizeIdx <= arena.smallMaxSizeIdx) {
            final PoolSubpage<T> nextSub;
            // small
            // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
            // This is need as we may add it back and so alter the linked-list structure.
            // 获取 PoolArena 拥有的 PoolSubPage 池的头部，并对其进行同步。这是必要的，因为我们可能会将其添加回去，从而改变链表结构。
            // #1-1 分配Small规格内存块
            //    从「PoolArena」获取索引对应的「PoolSubpage」。
            //    在「SizeClasses」中划分为 Small 级别的一共有 39 个，
            //    所以在 PoolArena#smallSubpagePools数组长度也为39，数组索引与sizeIdx一一对应
            PoolSubpage<T> head = arena.smallSubpagePools[sizeIdx];
            // PoolSubpage 链表是共享变量，需要加锁
            head.lock();
            try {
                nextSub = head.next;
                if (nextSub != head) {
                    assert nextSub.doNotDestroy && nextSub.elemSize == arena.sizeIdx2size(sizeIdx) : "doNotDestroy=" +
                            nextSub.doNotDestroy + ", elemSize=" + nextSub.elemSize + ", sizeIdx=" + sizeIdx;
                    // #1-2 如果能直接从smallSubpagePools找到可以用于分配的PoolSubpage，则直接从 PoolSubpage 中分配内存
                    handle = nextSub.allocate();
                    assert handle >= 0;
                    assert isSubpage(handle);
                    nextSub.chunk.initBufWithSubpage(buf, null, handle, reqCapacity, cache);
                    return true;
                }
                // #1-3 如果没有找到可用的 PoolSubpage，则新建一个 PoolSubpage 对象，并从中分配内存
                handle = allocateSubpage(sizeIdx, head);
                if (handle < 0) {
                    return false;
                }
                assert isSubpage(handle);
            } finally {
                head.unlock();
            }
        } else {
            // normal
            // runSize must be multiple of pageSize
            // #2 分配Normal级别内存块，runSize是pageSize的整数倍
            int runSize = arena.sizeIdx2size(sizeIdx);
            handle = allocateRun(runSize);
            if (handle < 0) {
                return false;
            }
            assert !isSubpage(handle);
        }

        // #3 尝试从cachedNioBuffers缓存中获取ByteBuffer对象并在ByteBuf对象初始化时使用
        ByteBuffer nioBuffer = cachedNioBuffers != null? cachedNioBuffers.pollLast() : null;
        // #4 初始化ByteBuf对象
        initBuf(buf, nioBuffer, handle, reqCapacity, cache);
        // #5 return
        return true;
    }

    /**
     * 从 run 分配若干个 page。
     * 1) 根据大小找到在 runsAvails 中使用的第一个可用的run
     * 2) 如果run的pages大于请求pages(run是以page计数的，所以这里以pages作为单位)，则拆分它，并保存剩下的run以供以后使用
     *
     * @param runSize 规格值，该值是pageSize的整数倍
     */
    private long allocateRun(int runSize) {
        // #1 根据规格值计算所需「page」的数量，这个数量值很重要:
        // 	  我们需要通过page数量获得搜索「LongPriorityQueue」的起始位置，并不是盲目从头开始向下搜索，
        //    这样会导致性能较差，而是在一合理的范围内找到第一个且合适的run
        int pages = runSize >> pageShifts;
        // #2 根据page的数量确定page的起始索引，索引值对应「IntPriorityQueue[] runsAvail」数组起始位置
        // 这里就是前面所说的向上取整，从拥有多一点的空闲页的run中分配准是没错的选择
        int pageIdx = arena.pages2pageIdx(pages);

        // runsAvail 属于并发变量，需要加锁
        runsAvailLock.lock();
        try {
            //find first queue which has at least one big enough run
            // #3 从「LongPriorityQueue[]」数组中找到最合适的run用于当前的内存分配请求。
            // 起始位置为「pageIdx」，并向后遍历直到数组的末尾或找到合适的run
            // 如果没有找到，返回-1
            // queueIdx 即要查找的 LongPriorityQueue 对象在数组中的索引
            int queueIdx = runFirstBestFit(pageIdx);
            if (queueIdx == -1) {
                return -1;
            }

            //get run with min offset in this queue
            // #4 获取「LongPriorityQueue」，该对象包含若干个可用的 run
            IntPriorityQueue queue = runsAvail[queueIdx];
            // #5 从「LongPriorityQueue」小顶堆中获取可用的 run（由handle表示）
            //    小顶堆能保证始终保持从低地址开始分配
            long handle = queue.poll();
            assert handle != IntPriorityQueue.NO_VALUE;
            handle <<= BITMAP_IDX_BIT_LENGTH;
            assert !isUsed(handle) : "invalid handle: " + handle;

            // #5 先将「handle」从该小顶堆中移除，因为我们有可能需要对它进行修改
            removeAvailRun0(handle);

            // #6 可能会把「run」拆分成两部分。为什么说可能呢?因为这个run可能刚好满足此次分配需求，所以不用拆分。
            // 一部分用于当前内存申请。
            // 另一部分则剩余空闲内存块，这一部分则会放到合适的LongPriorityQueue数组中，待下次分配时使用。
            // 返回的 handle 表示当前内存申请的句柄信息
            handle = splitLargeRun(handle, pages);

            // #7 更新剩余空间值
            int pinnedSize = runSize(pageShifts, handle);
            freeBytes -= pinnedSize;
            // #8 返回成功申请的句柄信息
            return handle;
        } finally {
            runsAvailLock.unlock();
        }
    }

    private int calculateRunSize(int sizeIdx) {
        int maxElements = 1 << pageShifts - SizeClasses.LOG2_QUANTUM;
        int runSize = 0;
        int nElements;

        // 用户请求的内存大小
        final int elemSize = arena.sizeIdx2size(sizeIdx);

        //find lowest common multiple of pageSize and elemSize
        // 计算pageSize和elemSize的最小公倍数
        do {
            runSize += pageSize;
            nElements = runSize / elemSize;
        } while (nElements < maxElements && runSize != nElements * elemSize);

        while (nElements > maxElements) {
            runSize -= pageSize;
            nElements = runSize / elemSize;
        }

        assert nElements > 0;
        assert runSize <= chunkSize;
        assert runSize >= elemSize;

        // 返回最小公倍数,也及时本次要从chunk中申请的内存大小
        return runSize;
    }

    // 从pageIdx开始搜索最合适的run用于内存分配
    private int runFirstBestFit(int pageIdx) {
        if (freeBytes == chunkSize) {
            return arena.nPSizes - 1;
        }
        // 比较简单，从pageIdx向后遍历，找到queue!=null且不为空的LongPriorityQueue
        for (int i = pageIdx; i < arena.nPSizes; i++) {
            IntPriorityQueue queue = runsAvail[i];
            if (queue != null && !queue.isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 把「run」拆分成合适的两部分（如果这个run可能刚好满足此次分配需求，不用拆分，修改handle信息后直接返回）
     * 这个方法其实并没有做太多的事情，它会根据所需要的页的数量计算各种所需要的信息，然后通过 toRunHandle() 方法生成 handle 句柄值并返回。其中，有一个比较重要步骤的是更新那两个重要的数据结构。
     * @param handle       run的句柄变更
     * @param needPages    所需要page的数量
     * @return             用于当前分配申请的句柄值
     */
    private long splitLargeRun(long handle, int needPages) {
        assert needPages > 0;

        // #1 获取run管理的空闲的page数量
        int totalPages = runPages(handle);
        assert needPages <= totalPages;

        // #2 计算剩余数量（总数-需要数量）
        int remPages = totalPages - needPages;

        // #3 如果还有剩余，需要重新生成run（由handle具象化）并写入两个重要的数据结构中
        // 一个是 LongLongHashMap runsAvailMap，另一个是 LongPriorityQueue[] runsAvail;
        if (remPages > 0) {
            // #3-1 获取偏移量
            int runOffset = runOffset(handle);

            // #3-2 剩余空闲页偏移量=旧的偏移量+分配页数
            // keep track of trailing unused pages for later use
            int availOffset = runOffset + needPages;
            // #3-3 根据偏移量、页数量以及isUsed状态生成新的句柄变量，这个变量表示一个全新未使用的run
            long availRun = toRunHandle(availOffset, remPages, 0);
            // #3-4 更新两个重要的数据结构
            insertAvailRun(availOffset, remPages, availRun);

            // #3-5 生成用于此次分配的句柄变量
            // not avail
            return toRunHandle(runOffset, needPages, 1);
        }

        // #4 恰好满足，只需把handle的isUsed标志位置为1
        //mark it as used
        handle |= 1L << IS_USED_SHIFT;
        // #5 大功告成，返回
        return handle;
    }

    /**
     * Create / initialize a new PoolSubpage of normCapacity. Any PoolSubpage created / initialized here is added to
     * subpage pool in the PoolArena that owns this PoolChunk.
     * 1)根据大小找到一个不完整的 subpage。如果它已经存在只是返回，否则分配一个新的 PoolSubpage 并调用 init()
     *  注意，这个 subpage 对象是在 PoolArena  init() 时添加到 subpagesPool 中的
     * 2)调用subpage.allocate()
     *
     * @param sizeIdx sizeIdx of normalized size 对应{@link SizeClasses} 索引值
     * @param head head of subpages
     *
     * @return index in memoryMap
     */
    private long allocateSubpage(int sizeIdx, PoolSubpage<T> head) {
        //allocate a new run
        // #2 获取拆分规格值和pageSize的最小公倍数, 也即是本次要从chunk中申请的内存大小，拆分规格值即用户请求的内存大小靠拢到标准规格的大小
        int runSize = calculateRunSize(sizeIdx);
        //runSize must be multiples of pageSize
        // #3 申请若干个page，申请到的page数量是拆分规格值和pageSize的最小公倍数。比如申请了30kb, pagesize是4kb, 则这里实际会申请的内存大小是60kb, 也即是15个page
        long runHandle = allocateRun(runSize);
        if (runHandle < 0) {
            return -1;
        }

        // runOffset是根据handle得到的run在当前chunk中的偏移量
        int runOffset = runOffset(runHandle);
        assert subpages[runOffset] == null;
        // 拆分规格值, 即用户请求的内存大小靠拢到标准规格的大小
        int elemSize = arena.sizeIdx2size(sizeIdx);

        // #4 创建一个新的PoolSubpage对象
        PoolSubpage<T> subpage = new PoolSubpage<T>(head, this, pageShifts, runOffset,
                runSize(pageShifts, runHandle), elemSize);

        // #5 由PoolChunk记录新创建的PoolSubpage，数组索引值是首页的偏移量，这个值是唯一的，也是记录在句柄值中
        // 因此，在归还内存时会通过句柄值找到对应的PoolSubpge对象
        subpages[runOffset] = subpage;
        // #6 委托PoolSubpage分配内存
        return subpage.allocate();
    }

    /**
     * Free a subpage or a run of pages When a subpage is freed from PoolSubpage, it might be added back to subpage pool
     * of the owning PoolArena. If the subpage pool in PoolArena has at least one other PoolSubpage of given elemSize,
     * we can completely free the owning Page so it is available for subsequent allocations
     * 1) 如果它是一个subpage，返回slab回到这个subpage
     * 2) 如果subpage未使用或它是一个run，然后开始释放这个run
     * 3) 合并连续的 avail run
     * 4) 保存合并的 run
     *
     * 这是对 subpage 和 run 回收的核心方法。
     * 对 subpage 回收是先回收到 PoolArena 对象的 subpage pool 池中（subpage pool指的是PoolArena的smallSubpagePools数组中某个规格的链表），
     * 如果发现此时的 PoolSubpage 已经没有被任何对象使用（numAvail == maxNumElems），
     * 它首先会从 subpage pool 池中移出，然后再按照 run 策略回收（因为此刻的 handle 记录着偏移量和 page 数量，所以完全有足够的回收信息）。
     *
     * 对于 run 回收，第一步会尝试不断向前合并相邻的空闲的 run，这一步会利用 runAvailMap 快速定位合适的 run，
     * 若合并成功，会重新生成 handle 句柄值，接着再向后不断合并相邻的空闲的 run 并得到新的 handle，最后再更新 {@link PoolChunk#runsAvail} 和 {@link PoolChunk#runsAvailMap} 两个数据结构，
     * 这样就完成了一次 run 的回收。
     *
     * @param handle handle to free
     */
    void free(long handle, int normCapacity, ByteBuffer nioBuffer) {
        // #1 回收subpage
        if (isSubpage(handle)) {
            // #1-3 获取此handle在当前chunk中的subpages数组中所在的位置
            int sIdx = runOffset(handle);
            // #1-4 通过偏移量定位 PoolChunk 内部的 PoolSubpage，而这个PoolSubpage只属于PoolChunk
            PoolSubpage<T> subpage = subpages[sIdx];
            assert subpage != null;
            // #1-2 获取此subpage在arena的smallSubpagePools数组中管理的头结点PoolSubpage对象。
            // 所以这里实际上根据要释放的 handle找到了这个handler对应的subpage对象，然后根据该subpage对象定位到它在PoolArena中的位置
            PoolSubpage<T> head = subpage.chunk.arena.smallSubpagePools[subpage.headIndex];
            // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
            // This is need as we may add it back and so alter the linked-list structure.
            // head是一个共享变量，需要加锁（可能删除或修改）
            head.lock();
            try {
                assert subpage.doNotDestroy;
                // #1-5 委托「PoolSubpage」释放内存
                if (subpage.free(head, bitmapIdx(handle))) {
                    //the subpage is still used, do not free it
                    // 返回「true」表示当前PoolSubpage对象还在使用，不需要被回收
                    return;
                }
                assert !subpage.doNotDestroy;
                // Null out slot in the array as it was freed and we should not use it anymore.
                // 返回「flase」表示PoolSubpage已经没有被任何地方引用，需要回收
                subpages[sIdx] = null;
            } finally {
                head.unlock();
            }
        }

        // #2 回收run。这里获取当前handle所在的run的page数量
        int runSize = runSize(pageShifts, handle); // 获取page数量
        //start free run
        runsAvailLock.lock();
        try {
            // collapse continuous runs, successfully collapsed runs
            // will be removed from runsAvail and runsAvailMap
            // #2-1 向前、后合并与当前run的pageOffset连续的run
            long finalRun = collapseRuns(handle);

            //set run as not used
            // #2-2 更新「isUsed」标志位为 0
            finalRun &= ~(1L << IS_USED_SHIFT);
            //if it is a subpage, set it to run
            // #2-3 如果先前handle表示的是subpage，则需要清除标志位
            finalRun &= ~(1L << IS_SUBPAGE_SHIFT);

            // #2-4 更新 {@link PoolChunk#runsAvail} 和 {@link PoolChunk#runsAvailMap} 数据结构
            insertAvailRun(runOffset(finalRun), runPages(finalRun), finalRun);
            // 32-5 更新剩余空闲内存块大小
            freeBytes += runSize;
        } finally {
            runsAvailLock.unlock();
        }

        // #3 回收ByteBuffer对象
        if (nioBuffer != null && cachedNioBuffers != null &&
            cachedNioBuffers.size() < PooledByteBufAllocator.DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK) {
            cachedNioBuffers.offer(nioBuffer);
        }
    }

    private long collapseRuns(long handle) {
        return collapseNext(collapsePast(handle));
    }

    /**
     * 向前合并相邻的 run
     * @param handle 回收句柄值
     * @return
     */
    private long collapsePast(long handle) {
        // 不断向前合并，直到不能合并为止
        for (;;) {
            // #1 获取偏移量
            int runOffset = runOffset(handle);
            // #2 获取拥有的page数量
            int runPages = runPages(handle);

            // #3 根据「runOffset-1」末尾可用的run
            long pastRun = getAvailRunByOffset(runOffset - 1);
            if (pastRun == -1) {
                // #3-1 没有相邻的 run，直接返回
                return handle;
            }

            // #4 存在相邻的 run
            int pastOffset = runOffset(pastRun);
            int pastPages = runPages(pastRun);

            //is continuous
            // #5 再一次判断是否是连续的: past的偏移量+页数量=run的偏移量
            if (pastRun != handle && pastOffset + pastPages == runOffset) {
                //remove past run
                // #6 移除旧的run信息
                removeAvailRun(pastRun);
                // #7 生成新的handle
                handle = toRunHandle(pastOffset, pastPages + runPages, 0);
            } else {
                return handle;
            }
        }
    }

    // 向后合并 run。原理和向前合并 run 类似。
    private long collapseNext(long handle) {
        for (;;) {
            int runOffset = runOffset(handle);
            int runPages = runPages(handle);

            long nextRun = getAvailRunByOffset(runOffset + runPages);
            if (nextRun == -1) {
                return handle;
            }

            int nextOffset = runOffset(nextRun);
            int nextPages = runPages(nextRun);

            //is continuous
            if (nextRun != handle && runOffset + runPages == nextOffset) {
                //remove next run
                removeAvailRun(nextRun);
                handle = toRunHandle(runOffset, runPages + nextPages, 0);
            } else {
                return handle;
            }
        }
    }

    private static long toRunHandle(int runOffset, int runPages, int inUsed) {
        return (long) runOffset << RUN_OFFSET_SHIFT
               | (long) runPages << SIZE_SHIFT
               | (long) inUsed << IS_USED_SHIFT;
    }

    void initBuf(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity,
                 PoolThreadCache threadCache) {
        if (isSubpage(handle)) {
            initBufWithSubpage(buf, nioBuffer, handle, reqCapacity, threadCache);
        } else {
            int maxLength = runSize(pageShifts, handle);
            buf.init(this, nioBuffer, handle, runOffset(handle) << pageShifts,
                    reqCapacity, maxLength, arena.parent.threadCache());
        }
    }

    void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity,
                            PoolThreadCache threadCache) {
        int runOffset = runOffset(handle);
        int bitmapIdx = bitmapIdx(handle);

        PoolSubpage<T> s = subpages[runOffset];
        assert s.isDoNotDestroy();
        assert reqCapacity <= s.elemSize : reqCapacity + "<=" + s.elemSize;

        int offset = (runOffset << pageShifts) + bitmapIdx * s.elemSize;
        buf.init(this, nioBuffer, handle, offset, reqCapacity, s.elemSize, threadCache);
    }

    void incrementPinnedMemory(int delta) {
        assert delta > 0;
        pinnedBytes.add(delta);
    }

    void decrementPinnedMemory(int delta) {
        assert delta > 0;
        pinnedBytes.add(-delta);
    }

    @Override
    public int chunkSize() {
        return chunkSize;
    }

    @Override
    public int freeBytes() {
        if (this.unpooled) {
            return freeBytes;
        }
        runsAvailLock.lock();
        try {
            return freeBytes;
        } finally {
            runsAvailLock.unlock();
        }
    }

    public int pinnedBytes() {
        return (int) pinnedBytes.value();
    }

    @Override
    public String toString() {
        final int freeBytes;
        if (this.unpooled) {
            freeBytes = this.freeBytes;
        } else {
            runsAvailLock.lock();
            try {
                freeBytes = this.freeBytes;
            } finally {
                runsAvailLock.unlock();
            }
        }

        return new StringBuilder()
                .append("Chunk(")
                .append(Integer.toHexString(System.identityHashCode(this)))
                .append(": ")
                .append(usage(freeBytes))
                .append("%, ")
                .append(chunkSize - freeBytes)
                .append('/')
                .append(chunkSize)
                .append(')')
                .toString();
    }

    void destroy() {
        arena.destroyChunk(this);
    }

    static int runOffset(long handle) {
        return (int) (handle >> RUN_OFFSET_SHIFT);
    }

    static int runSize(int pageShifts, long handle) {
        return runPages(handle) << pageShifts;
    }

    static int runPages(long handle) {
        return (int) (handle >> SIZE_SHIFT & 0x7fff);
    }

    static boolean isUsed(long handle) {
        return (handle >> IS_USED_SHIFT & 1) == 1L;
    }

    static boolean isRun(long handle) {
        return !isSubpage(handle);
    }

    static boolean isSubpage(long handle) {
        return (handle >> IS_SUBPAGE_SHIFT & 1) == 1L;
    }

    static int bitmapIdx(long handle) {
        return (int) handle;
    }
}
