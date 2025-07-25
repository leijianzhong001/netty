/*
 * Copyright 2019 The Netty Project
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
package io.netty.util.internal;

import static io.netty.util.internal.ObjectUtil.checkPositive;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import io.netty.util.IllegalReferenceCountException;
import io.netty.util.ReferenceCounted;

/**
 * Common logic for {@link ReferenceCounted} implementations
 */
public abstract class ReferenceCountUpdater<T extends ReferenceCounted> {
    /*
     * Implementation notes:
     *
     * For the updated int field:
     *   Even => "real" refcount is (refCnt >>> 1)
     *   Odd  => "real" refcount is 0
     *
     * (x & y) appears to be surprisingly expensive relative to (x == y). Thus this class uses
     * a fast-path in some places for most common low values when checking for live (even) refcounts,
     * for example: if (rawCnt == 2 || rawCnt == 4 || (rawCnt & 1) == 0) { ...
     */

    protected ReferenceCountUpdater() { }

    public static long getUnsafeOffset(Class<? extends ReferenceCounted> clz, String fieldName) {
        try {
            if (PlatformDependent.hasUnsafe()) {
                return PlatformDependent.objectFieldOffset(clz.getDeclaredField(fieldName));
            }
        } catch (Throwable ignore) {
            // fall-back
        }
        return -1;
    }

    protected abstract AtomicIntegerFieldUpdater<T> updater();

    protected abstract long unsafeOffset();

    public final int initialValue() {
        return 2;
    }

    public void setInitialValue(T instance) {
        final long offset = unsafeOffset();
        if (offset == -1) {
            updater().set(instance, initialValue());
        } else {
            PlatformDependent.safeConstructPutInt(instance, offset, initialValue());
        }
    }

    private static int realRefCnt(int rawCnt) {
        return rawCnt != 2 && rawCnt != 4 && (rawCnt & 1) != 0 ? 0 : rawCnt >>> 1;
    }

    /**
     * Like {@link #realRefCnt(int)} but throws if refCnt == 0
     */
    private static int toLiveRealRefCnt(int rawCnt, int decrement) {
        if (rawCnt == 2 || rawCnt == 4 || (rawCnt & 1) == 0) {
            return rawCnt >>> 1;
        }
        // odd rawCnt => already deallocated
        throw new IllegalReferenceCountException(0, -decrement);
    }

    private int nonVolatileRawCnt(T instance) {
        // TODO: Once we compile against later versions of Java we can replace the Unsafe usage here by varhandles.
        final long offset = unsafeOffset();
        return offset != -1 ? PlatformDependent.getInt(instance, offset) : updater().get(instance);
    }

    public final int refCnt(T instance) {
        return realRefCnt(updater().get(instance));
    }

    public final boolean isLiveNonVolatile(T instance) {
        final long offset = unsafeOffset();
        final int rawCnt = offset != -1 ? PlatformDependent.getInt(instance, offset) : updater().get(instance);

        // The "real" ref count is > 0 if the rawCnt is even.
        return rawCnt == 2 || rawCnt == 4 || rawCnt == 6 || rawCnt == 8 || (rawCnt & 1) == 0;
    }

    /**
     * An unsafe operation that sets the reference count directly
     */
    public final void setRefCnt(T instance, int refCnt) {
        updater().set(instance, refCnt > 0 ? refCnt << 1 : 1); // overflow OK here
    }

    /**
     * Resets the reference count to 1
     */
    public final void resetRefCnt(T instance) {
        // no need of a volatile set, it should happen in a quiescent state
        updater().lazySet(instance, initialValue());
    }

    public final T retain(T instance) {
        return retain0(instance, 1, 2);
    }

    public final T retain(T instance, int increment) {
        // all changes to the raw count are 2x the "real" change - overflow is OK
        // 原始计数的所有变化都是“实际”变化的2倍。为了提高效率，refCnt的实际值都是偶数
        int rawIncrement = checkPositive(increment, "increment") << 1;
        return retain0(instance, increment, rawIncrement);
    }

