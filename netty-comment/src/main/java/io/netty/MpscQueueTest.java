package io.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.internal.PlatformDependent;

import java.util.Queue;
import java.util.stream.IntStream;

public class MpscQueueTest {
    public static void main(String[] args) throws InterruptedException {
        // 未指定容量的情况下，实际的类型是 MpscUnboundedArrayQueue
        Queue<ByteBuf> byteBufs = PlatformDependent.<ByteBuf>newMpscQueue();
        for (int i = 0; i < 10240; i++) {
            ByteBuf buffer = Unpooled.buffer();
            // byte的正常表示范围是-128~127，这里的j一旦大于127，就会变成负数，不会超出byte范围，所以长度还是一个字节
            IntStream.range(1, 1024).forEach(buffer::writeByte);
            byteBufs.offer(buffer);
        }

        // MpscQueue长度最小会被指定为 2048，小了没用，所以3000
        // 指定了容量的情况下，有unsafe时，实际的类型是 MpscChunkedArrayQueue
        Queue<ByteBuf> byteBufs2 = PlatformDependent.<ByteBuf>newMpscQueue(3000);
        for (int i = 0; i < 10240; i++) {
            ByteBuf buffer = Unpooled.buffer();
            // byte的正常表示范围是-128~127，这里的j一旦大于127，就会变成负数，不会超出byte范围，所以长度还是一个字节
            IntStream.range(1, 1024).forEach(buffer::writeByte);
            // 一旦超过了最大长度，这里就会阻塞
            boolean offer = byteBufs2.offer(buffer);
            if (!offer) {
                System.out.println("MpscQueue已满");
            }
        }
    }
}
