package io.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.CharsetUtil;
import io.netty.util.internal.SystemPropertyUtil;

import java.util.Scanner;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public class MemoryLeakTestClient {

    private static volatile boolean need_wait = false;

    public static void main(String[] args) throws InterruptedException {
        // io.netty.eventLoop.maxPendingTasks 设置 maxPendingTasks 限制客户端 taskQueue 大小，否则的话如果客户端使用 NioEventLoop 之外的线程全速写入数据的话，会造成taskQueue一直膨胀，直到OOM
        // 设置 maxPendingTasks，一旦超过改值，会触发任务队列溢出的异常
        // System.setProperty("io.netty.eventLoop.maxPendingTasks", "50000");
        // 客户端线程池
        NioEventLoopGroup eventExecutors = new NioEventLoopGroup();

        try {
            // 创建客户端启动对象
            // 注意客户但启动对象使用的是 Bootstrap 而不是 ServerBootstrap
            Bootstrap bootstrap = new Bootstrap();

            // 设置客户端启动参数
            bootstrap.group(eventExecutors)
                    .channel(NioSocketChannel.class) // 设置客户端通道的实现(反射)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ch.pipeline().addLast(new MemoryLeakTestClientHandler());
                        }
                    });

            System.out.println("Netty client is ok...");

            /*
             * 下面这两步都涉及到Netty的异步模型
             */
            // 启动客户端去连接服务器,sync操作会等待当前future完成之后再返回
            ChannelFuture channelFuture = bootstrap.connect("127.0.0.1", 4444).sync();
            Channel channel = channelFuture.channel();
            // 设置channel的高水位线 10M
            channel.config().setWriteBufferHighWaterMark(1024 * 1024 * 100);
            try { TimeUnit.MILLISECONDS.sleep(30); } catch (InterruptedException ignored) {}
            while (true) {
                ByteBuf buffer = Unpooled.buffer(1024);
                for (int i = 0; i < buffer.capacity(); i++) {
                    buffer.writeByte(i);
                }

                // 等待写入到 taskQueue 成功，因此这里可以阻塞等待，不会降低写入效率
                // 不能使用 addListener 的方式控制是否写入，因为在main方法中执行的addListener可能在addListener时就发现future已经完成了，此时会将 notifyListenersNow 操作提交到 taskQueue 中执行
                // 但此时 taskQueue 已经满了，因此会在提交时 RejectedExecutionException 异常
                // 也不能使用 await 等待，因为 ChannelFuture 的结果只有在真正flush完成的时候才会被设置，如果 await 的话，会严重降低写入效率
                if (!channel.isWritable()){
                    System.out.println(Thread.currentThread().getName() + ": taskQueue is full, wait a moment...");
                    try { TimeUnit.MILLISECONDS.sleep(1); } catch (InterruptedException ignored) {}
                    continue;
                }
                channel.writeAndFlush(buffer);
            }
        } finally {
            eventExecutors.shutdownGracefully();
        }

    }
}
