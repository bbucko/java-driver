/*
 * Copyright (C) 2017-2017 DataStax Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.oss.driver.internal.core.channel;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.ProtocolVersion;
import com.datastax.oss.driver.api.core.config.CoreDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfig;
import com.datastax.oss.driver.api.core.config.DriverConfigProfile;
import com.datastax.oss.driver.internal.core.DriverContext;
import com.datastax.oss.driver.internal.core.NettyOptions;
import com.datastax.oss.driver.internal.core.ProtocolVersionRegistry;
import com.datastax.oss.driver.internal.core.protocol.ByteBufPrimitiveCodec;
import com.datastax.oss.driver.internal.core.ssl.SslHandlerFactory;
import com.datastax.oss.protocol.internal.Compressor;
import com.datastax.oss.protocol.internal.Frame;
import com.datastax.oss.protocol.internal.FrameCodec;
import com.datastax.oss.protocol.internal.Message;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.fail;

/**
 * Sets up the infrastructure for channel factory tests.
 *
 * <p>Because the factory manages channel creation itself, {@link
 * io.netty.channel.embedded.EmbeddedChannel} is not suitable. Instead, we launch an embedded server
 * and connect to it with the local transport.
 *
 * <p>The current implementation assumes that only one connection will be tested at a time, but
 * support for multiple simultaneous connections could easily be added: store multiple instances of
 * requestFrameExchanger and serverResponseChannel, and add a parameter to readOutboundFrame and
 * writeInboundFrame (for instance the position of the connection in creation order) to specify
 * which instance to use.
 */
abstract class ChannelFactoryTestBase {
  static final LocalAddress SERVER_ADDRESS =
      new LocalAddress(ChannelFactoryTestBase.class.getSimpleName() + "-server");

  DefaultEventLoopGroup serverGroup;
  DefaultEventLoopGroup clientGroup;

  @Mock DriverContext driverContext;
  @Mock DriverConfig driverConfig;
  @Mock DriverConfigProfile defaultConfigProfile;
  @Mock NettyOptions nettyOptions;
  @Mock ProtocolVersionRegistry protocolVersionRegistry;

  // The server's I/O thread will store the last received request here, and block until the test
  // thread retrieves it. This assumes readOutboundFrame() is called for each actual request, else
  // the test will hang forever.
  private final Exchanger<Frame> requestFrameExchanger = new Exchanger<>();

  // The channel that accepts incoming connections on the server
  private LocalServerChannel serverAcceptChannel;
  // The channel to send responses to the last open connection
  private volatile LocalChannel serverResponseChannel;

  @BeforeMethod
  public void setup() throws InterruptedException {
    MockitoAnnotations.initMocks(this);

    serverGroup = new DefaultEventLoopGroup(1);
    clientGroup = new DefaultEventLoopGroup(1);

    Mockito.when(driverContext.config()).thenReturn(driverConfig);
    Mockito.when(driverConfig.defaultProfile()).thenReturn(defaultConfigProfile);
    Mockito.when(defaultConfigProfile.isDefined(CoreDriverOption.AUTHENTICATION_PROVIDER_CLASS))
        .thenReturn(false);
    Mockito.when(
            defaultConfigProfile.getDuration(
                CoreDriverOption.CONNECTION_INIT_QUERY_TIMEOUT, TimeUnit.MILLISECONDS))
        .thenReturn(100L);
    Mockito.when(
            defaultConfigProfile.getDuration(
                CoreDriverOption.CONNECTION_SET_KEYSPACE_TIMEOUT, MILLISECONDS))
        .thenReturn(100L);
    Mockito.when(defaultConfigProfile.getInt(CoreDriverOption.CONNECTION_MAX_REQUESTS))
        .thenReturn(1);

    Mockito.when(driverContext.protocolVersionRegistry()).thenReturn(protocolVersionRegistry);
    Mockito.when(driverContext.nettyOptions()).thenReturn(nettyOptions);
    Mockito.when(nettyOptions.ioEventLoopGroup()).thenReturn(clientGroup);
    Mockito.when(nettyOptions.channelClass()).thenAnswer((Answer<Object>) i -> LocalChannel.class);
    Mockito.when(nettyOptions.allocator()).thenReturn(ByteBufAllocator.DEFAULT);
    Mockito.when(driverContext.frameCodec())
        .thenReturn(
            FrameCodec.defaultClient(
                new ByteBufPrimitiveCodec(ByteBufAllocator.DEFAULT), Compressor.none()));
    Mockito.when(driverContext.sslHandlerFactory()).thenReturn(SslHandlerFactory.NONE);

    // Start local server
    ServerBootstrap serverBootstrap =
        new ServerBootstrap()
            .group(serverGroup)
            .channel(LocalServerChannel.class)
            .localAddress(SERVER_ADDRESS)
            .childHandler(new ServerInitializer());
    ChannelFuture channelFuture = serverBootstrap.bind().sync();
    serverAcceptChannel = (LocalServerChannel) channelFuture.sync().channel();
  }

