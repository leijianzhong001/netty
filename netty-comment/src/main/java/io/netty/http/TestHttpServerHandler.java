package io.netty.http;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;

public class TestHttpServerHandler extends SimpleChannelInboundHandler<HttpObject> {
    /**
     * 指定如何读取客户端数据
     * 所有类型的 `HTTP `消息（`FullHttpRequest`、 `LastHttpContent` ）都实现了 `HttpObject`接口
     * @param ctx 上下文
     * @param msg 客户端传来的消息
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg){
        if (msg instanceof HttpRequest) {
            HttpRequest httpRequest = (HttpRequest)msg;
            System.out.println("收到http消息头：" + msg.getClass());
            System.out.println("http消息投携带的url: " + httpRequest.uri());
            System.out.println("http消息头内容：" + httpRequest.headers());
            System.out.println("http消息头携带的请求方法：" + httpRequest.method());
        } else if (msg instanceof LastHttpContent){
            LastHttpContent lastHttpContent = (LastHttpContent)msg;
            System.out.println("收到http消息结束部分: " + msg.getClass());
            System.out.println("收到http消息结束部分携带的消息头: " + lastHttpContent.trailingHeaders());
            ByteBuf content = lastHttpContent.content();
            byte[] bytes = new byte[content.readableBytes()];
            content.readBytes(bytes);
            System.out.println("收到http消息结束部分携带的消息体内容: " + new String(bytes));
        } else if (msg instanceof HttpContent){
            HttpContent httpContent = (HttpContent)msg;
            System.out.println("收到http消息体部分: " + msg.getClass());
            ByteBuf content = httpContent.content();
            byte[] bytes = new byte[content.readableBytes()];
            content.readBytes(bytes);
            System.out.println("收到http消息体部分内容: " + new String(bytes));
        }
    }
}
