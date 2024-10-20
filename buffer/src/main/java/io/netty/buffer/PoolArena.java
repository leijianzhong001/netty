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
import io.netty.util.internal.StringUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static io.netty.buffer.PoolChunk.isSubpage;
import static java.lang.Math.max;

/**
 * PoolArena——内存管理的统筹者
 * PoolArena是内存管理的统筹者。
 * 它内部有一个PoolChunkList组成的链表(上文已经介绍过了，链表是按PoolChunkList所管理的使用率划分)。
 * 此外，它还有两个PoolSubpage的数组，PoolSubpage[] tinySubpagePools 和 PoolSubpage[] smallSubpagePools。
 * 默认情况下，tinySubpagePools的长度为31，即存放16，32，48...496这31种规格的PoolSubpage(不同规格的PoolSubpage存放在对应的数组下标中，相同规格的PoolSubpage在同一个数组下标中形成链表)。
 * 同理，默认情况下，smallSubpagePools的长度为4，存放512，1024，2048，4096这四种规格的PoolSubpage。
 * PoolArena会根据所申请的内存大小决定是找PoolChunk还是找对应规格的PoolSubpage来分配。
 *
 * 值得注意的是，PoolArena在分配内存时，是会存在竞争的，因此在关键的地方，PoolArena会通过sychronize来保证线程的安全。
 * Netty对这种竞争做了一定程度的优化，它会分配多个PoolArena，让线程尽量使用不同的PoolArena，减少出现竞争的情况。
 */
abstract class PoolArena<T> extends SizeClasses implements PoolArenaMetric {
    private static final boolean HAS_UNSAFE = PlatformDependent.hasUnsafe();

    enum SizeClass {
        Small, // 小于一个page(8Kib)的内存就是 Small
        Normal // 大于一个page(8Kib)且小于一个chunk(4MiB)的内存就是Normal
               // 大于一个chunk的就是huge, 直接和操作系统申请，不走arena
    }

    final PooledByteBufAllocator parent;

    final int numSmallSubpagePools;
    final int directMemoryCacheAlignment;

    /**
     * PoolArena会根据所申请的内存大小决定是找PoolChunk还是找对应规格的PoolSubpage来分配。
     * PoolSubpage——小内存的管理者
     * 不同规格的PoolSubpage存放在对应的数组下标中，相同规格的PoolSubpage在同一个数组下标中形成【链表】，PoolSubpage数组一个简单的示意图如下：
     *       [0] -> 16B -> 16B -> 16B -> 16B -> ...
     *       [1] -> 32B -> 32B -> 32B -> 32B -> ...
     *       [2] -> 48B -> 48B -> 48B -> 48B -> ...
     * 只有分配的空间大于一个page，才会从 PoolChunkList 中分配，否则的话都是使用 PoolSubpage 来分配
     * PoolChunk管理的最小内存是一个Page(默认8K)，而当我们需要的内存比较小时，直接分配一个Page无疑会造成内存浪费。 PoolSubPage就是用来管理这类细小内存的管理者。
     *
     * smallSubpagePools数组的下标是内存规格，更具体的来说，是内存规格对应的 SizeClasses 的索引值
     */
    final PoolSubpage<T>[] smallSubpagePools;

    /**
     * PoolArena会根据所申请的内存大小决定是找PoolChunk还是找对应规格的PoolSubpage来分配。
     * PoolChunkList——对PoolChunk的管理
     * PoolChunkList 内部有一个PoolChunk组成的【链表】。通常一个PoolChunkList中的所有PoolChunk使用率(已分配内存/ChunkSize)都在相同的范围内。
     * 每个PoolChunkList有自己的最小使用率或者最大使用率的范围，PoolChunkList与PoolChunkList之间又会形成链表，并且使用率范围小的PoolChunkList会在链表中更加靠前。
     * <p>
     * 而随着PoolChunk的内存分配和使用，其使用率发生变化后，PoolChunk会在PoolChunkList的链表中，前后调整，移动到合适范围的PoolChunkList内。
     * <p>
     * 这样做的好处是，使用率的小的PoolChunk可以先被用于内存分配，从而维持PoolChunk的利用率都在一个较高的水平，避免内存浪费。
     * 下面的变量名称中的数字指的是PoolChunk的使用率
     */
    private final PoolChunkList<T> q050; // 使用率在 50%~75% 的poolchunk列表
    private final PoolChunkList<T> q025; // 使用率在 25%~50% 的poolchunk列表
    private final PoolChunkList<T> q000; // 使用率在 0%~25% 的poolchunk列表
    private final PoolChunkList<T> qInit;
    private final PoolChunkList<T> q075; // 使用率在 75%~100% 的poolchunk列表
    private final PoolChunkList<T> q100; // 使用率在 100% 的poolchunk列表

