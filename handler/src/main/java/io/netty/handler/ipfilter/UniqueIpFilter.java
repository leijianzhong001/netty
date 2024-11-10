/*
 * Copyright 2014 The Netty Project
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
package io.netty.handler.ipfilter;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.internal.ConcurrentSet;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Set;

/**
 * This class allows one to ensure that at all times for every IP address there is at most one
 * {@link Channel} connected to the server.
 */
@ChannelHandler.Sharable
public class UniqueIpFilter extends AbstractRemoteAddressFilter<InetSocketAddress> {

    private final Set<InetAddress> connected = new ConcurrentSet<InetAddress>();

    @Override
    protected boolean accept(ChannelHandlerContext ctx, InetSocketAddress remoteAddress) throws Exception {
        // 唯一ip地址过滤的实现是，一个ip地址只允许有一个连接，如果有多个连接，后面的连接会被拒绝
        final InetAddress remoteIp = remoteAddress.getAddress();
        if (!connected.add(remoteIp)) {
            return false;
        } else {
            ctx.channel().closeFuture().addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    // 注册一个监听器，当连接关闭时，将ip地址从集合中移除，一遍该地址可以重新连接都当前服务
                    connected.remove(remoteIp);
                }
            });
            return true;
        }
    }
}