  // Sets up the pipeline for our local server
  private class ServerInitializer extends ChannelInitializer<LocalChannel> {
    @Override
    protected void initChannel(LocalChannel ch) throws Exception {
      // Install a single handler that stores received requests, so that the test can check what
      // the client sent
      ch.pipeline()
          .addLast(
              new ChannelInboundHandlerAdapter() {
                @Override
                @SuppressWarnings("unchecked")
                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                  super.channelRead(ctx, msg);
                  requestFrameExchanger.exchange((Frame) msg);
                }
              });

      // Store the channel so that the test can send responses back to the client
      serverResponseChannel = ch;
    }
  }

  protected Frame readOutboundFrame() {
    try {
      return requestFrameExchanger.exchange(null, 100, MILLISECONDS);
    } catch (InterruptedException e) {
      fail("unexpected interruption while waiting for outbound frame", e);
    } catch (TimeoutException e) {
      fail("Timed out reading outbound frame");
    }
    return null; // never reached
  }

  protected void writeInboundFrame(Frame requestFrame, Message response) {
    writeInboundFrame(requestFrame, response, requestFrame.protocolVersion);
  }

  private void writeInboundFrame(Frame requestFrame, Message response, int protocolVersion) {
    serverResponseChannel.writeAndFlush(
        Frame.forResponse(
            protocolVersion,
            requestFrame.streamId,
            null,
            Frame.NO_PAYLOAD,
            Collections.emptyList(),
            response));
  }

  ChannelFactory newChannelFactory() {
    return new TestChannelFactory(driverContext);
  }

  // A simplified channel factory to use in the tests.
  // It only installs high-level handlers on the pipeline, not the frame codecs. So we'll receive
  // Frame objects on the server side, which is simpler to test.
  private static class TestChannelFactory extends ChannelFactory {

    private TestChannelFactory(DriverContext driverContext) {
      super(driverContext);
    }

    @Override
    ChannelInitializer<Channel> initializer(
        SocketAddress address, ProtocolVersion protocolVersion, CqlIdentifier keyspace) {
      return new ChannelInitializer<Channel>() {
        @Override
        protected void initChannel(Channel channel) throws Exception {
          DriverConfigProfile defaultConfigProfile = driverContext.config().defaultProfile();

          long setKeyspaceTimeoutMillis =
              defaultConfigProfile.getDuration(
                  CoreDriverOption.CONNECTION_SET_KEYSPACE_TIMEOUT, MILLISECONDS);
          int maxRequestsPerConnection =
              defaultConfigProfile.getInt(CoreDriverOption.CONNECTION_MAX_REQUESTS);

          InFlightHandler inFlightHandler =
              new InFlightHandler(
                  protocolVersion,
                  new StreamIdGenerator(maxRequestsPerConnection),
                  setKeyspaceTimeoutMillis);
          ProtocolInitHandler initHandler =
              new ProtocolInitHandler(driverContext, protocolVersion, clusterName, keyspace);
          channel.pipeline().addLast("inflight", inFlightHandler).addLast("init", initHandler);
        }
      };
    }
  }

  @AfterMethod
  public void tearDown() throws InterruptedException {
    serverAcceptChannel.close();

    serverGroup.shutdownGracefully(100, 100, TimeUnit.MILLISECONDS).sync();
    clientGroup.shutdownGracefully(100, 100, TimeUnit.MILLISECONDS).sync();
  }
}