    private final List<PoolChunkListMetric> chunkListMetrics;

    // Metrics for allocations and deallocations
    private long allocationsNormal;
    // We need to use the LongCounter here as this is not guarded via synchronized block.
    private final LongCounter allocationsSmall = PlatformDependent.newLongCounter();
    private final LongCounter allocationsHuge = PlatformDependent.newLongCounter();
    private final LongCounter activeBytesHuge = PlatformDependent.newLongCounter();

    private long deallocationsSmall;
    private long deallocationsNormal;

    // We need to use the LongCounter here as this is not guarded via synchronized block.
    private final LongCounter deallocationsHuge = PlatformDependent.newLongCounter();

    // Number of thread caches backed by this arena.
    // 由该arena支持的线程缓存数量。
    final AtomicInteger numThreadCaches = new AtomicInteger();

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    private final ReentrantLock lock = new ReentrantLock();

    protected PoolArena(PooledByteBufAllocator parent, int pageSize,
          int pageShifts, int chunkSize, int cacheAlignment) {
        super(pageSize, pageShifts, chunkSize, cacheAlignment);
        this.parent = parent;
        directMemoryCacheAlignment = cacheAlignment;

        numSmallSubpagePools = nSubpages;
        smallSubpagePools = newSubpagePoolArray(numSmallSubpagePools);
        for (int i = 0; i < smallSubpagePools.length; i ++) {
            smallSubpagePools[i] = newSubpagePoolHead(i);
        }

        // 在 PoolArena 的构造函数中，串联所有的PoolChunkList使其形成一个链表，链表中的每个PoolChunkList的使用率范围不同，使用率范围小的PoolChunkList会在链表中更加靠前。
        q100 = new PoolChunkList<T>(this, null, 100, Integer.MAX_VALUE, chunkSize);
        q075 = new PoolChunkList<T>(this, q100, 75, 100, chunkSize);
        q050 = new PoolChunkList<T>(this, q075, 50, 100, chunkSize);
        q025 = new PoolChunkList<T>(this, q050, 25, 75, chunkSize);
        q000 = new PoolChunkList<T>(this, q025, 1, 50, chunkSize);
        qInit = new PoolChunkList<T>(this, q000, Integer.MIN_VALUE, 25, chunkSize);

        q100.prevList(q075);
        q075.prevList(q050);
        q050.prevList(q025);
        q025.prevList(q000);
        q000.prevList(null);
        qInit.prevList(qInit);

        List<PoolChunkListMetric> metrics = new ArrayList<PoolChunkListMetric>(6);
        metrics.add(qInit);
        metrics.add(q000);
        metrics.add(q025);
        metrics.add(q050);
        metrics.add(q075);
        metrics.add(q100);
        chunkListMetrics = Collections.unmodifiableList(metrics);
    }

    private PoolSubpage<T> newSubpagePoolHead(int index) {
        PoolSubpage<T> head = new PoolSubpage<T>(index);
        head.prev = head;
        head.next = head;
        return head;
    }

    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpagePoolArray(int size) {
        return new PoolSubpage[size];
    }

    abstract boolean isDirect();

