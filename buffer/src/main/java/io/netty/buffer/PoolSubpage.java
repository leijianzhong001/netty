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

import java.util.concurrent.locks.ReentrantLock;

import static io.netty.buffer.PoolChunk.RUN_OFFSET_SHIFT;
import static io.netty.buffer.PoolChunk.SIZE_SHIFT;
import static io.netty.buffer.PoolChunk.IS_USED_SHIFT;
import static io.netty.buffer.PoolChunk.IS_SUBPAGE_SHIFT;

/**
 * PoolSubpage——小内存的管理者  run?
 *
 * PoolChunk管理的最小内存是一个Page(默认8K)，而当我们需要的内存比较小时，直接分配一个Page无疑会造成内存浪费。
 * PoolSubPage就是用来管理这类细小内存的管理者。 一个 PoolSubPage 对应的内存大小就是一个page: 8KB
 *
 * 小内存是指小于一个Page的内存，可以分为Tiny和Smalll，Tiny是小于512B的内存，而Small则是512到4096B的内存。
 * 如果内存块大于等于一个Page，称之为Normal，而大于一个Chunk的内存块称之为Huge。
 * 而Tiny和Small内部又会按具体内存的大小进行细分。
 *      对Tiny而言，会分成16，32，48...496(以16的倍数递增)，共31种情况。
 *      对Small而言，会分成512，1024，2048，4096四种情况。
 *
 * PoolSubpage会先向PoolChunk申请一个Page的内存，然后将这个page按规格划分成相等的若干个内存块。
 * 一个PoolSubpage仅会管理一种规格的内存块，例如仅管理16B,就将一个Page的内存分成512个16B大小的内存块。
 * 每个PoolSubpage仅会选一种规格的内存管理，因此处理相同规格的PoolSubpage往往是通过链表的方式组织在一起，不同的规格则分开存放在不同的地方。
 * 并且总是管理一个规格的特性，让PoolSubpage在内存管理时不需要使用PoolChunk的完全二叉树方式来管理内存(例如，
 * 管理16B的PoolSubpage只需要考虑分配16B的内存，当申请32B的内存时，必须交给管理32B的内存来处理)，仅用 long[] bitmap (可以看成是位数组)来记录所管理的内存块
 * 中哪些已经被分配(第几位就表示第几个内存块)。
 * 实现方式要简单很多。
 */
final class PoolSubpage<T> implements PoolSubpageMetric {

    // 当前 PoolSubpage 所属的 PoolChunk 对象， 因为 PoolSubpage 也是从 PoolChunk 中分配的
    final PoolChunk<T> chunk;
    // 当前 PoolSubpage 管理的内存块的规格
    final int elemSize;
    private final int pageShifts;
    private final int runOffset;
    private final int runSize;

    /**
     * 记录当前 PoolSubpage 中每个内存块的分配情况，1表示已经分配，0表示未分配
     *
     * 总是管理一个规格的特性，让PoolSubpage在内存管理时不需要使用PoolChunk的完全二叉树方式来管理内存.
     * 例如， 管理16B的PoolSubpage只需要考虑分配16B的内存，当申请32B的内存时，必须交给管理32B的内存来处理)，
     * 仅用 long[] bitmap (可以看成是位数组)来记录所管理的内存块中哪些已经被分配(第几位就表示第几个内存块)。
     *
     * 如果当前 PoolSubpage 管理的是32 byte的内存，则当前 PoolSubpage 会被分成256个 32 byte 大小的内存块，并使用一个长度为4的long数组 bitmap来标记每个内存块的使用情况，
     * 因为一个long是64位，4个long正好可以表示256个内存块的使用情况
     */
    private final long[] bitmap;
    private final int bitmapLength; // 上面bitmap数组的长度
    private final int maxNumElems; // maxNumElems 表述的是当前 PoolSubpage 中内存块的数量
    final int headIndex;

    PoolSubpage<T> prev;
    PoolSubpage<T> next;

    boolean doNotDestroy;
    private int nextAvail; // 下一个可用内存块的偏移量
    private int numAvail; // 可用的内存块的数量

    final ReentrantLock lock;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /** Special constructor that creates a linked list head */
    PoolSubpage(int headIndex) {
        chunk = null;
        lock = new ReentrantLock();
        pageShifts = -1;
        runOffset = -1;
        elemSize = -1;
        runSize = -1;
        bitmap = null;
        bitmapLength = -1;
        maxNumElems = 0;
        this.headIndex = headIndex;
    }

