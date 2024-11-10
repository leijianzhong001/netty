package io.netty;

import io.netty.channel.*;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;


public class KeepaliveHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent idleStateEvent = (IdleStateEvent) evt;
            IdleState state = idleStateEvent.state();
            if (state == IdleState.WRITER_IDLE){
                // 因为有StringEncoder,所以写出 String 数据即可
                ctx.writeAndFlush("heartbeat: ");
            }
        }
    }
}