    PooledByteBuf<T> allocate(PoolThreadCache cache, int reqCapacity, int maxCapacity) {
        /*
         * 这里只是从对象池中分配一个ByteBuf对象，但还没有分配底层的实际内存资源
         * ByteBuf和内存其实是两个概念，要区分理解。
         *      ByteBuf是一个对象，需要给他分配一块内存，它才能正常工作。
         *      而内存可以通俗的理解成我们操作系统的内存，虽然申请到的内存也是需要依赖载体存储的: 堆内存时，通过byte[]， 而Direct内存，则是Nio的ByteBuffer(因此Java使用Direct Memory的能力是JDK中Nio包提供的)。
         * 为什么要强调这两个概念，是因为Netty的内存池(或者称内存管理机制)涉及的是针对内存的分配和回收，
         * 而Netty的ByteBuf的回收则是另一种叫做对象池的技术(通过Recycler实现)。 虽然这两者总是伴随着一起使用，但这二者是独立的两套机制。
         * 可能存在着某次创建ByteBuf时，ByteBuf是回收使用的，而内存却是新向操作系统申请的。
         * 也可能存在某次创建ByteBuf时，ByteBuf是新创建的，而内存却是回收使用的。
         */
        PooledByteBuf<T> buf = newByteBuf(maxCapacity);
        /*
         * 这里才是实际的为ByteBuf分配内存资源
         */
        allocate(cache, buf, reqCapacity);
        return buf;
    }

    /**
     * 按不同规格类型采用不同的内存分配策略
     * 这个方法主要是对用户申请的内存大小进行 SizeClasses 规格化，获取在 SizeClasses 的索引，通过判断索引值的大小采取不同的分配策略:
     * @param cache			本地线程缓存
     * @param buf			ByteBuf对象，是byte[]或ByteBuffer的承载对象
     * @param reqCapacity	申请内存容量大小
     */
    private void allocate(PoolThreadCache cache, PooledByteBuf<T> buf, final int reqCapacity) {
        // 1、根据申请容量大小查表确定对应数组下标序号。 具体操作就是先确定 reqCapacity 在第几组，然后在组内的哪个位置。两者相加就是最后的值了
        final int sizeIdx = size2SizeIdx(reqCapacity);

        // 2、根据下标序号就可以得到对应的规格值 smallMaxSizeIdx=38
        if (sizeIdx <= smallMaxSizeIdx) { // size <= 8KB
            // 2.1、下标序号 <=「smallMaxSizeIdx」，表示申请容量大小 <= pageSize，属于「Small」级别内存分配
            tcacheAllocateSmall(cache, buf, reqCapacity, sizeIdx);
        } else if (sizeIdx < nSizes) {  // 8KB < size <=16MB， nSizes=64
            // 2.2、下标序号<「nSizes」，表示申请容量大小介于pageSize和chunkSize之间，属于「Normal」级别内存分配
            tcacheAllocateNormal(cache, buf, reqCapacity, sizeIdx);
        } else {
            // 2.3、超出「ChunkSize」，属于「Huge」级别内存分配
            int normCapacity = directMemoryCacheAlignment > 0
                    ? normalizeSize(reqCapacity) : reqCapacity;
            // Huge allocations are never served via the cache so just call allocateHuge
            allocateHuge(buf, normCapacity);
        }
    }

    private void tcacheAllocateSmall(PoolThreadCache cache, PooledByteBuf<T> buf, final int reqCapacity,
                                     final int sizeIdx) {

        if (cache.allocateSmall(this, buf, reqCapacity, sizeIdx)) {
            // was able to allocate out of the cache so move on
            return;
        }

        /*
         * Synchronize on the head. This is needed as {@link PoolChunk#allocateSubpage(int)} and
         * {@link PoolChunk#free(long)} may modify the doubly linked list as well.
         */
        final PoolSubpage<T> head = smallSubpagePools[sizeIdx];
        final boolean needsNormalAllocation;
        head.lock();
        try {
            final PoolSubpage<T> s = head.next;
            needsNormalAllocation = s == head;
            if (!needsNormalAllocation) {
                assert s.doNotDestroy && s.elemSize == sizeIdx2size(sizeIdx) : "doNotDestroy=" +
                        s.doNotDestroy + ", elemSize=" + s.elemSize + ", sizeIdx=" + sizeIdx;
                long handle = s.allocate();
                assert handle >= 0;
                s.chunk.initBufWithSubpage(buf, null, handle, reqCapacity, cache);
            }
        } finally {
            head.unlock();
        }

        if (needsNormalAllocation) {
            lock();
            try {
                allocateNormal(buf, reqCapacity, sizeIdx, cache);
            } finally {
                unlock();
            }
        }

        incSmallAllocation();
    }