    PoolSubpage(PoolSubpage<T> head, PoolChunk<T> chunk, int pageShifts, int runOffset, int runSize, int elemSize) {
        this.headIndex = head.headIndex;
        this.chunk = chunk; // chunk 表示的是当前 PoolSubpage 所属的 PoolChunk 对象
        this.pageShifts = pageShifts;
        this.runOffset = runOffset; // runOffset 表示的是当前 PoolSubpage 在 PoolChunk 中的偏移量
        this.runSize = runSize; // runSize 表示的是当前 PoolSubpage 所管理的总内存大小
        this.elemSize = elemSize; // elemSize 表示的是当前 PoolSubpage 所管理的内存规格

        doNotDestroy = true;

        // maxNumElems 表述的是当前 PoolSubpage 管理的内存块的数量
        maxNumElems = numAvail = runSize / elemSize;

        // bitmapLength 用于表达 bitmap 数组的长度。
        // 如果当前 PoolSubpage 管理的是32 byte 规格的内存，那么 maxNumElems 就是 8kb/32=256, 即当前 PoolSubpage 管理的内存块的数量是 256
        // 而如果我们需要标识 256 个内存块的使用情况，那么就至少需要256位的长度，如果使用long类型来存储的话，一个long是64位，那么只需要4个long即可表示全部的 256 个内存块的使用情况。
        // 所以这里计算 bitmap 数组的长度的方式是 maxNumElems/64 -> 256/64=4
        int bitmapLength = maxNumElems >>> 6; // 无符号右移6位相当于除以64
        if ((maxNumElems & 63) != 0) {
            bitmapLength ++;
        }
        this.bitmapLength = bitmapLength; // bitmap数组的长度
        bitmap = new long[bitmapLength];
        nextAvail = 0;

        lock = null;
        // 注意：这里的这个head是arena.smallSubpagePools中的elemSize这个规格的头结点，所以这里实际上时将当前新建的PoolSubpage加入到了PoolArena的管理中
        addToPool(head);
    }

    /**
     * Returns the bitmap index of the subpage allocation.
     */
    long allocate() {
        // 无可用分片，返回-1
        if (numAvail == 0 || !doNotDestroy) {
            return -1;
        }

        // #1 获取下一个可用的分片索引（绝对值）
        // 第一个索引值为0
        final int bitmapIdx = getNextAvail();
        if (bitmapIdx < 0) {
            removeFromPool(); // Subpage appear to be in an invalid state. Remove to prevent repeated errors.
            throw new AssertionError("No next available bitmap index found (bitmapIdx = " + bitmapIdx + "), " +
                    "even though there are supposed to be (numAvail = " + numAvail + ") " +
                    "out of (maxNumElems = " + maxNumElems + ") available indexes.");
        }

        // #2 除以64，确定bitmap[]中的哪一个，因为是使用long[]数组类存储所有内存分片的使用情况，并且 bitmap 的长度被构造为 maxNumElems/64，即它是使用一个long数字来记录多个内存分片的使用情况的
        // 第一个bitmap索引值为0
        int q = bitmapIdx >>> 6;
        // #3 &63: 确认64位长度long的哪一位
        // 除以64取余，获取当前绝对 id 的偏移量
        // 63: 0011 1111
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) == 0;
        // 更新第r位的值为1
        // << 优先级高于 |=
        bitmap[q] |= 1L << r;

        if (-- numAvail == 0) {
            // 如果可用数量为0，表示子页中再无可分配的空间
            // 需要从双向链表中移除
            removeFromPool();
        }

