package io.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;

public class UnpooledBufferVsPooledBuffer {
    public static void main(String[] args) {
        PooledByteBufAllocator pooledByteBufAllocator = new PooledByteBufAllocator(false);
        long start = System.currentTimeMillis();
        for (int i = 0; i < 100000000; i++) {
            ByteBuf buffer = pooledByteBufAllocator.buffer(1024 * 10);
            buffer.release();
        }
        System.out.println("PooledByteBufAllocator:" + (System.currentTimeMillis() - start));

        start = System.currentTimeMillis();
        for (int i = 0; i < 100000000; i++) {
            ByteBuf buffer = Unpooled.buffer(1024 * 10);
            buffer.release();
        }
        System.out.println("Unpooled:" + (System.currentTimeMillis() - start));
    }
}