    /**
     * 尝试先从本地线程缓存中分配内存，尝试失败，
     * 就会从不同使用率的「PoolChunkList」链表中寻找合适的内存空间并完成分配。
     * 如果这样还是不行，那就只能创建一个全新的PoolChunk对象
     * @param cache         本地线程缓存，用来提高内存分配效率
     * @param buf           ByteBuf承载对象
     * @param reqCapacity    用户申请的内存大小
     * @param sizeIdx        对应{@link SizeClasses}的索引值，可以通过该值从{@link SizeClasses}中获取相应的规格值
     */
    private void tcacheAllocateNormal(PoolThreadCache cache, PooledByteBuf<T> buf, final int reqCapacity,
                                      final int sizeIdx) {
        // #1 首先尝试从「本地线程缓存(线程私有变量，不需要加锁)」分配内存
        if (cache.allocateNormal(this, buf, reqCapacity, sizeIdx)) {
            // was able to allocate out of the cache so move on
            // 尝试成功，直接返回。本地线程会完成对「ByteBuf」对象的初始化工作
            return;
        }
        // 因为对「PoolArena」对象来说，内部的PoolChunkList会存在线程竞争，需要加锁
        lock();
        try {
            // #2 委托给「PoolChunk」对象完成内存分配
            allocateNormal(buf, reqCapacity, sizeIdx, cache);
            ++allocationsNormal;
        } finally {
            unlock();
        }
    }

    /**
     * 先从「PoolChunkList」链表中选取某一个「PoolChunk」进行内存分配，如果实在找不到合适的「PoolChunk」对象，
     * 那就只能新建一个全新的「PoolChunk」对象，在完成内存分配后需要添加到对应的PoolChunkList链表中。
     * 内部有多个「PoolChunkList」链表，q050、q025表示内部的「PoolChunk」最低的使用率。
     * Netty 会先从q050开始分配，并非从q000开始。
     * 这是因为如果从q000开始分配内存的话会导致有大部分的PoolChunk面临频繁的创建和销毁，造成内存分配的性能降低。
     *
     * @param buf         ByeBuf承载对象
     * @param reqCapacity 用户所需要真实的内存大小
     * @param sizeIdx     对应{@link SizeClasses}的索引值，可以通过该值从{@link SizeClasses}中获取相应的规格值
     * @param threadCache 本地线程缓存，这个缓存主要是为了初始化PooledByteBuf时填充对象内部的缓存变量
     */
    private void allocateNormal(PooledByteBuf<T> buf, int reqCapacity, int sizeIdx, PoolThreadCache threadCache) {
        assert lock.isHeldByCurrentThread();
        // #1 尝试从「PoolChunkList」链表中分配（寻找现有的「PoolChunk」进行内存分配）
        if (q050.allocate(buf, reqCapacity, sizeIdx, threadCache) ||
            q025.allocate(buf, reqCapacity, sizeIdx, threadCache) ||
            q000.allocate(buf, reqCapacity, sizeIdx, threadCache) ||
            qInit.allocate(buf, reqCapacity, sizeIdx, threadCache) ||
            q075.allocate(buf, reqCapacity, sizeIdx, threadCache)) {
            // 分配成功，直接返回
            return;
        }

        // #2 新建一个「PoolChunk」对象，如果实在找不到合适的「PoolChunk」对象
        // Add a new chunk.
        PoolChunk<T> c = newChunk(pageSize, nPSizes, pageShifts, chunkSize);
        // #3 使用新的「PoolChunk」完成内存分配
        boolean success = c.allocate(buf, reqCapacity, sizeIdx, threadCache);
        assert success;
        // #4 根据最低的使用率添加到「PoolChunkList」节点中
        qInit.add(c);
    }