        // 将bitmapIdx 转换为long类型handle存储，long 高32位存储的是小内存位置索引
        return toHandle(bitmapIdx);
    }

    /**
     * @return {@code true} if this subpage is in use.
     *         {@code false} if this subpage is not used by its chunk and thus it's OK to be released.
     */
    boolean free(PoolSubpage<T> head, int bitmapIdx) {
        int q = bitmapIdx >>> 6;
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) != 0;
        bitmap[q] ^= 1L << r;

        setNextAvail(bitmapIdx);

        if (numAvail ++ == 0) {
            addToPool(head);
            /* When maxNumElems == 1, the maximum numAvail is also 1.
             * Each of these PoolSubpages will go in here when they do free operation.
             * If they return true directly from here, then the rest of the code will be unreachable
             * and they will not actually be recycled. So return true only on maxNumElems > 1. */
            if (maxNumElems > 1) {
                return true;
            }
        }

        if (numAvail != maxNumElems) {
            // 如果当前subPage中还有内存分片在被使用，不能进行回收
            return true;
        } else {
            // Subpage not in use (numAvail == maxNumElems)
            // 如果当前subPage中所有的内存分片都没有被使用，可以进行回收
            if (prev == next) {
                // Do not remove if this subpage is the only one left in the pool.
                // 如果当前subPage是链表中唯一的一个，不能进行回收
                return true;
            }

            // Remove this subpage from the pool if there are other subpages left in the pool.
            doNotDestroy = false;
            // 将自身从arena的smallSubpagePools的某个head对应的双向链表中移除，后面将按照run的方式回收
            removeFromPool();
            return false;
        }
    }

    private void addToPool(PoolSubpage<T> head) {
        assert prev == null && next == null;
        prev = head;
        next = head.next;
        next.prev = this;
        head.next = this;
    }

    private void removeFromPool() {
        assert prev != null && next != null;
        prev.next = next;
        next.prev = prev;
        next = null;
        prev = null;
    }

    private void setNextAvail(int bitmapIdx) {
        nextAvail = bitmapIdx;
    }

    /**
     * 获取下一个可用的「分片内存块」
     */
    private int getNextAvail() {
        int nextAvail = this.nextAvail;
        // nextAvail>=0，表明可以直接使用
        if (nextAvail >= 0) {
            this.nextAvail = -1;
            return nextAvail;
        }
        // nextAvaild<0，需要寻找下一个可用的「分片内存块」
        return findNextAvail();
    }

    /**
     * 获取下一个可用的「分片内存块」
     * 本质是搜索 bitmap[] 数组为0的索引值
     */
    private int findNextAvail() {
        for (int i = 0; i < bitmapLength; i ++) {
            long bits = bitmap[i];
            // #1 先判断整个bits是否有「0」位
            // 不可用时bits为「0XFFFFFFFFFFFFFFFF」，~bits=0
            // 可用时~bits !=0
            if (~bits != 0) {
                // #2 找寻可用的位
                return findNextAvail0(i, bits);
            }
        }
        return -1;
    }

    /**
     * 搜索下一个可用位
     */
    private int findNextAvail0(int i, long bits) {
        // i << 6 => i * 2^6=i*64
        // 想象把long[]展开，baseVal就是基址
        final int baseVal = i << 6;
        for (int j = 0; j < 64; j ++) {
            // bits & 1: 判断最低位是否为0
            if ((bits & 1) == 0) {
                // 找到空闲子块，组装数据
                // baseVal|j => baseVal + j，基址+位的偏移值
                int val = baseVal | j;
                // 不能越界
                if (val < maxNumElems) {
                    return val;
                } else {
                    break;
                }
            }
            // 无符号右移1位
            bits >>>= 1;
        }
        return -1;
    }

    /**
     * 将bitmap索引信息写入高32位，memoryMapIdx信息写入低32位
     *
     * 0x4000000000000000L: 最高位为1，其他所有位为0。
     * 为什么使用0x4000000000000000L数值?
     * 是因为对于第一次小内存分配情况，如果高32位为0，则返回句柄值的高位为 0，
     * 低32位为 2048（第11层的第一个节点的索引值），但是这个返回值并不会当成子页来处理，从而影响后续的逻辑判断
     * 详见 https://blog.csdn.net/wangwei19871103/article/details/104356566
     * @param bitmapIdx  bitmap索引值
     * @return		     句柄值
     */
    private long toHandle(int bitmapIdx) {
        int pages = runSize >> pageShifts;
        return (long) runOffset << RUN_OFFSET_SHIFT
               | (long) pages << SIZE_SHIFT
               | 1L << IS_USED_SHIFT
               | 1L << IS_SUBPAGE_SHIFT
               | bitmapIdx;
    }

    @Override
    public String toString() {
        final int numAvail;
        if (chunk == null) {
            // This is the head so there is no need to synchronize at all as these never change.
            numAvail = 0;
        } else {
            final boolean doNotDestroy;
            PoolSubpage<T> head = chunk.arena.smallSubpagePools[headIndex];
            head.lock();
            try {
                doNotDestroy = this.doNotDestroy;
                numAvail = this.numAvail;
            } finally {
                head.unlock();
            }
            if (!doNotDestroy) {
                // Not used for creating the String.
                return "(" + runOffset + ": not in use)";
            }
        }

        return "(" + this.runOffset + ": " + (this.maxNumElems - numAvail) + '/' + this.maxNumElems +
                ", offset: " + this.runOffset + ", length: " + this.runSize + ", elemSize: " + this.elemSize + ')';
    }

    @Override
    public int maxNumElements() {
        return maxNumElems;
    }

    @Override
    public int numAvailable() {
        if (chunk == null) {
            // It's the head.
            return 0;
        }
        PoolSubpage<T> head = chunk.arena.smallSubpagePools[headIndex];
        head.lock();
        try {
            return numAvail;
        } finally {
            head.unlock();
        }
    }

    @Override
    public int elementSize() {
        return elemSize;
    }

    @Override
    public int pageSize() {
        return 1 << pageShifts;
    }

    boolean isDoNotDestroy() {
        if (chunk == null) {
            // It's the head.
            return true;
        }
        PoolSubpage<T> head = chunk.arena.smallSubpagePools[headIndex];
        head.lock();
        try {
            return doNotDestroy;
        } finally {
            head.unlock();
        }
    }

    void destroy() {
        if (chunk != null) {
            chunk.destroy();
        }
    }

    void lock() {
        lock.lock();
    }

    void unlock() {
        lock.unlock();
    }
}
