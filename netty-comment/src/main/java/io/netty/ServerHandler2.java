package io.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;


public class ServerHandler2 extends SimpleChannelInboundHandler<String> {
    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(ServerHandler2.class);

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String msg) throws Exception {
        if (ctx.channel().isActive() && ctx.channel().isWritable()){
            ctx.writeAndFlush("received your message: " + msg);
        } else {
            logger.warn("channel is not active or writable, drop write message: {}", msg);
        }
    }
}
