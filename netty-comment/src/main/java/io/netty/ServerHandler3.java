package io.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;


public class ServerHandler3 extends ChannelInboundHandlerAdapter {
    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(ServerHandler3.class);

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ByteBuf reqMsg = (ByteBuf)msg;
        ctx.writeAndFlush("i see that: " + reqMsg.toString());
    }
}