    // rawIncrement == increment << 1
    private T retain0(T instance, final int increment, final int rawIncrement) {
        // 通过CAS方式递增并获取原值
        int oldRef = updater().getAndAdd(instance, rawIncrement);
        // (oldRef & 1) != 0 判断奇偶，正常情况这里应该都是偶数。这里如果不是偶数，则抛出异常
        if (oldRef != 2 && oldRef != 4 && (oldRef & 1) != 0) {
            throw new IllegalReferenceCountException(0, increment);
        }
        // don't pass 0!
        if ((oldRef <= 0 && oldRef + rawIncrement >= 0)
                || (oldRef >= 0 && oldRef + rawIncrement < oldRef)) {
            // overflow case
            updater().getAndAdd(instance, -rawIncrement);
            throw new IllegalReferenceCountException(realRefCnt(oldRef), increment);
        }
        return instance;
    }

    public final boolean release(T instance) {
        int rawCnt = nonVolatileRawCnt(instance);
        // 源码中release的具体实现要注意的是由于引用计数以2倍递增，所以引用次数 = 引用计数/2，当 decrement=refcnt/2 也就是引用次数=释放次数时， 代表ByteBuf不再被引用，执行内存释放或放回内存池的操作
        // 1、rawCnt = 2 表示当前Bytebuf只被引用了一次，所以 tryFinalRelease0 尝试直接对引用计数器置为1（1除以2等于0，所以相当于引用次数变为0），以意图直接释放ByteBuf
        // 2、如果 tryFinalRelease0 释放失败，说明在真正执行的时候，引用计数器不是2了，则 retryRelease0 尝试将引用次数-1，并返回true
        // 3、如果引用计数器不等于2，说明有多个实际引用，则 nonFinalRelease0 尝试将实际引用次数-1，并返回true
        return rawCnt == 2 ? tryFinalRelease0(instance, 2) || retryRelease0(instance, 1)
                : nonFinalRelease0(instance, 1, rawCnt, toLiveRealRefCnt(rawCnt, 1));
    }

    public final boolean release(T instance, int decrement) {
        int rawCnt = nonVolatileRawCnt(instance);
        // 对计数器进行除以2操作,得到实际的引用次数
        int realCnt = toLiveRealRefCnt(rawCnt, checkPositive(decrement, "decrement"));
        return decrement == realCnt ? tryFinalRelease0(instance, rawCnt) || retryRelease0(instance, decrement)
                : nonFinalRelease0(instance, decrement, rawCnt, realCnt);
    }

    private boolean tryFinalRelease0(T instance, int expectRawCnt) {
        return updater().compareAndSet(instance, expectRawCnt, 1); // any odd number will work
    }

    private boolean nonFinalRelease0(T instance, int decrement, int rawCnt, int realCnt) {
        if (decrement < realCnt
                // all changes to the raw count are 2x the "real" change - overflow is OK
                && updater().compareAndSet(instance, rawCnt, rawCnt - (decrement << 1))) {
            // 递减引用计数器
            return false;
        }
        // 如果 compareAndSet 失败，则尝试进行完全释放，即将实际引用次数置为0（引用计数器置为1）
        return retryRelease0(instance, decrement);
    }

    private boolean retryRelease0(T instance, int decrement) {
        for (;;) {
            // 注意，参数中的 decrement 表示本次要实际释放的引用次数，释放一次引用就是1
            // rawCnt 表示引用计数器的值，是实际引用次数 * 2
            // realCnt表示实际的引用次数。toLiveRealRefCnt 对计数器进行除以2操作,得到实际的引用次数
            int rawCnt = updater().get(instance), realCnt = toLiveRealRefCnt(rawCnt, decrement);
            // 这里如注意 你传入的减值参数 decrement = realCnt 时 等同于 引用次数=释放次数，直接进行释放操作
            if (decrement == realCnt) {
                // CAS方式置为1,返回true，执行后面的内存释放操作
                if (tryFinalRelease0(instance, rawCnt)) {
                    return true;
                }
            } else if (decrement < realCnt) {
                // 如果decrement 小于 realCnt，通过CAS方式减去 decrement * 2
                // all changes to the raw count are 2x the "real" change
                if (updater().compareAndSet(instance, rawCnt, rawCnt - (decrement << 1))) {
                    return false;
                }
            } else {
                throw new IllegalReferenceCountException(realCnt, -decrement);
            }
            Thread.yield(); // this benefits throughput under high contention
        }
    }
}