    private void incSmallAllocation() {
        allocationsSmall.increment();
    }

    private void allocateHuge(PooledByteBuf<T> buf, int reqCapacity) {
        PoolChunk<T> chunk = newUnpooledChunk(reqCapacity);
        activeBytesHuge.add(chunk.chunkSize());
        buf.initUnpooled(chunk, reqCapacity);
        allocationsHuge.increment();
    }

    void free(PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, int normCapacity, PoolThreadCache cache) {
        chunk.decrementPinnedMemory(normCapacity);
        if (chunk.unpooled) {
            int size = chunk.chunkSize();
            destroyChunk(chunk);
            activeBytesHuge.add(-size);
            deallocationsHuge.increment();
        } else {
            SizeClass sizeClass = sizeClass(handle);
            if (cache != null && cache.add(this, chunk, nioBuffer, handle, normCapacity, sizeClass)) {
                // cached so not free it.
                return;
            }

            freeChunk(chunk, handle, normCapacity, sizeClass, nioBuffer, false);
        }
    }

    private static SizeClass sizeClass(long handle) {
        return isSubpage(handle) ? SizeClass.Small : SizeClass.Normal;
    }

    void freeChunk(PoolChunk<T> chunk, long handle, int normCapacity, SizeClass sizeClass, ByteBuffer nioBuffer,
                   boolean finalizer) {
        final boolean destroyChunk;
        lock();
        try {
            // We only call this if freeChunk is not called because of the PoolThreadCache finalizer as otherwise this
            // may fail due lazy class-loading in for example tomcat.
            if (!finalizer) {
                switch (sizeClass) {
                    case Normal:
                        ++deallocationsNormal;
                        break;
                    case Small:
                        ++deallocationsSmall;
                        break;
                    default:
                        throw new Error();
                }
            }
            destroyChunk = !chunk.parent.free(chunk, handle, normCapacity, nioBuffer);
        } finally {
            unlock();
        }
        if (destroyChunk) {
            // destroyChunk not need to be called while holding the synchronized lock.
            destroyChunk(chunk);
        }
    }

    void reallocate(PooledByteBuf<T> buf, int newCapacity) {
        assert newCapacity >= 0 && newCapacity <= buf.maxCapacity();

        final int oldCapacity;
        final PoolChunk<T> oldChunk;
        final ByteBuffer oldNioBuffer;
        final long oldHandle;
        final T oldMemory;
        final int oldOffset;
        final int oldMaxLength;
        final PoolThreadCache oldCache;

        // We synchronize on the ByteBuf itself to ensure there is no "concurrent" reallocations for the same buffer.
        // We do this to ensure the ByteBuf internal fields that are used to allocate / free are not accessed
        // concurrently. This is important as otherwise we might end up corrupting our internal state of our data
        // structures.
        //
        // Also note we don't use a Lock here but just synchronized even tho this might seem like a bad choice for Loom.
        // This is done to minimize the overhead per ByteBuf. The time this would block another thread should be
        // relative small and so not be a problem for Loom.
        // See https://github.com/netty/netty/issues/13467
        synchronized (buf) {
            oldCapacity = buf.length;
            if (oldCapacity == newCapacity) {
                return;
            }

            oldChunk = buf.chunk;
            oldNioBuffer = buf.tmpNioBuf;
            oldHandle = buf.handle;
            oldMemory = buf.memory;
            oldOffset = buf.offset;
            oldMaxLength = buf.maxLength;
            oldCache = buf.cache;

            // This does not touch buf's reader/writer indices
            allocate(parent.threadCache(), buf, newCapacity);
        }
        int bytesToCopy;
        if (newCapacity > oldCapacity) {
            bytesToCopy = oldCapacity;
        } else {
            buf.trimIndicesToCapacity(newCapacity);
            bytesToCopy = newCapacity;
        }
        memoryCopy(oldMemory, oldOffset, buf, bytesToCopy);
        free(oldChunk, oldNioBuffer, oldHandle, oldMaxLength, oldCache);
    }

    @Override
    public int numThreadCaches() {
        return numThreadCaches.get();
    }

