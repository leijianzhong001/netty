package io.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


public class MemoryLeakTestServerHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(MemoryLeakTestServerHandler.class);
    ExecutorService executorService = Executors.newFixedThreadPool(8) ;
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ByteBuf reqMsg = (ByteBuf)msg;
        int hashCode = System.identityHashCode(reqMsg);
        System.out.println("server receive data size: " + reqMsg.readableBytes() + " hashCode: " + hashCode);
        // 加上释放操作，内存使用就趋于平稳了
        ReferenceCountUtil.release(reqMsg);
        ctx.writeAndFlush(reqMsg);
        executorService.execute(() -> {
            byte[] req = new byte[1024 * 1024];
            try { TimeUnit.MILLISECONDS.sleep(2000);} catch (InterruptedException ignored) {}
        });

    }
}