    @Override
    public int numTinySubpages() {
        return 0;
    }

    @Override
    public int numSmallSubpages() {
        return smallSubpagePools.length;
    }

    @Override
    public int numChunkLists() {
        return chunkListMetrics.size();
    }

    @Override
    public List<PoolSubpageMetric> tinySubpages() {
        return Collections.emptyList();
    }

    @Override
    public List<PoolSubpageMetric> smallSubpages() {
        return subPageMetricList(smallSubpagePools);
    }

    @Override
    public List<PoolChunkListMetric> chunkLists() {
        return chunkListMetrics;
    }

    private static List<PoolSubpageMetric> subPageMetricList(PoolSubpage<?>[] pages) {
        List<PoolSubpageMetric> metrics = new ArrayList<PoolSubpageMetric>();
        for (PoolSubpage<?> head : pages) {
            if (head.next == head) {
                continue;
            }
            PoolSubpage<?> s = head.next;
            for (;;) {
                metrics.add(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
        return metrics;
    }

    @Override
    public long numAllocations() {
        final long allocsNormal;
        lock();
        try {
            allocsNormal = allocationsNormal;
        } finally {
            unlock();
        }
        return allocationsSmall.value() + allocsNormal + allocationsHuge.value();
    }

    @Override
    public long numTinyAllocations() {
        return 0;
    }

    @Override
    public long numSmallAllocations() {
        return allocationsSmall.value();
    }

    @Override
    public long numNormalAllocations() {
        lock();
        try {
            return allocationsNormal;
        } finally {
            unlock();
        }
    }

    @Override
    public long numDeallocations() {
        final long deallocs;
        lock();
        try {
            deallocs = deallocationsSmall + deallocationsNormal;
        } finally {
            unlock();
        }
        return deallocs + deallocationsHuge.value();
    }

    @Override
    public long numTinyDeallocations() {
        return 0;
    }

    @Override
    public long numSmallDeallocations() {
        lock();
        try {
            return deallocationsSmall;
        } finally {
            unlock();
        }
    }

    @Override
    public long numNormalDeallocations() {
        lock();
        try {
            return deallocationsNormal;
        } finally {
            unlock();
        }
    }

    @Override
    public long numHugeAllocations() {
        return allocationsHuge.value();
    }

    @Override
    public long numHugeDeallocations() {
        return deallocationsHuge.value();
    }

    @Override
    public  long numActiveAllocations() {
        long val = allocationsSmall.value() + allocationsHuge.value()
                - deallocationsHuge.value();
        lock();
        try {
            val += allocationsNormal - (deallocationsSmall + deallocationsNormal);
        } finally {
            unlock();
        }
        return max(val, 0);
    }

    @Override
    public long numActiveTinyAllocations() {
        return 0;
    }

    @Override
    public long numActiveSmallAllocations() {
        return max(numSmallAllocations() - numSmallDeallocations(), 0);
    }

    @Override
    public long numActiveNormalAllocations() {
        final long val;
        lock();
        try {
            val = allocationsNormal - deallocationsNormal;
        } finally {
            unlock();
        }
        return max(val, 0);
    }

    @Override
    public long numActiveHugeAllocations() {
        return max(numHugeAllocations() - numHugeDeallocations(), 0);
    }

    @Override
    public long numActiveBytes() {
        long val = activeBytesHuge.value();
        lock();
        try {
            for (int i = 0; i < chunkListMetrics.size(); i++) {
                for (PoolChunkMetric m: chunkListMetrics.get(i)) {
                    val += m.chunkSize();
                }
            }
        } finally {
            unlock();
        }
        return max(0, val);
    }

    /**
     * Return the number of bytes that are currently pinned to buffer instances, by the arena. The pinned memory is not
     * accessible for use by any other allocation, until the buffers using have all been released.
     */
    public long numPinnedBytes() {
        long val = activeBytesHuge.value(); // Huge chunks are exact-sized for the buffers they were allocated to.
        lock();
        try {
            for (int i = 0; i < chunkListMetrics.size(); i++) {
                for (PoolChunkMetric m: chunkListMetrics.get(i)) {
                    val += ((PoolChunk<?>) m).pinnedBytes();
                }
            }
        } finally {
            unlock();
        }
        return max(0, val);
    }

    protected abstract PoolChunk<T> newChunk(int pageSize, int maxPageIdx, int pageShifts, int chunkSize);
    protected abstract PoolChunk<T> newUnpooledChunk(int capacity);
    protected abstract PooledByteBuf<T> newByteBuf(int maxCapacity);
    protected abstract void memoryCopy(T src, int srcOffset, PooledByteBuf<T> dst, int length);
    protected abstract void destroyChunk(PoolChunk<T> chunk);

    @Override
    public String toString() {
        lock();
        try {
            StringBuilder buf = new StringBuilder()
                    .append("Chunk(s) at 0~25%:")
                    .append(StringUtil.NEWLINE)
                    .append(qInit)
                    .append(StringUtil.NEWLINE)
                    .append("Chunk(s) at 0~50%:")
                    .append(StringUtil.NEWLINE)
                    .append(q000)
                    .append(StringUtil.NEWLINE)
                    .append("Chunk(s) at 25~75%:")
                    .append(StringUtil.NEWLINE)
                    .append(q025)
                    .append(StringUtil.NEWLINE)
                    .append("Chunk(s) at 50~100%:")
                    .append(StringUtil.NEWLINE)
                    .append(q050)
                    .append(StringUtil.NEWLINE)
                    .append("Chunk(s) at 75~100%:")
                    .append(StringUtil.NEWLINE)
                    .append(q075)
                    .append(StringUtil.NEWLINE)
                    .append("Chunk(s) at 100%:")
                    .append(StringUtil.NEWLINE)
                    .append(q100)
                    .append(StringUtil.NEWLINE)
                    .append("small subpages:");
            appendPoolSubPages(buf, smallSubpagePools);
            buf.append(StringUtil.NEWLINE);
            return buf.toString();
        } finally {
            unlock();
        }
    }

    private static void appendPoolSubPages(StringBuilder buf, PoolSubpage<?>[] subpages) {
        for (int i = 0; i < subpages.length; i ++) {
            PoolSubpage<?> head = subpages[i];
            if (head.next == head || head.next == null) {
                continue;
            }

            buf.append(StringUtil.NEWLINE)
                    .append(i)
                    .append(": ");
            PoolSubpage<?> s = head.next;
            while (s != null) {
                buf.append(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
    }

    @Override
    protected final void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            destroyPoolSubPages(smallSubpagePools);
            destroyPoolChunkLists(qInit, q000, q025, q050, q075, q100);
        }
    }

    private static void destroyPoolSubPages(PoolSubpage<?>[] pages) {
        for (PoolSubpage<?> page : pages) {
            page.destroy();
        }
    }

    private void destroyPoolChunkLists(PoolChunkList<T>... chunkLists) {
        for (PoolChunkList<T> chunkList: chunkLists) {
            chunkList.destroy(this);
        }
    }

    static final class HeapArena extends PoolArena<byte[]> {

        HeapArena(PooledByteBufAllocator parent, int pageSize, int pageShifts,
                  int chunkSize) {
            super(parent, pageSize, pageShifts, chunkSize,
                  0);
        }

        private static byte[] newByteArray(int size) {
            return PlatformDependent.allocateUninitializedArray(size);
        }

        @Override
        boolean isDirect() {
            return false;
        }

        @Override
        protected PoolChunk<byte[]> newChunk(int pageSize, int maxPageIdx, int pageShifts, int chunkSize) {
            return new PoolChunk<byte[]>(
                    this, null, newByteArray(chunkSize), pageSize, pageShifts, chunkSize, maxPageIdx);
        }

        @Override
        protected PoolChunk<byte[]> newUnpooledChunk(int capacity) {
            return new PoolChunk<byte[]>(this, null, newByteArray(capacity), capacity);
        }

        @Override
        protected void destroyChunk(PoolChunk<byte[]> chunk) {
            // Rely on GC.
        }

        @Override
        protected PooledByteBuf<byte[]> newByteBuf(int maxCapacity) {
            return HAS_UNSAFE ? PooledUnsafeHeapByteBuf.newUnsafeInstance(maxCapacity)
                    : PooledHeapByteBuf.newInstance(maxCapacity);
        }

        @Override
        protected void memoryCopy(byte[] src, int srcOffset, PooledByteBuf<byte[]> dst, int length) {
            if (length == 0) {
                return;
            }

            System.arraycopy(src, srcOffset, dst.memory, dst.offset, length);
        }
    }

    static final class DirectArena extends PoolArena<ByteBuffer> {

        DirectArena(PooledByteBufAllocator parent, int pageSize, int pageShifts,
                    int chunkSize, int directMemoryCacheAlignment) {
            super(parent, pageSize, pageShifts, chunkSize,
                  directMemoryCacheAlignment);
        }

        @Override
        boolean isDirect() {
            return true;
        }

        @Override
        protected PoolChunk<ByteBuffer> newChunk(int pageSize, int maxPageIdx,
            int pageShifts, int chunkSize) {
            if (directMemoryCacheAlignment == 0) {
                ByteBuffer memory = allocateDirect(chunkSize);
                return new PoolChunk<ByteBuffer>(this, memory, memory, pageSize, pageShifts,
                        chunkSize, maxPageIdx);
            }

            final ByteBuffer base = allocateDirect(chunkSize + directMemoryCacheAlignment);
            final ByteBuffer memory = PlatformDependent.alignDirectBuffer(base, directMemoryCacheAlignment);
            return new PoolChunk<ByteBuffer>(this, base, memory, pageSize,
                    pageShifts, chunkSize, maxPageIdx);
        }

        @Override
        protected PoolChunk<ByteBuffer> newUnpooledChunk(int capacity) {
            if (directMemoryCacheAlignment == 0) {
                ByteBuffer memory = allocateDirect(capacity);
                return new PoolChunk<ByteBuffer>(this, memory, memory, capacity);
            }

            final ByteBuffer base = allocateDirect(capacity + directMemoryCacheAlignment);
            final ByteBuffer memory = PlatformDependent.alignDirectBuffer(base, directMemoryCacheAlignment);
            return new PoolChunk<ByteBuffer>(this, base, memory, capacity);
        }

        private static ByteBuffer allocateDirect(int capacity) {
            return PlatformDependent.useDirectBufferNoCleaner() ?
                    PlatformDependent.allocateDirectNoCleaner(capacity) : ByteBuffer.allocateDirect(capacity);
        }

        @Override
        protected void destroyChunk(PoolChunk<ByteBuffer> chunk) {
            if (PlatformDependent.useDirectBufferNoCleaner()) {
                PlatformDependent.freeDirectNoCleaner((ByteBuffer) chunk.base);
            } else {
                PlatformDependent.freeDirectBuffer((ByteBuffer) chunk.base);
            }
        }

        @Override
        protected PooledByteBuf<ByteBuffer> newByteBuf(int maxCapacity) {
            if (HAS_UNSAFE) {
                return PooledUnsafeDirectByteBuf.newInstance(maxCapacity);
            } else {
                return PooledDirectByteBuf.newInstance(maxCapacity);
            }
        }

        @Override
        protected void memoryCopy(ByteBuffer src, int srcOffset, PooledByteBuf<ByteBuffer> dstBuf, int length) {
            if (length == 0) {
                return;
            }

            if (HAS_UNSAFE) {
                PlatformDependent.copyMemory(
                        PlatformDependent.directBufferAddress(src) + srcOffset,
                        PlatformDependent.directBufferAddress(dstBuf.memory) + dstBuf.offset, length);
            } else {
                // We must duplicate the NIO buffers because they may be accessed by other Netty buffers.
                src = src.duplicate();
                ByteBuffer dst = dstBuf.internalNioBuffer();
                src.position(srcOffset).limit(srcOffset + length);
                dst.position(dstBuf.offset);
                dst.put(src);
            }
        }
    }

    void lock() {
        lock.lock();
    }

    void unlock() {
        lock.unlock();
    }
}
