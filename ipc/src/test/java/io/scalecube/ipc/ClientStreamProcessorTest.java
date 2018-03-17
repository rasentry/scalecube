package io.scalecube.ipc;

import static io.scalecube.ipc.ServiceMessage.copyFrom;
import static org.junit.Assert.assertEquals;

import io.scalecube.transport.Address;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import rx.observers.AssertableSubscriber;

import java.io.IOException;
import java.net.ConnectException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

public class ClientStreamProcessorTest {

  private static final Duration TIMEOUT = Duration.ofMillis(1000);
  private static final int CONNECT_TIMEOUT_MILLIS = (int) TIMEOUT.toMillis();

  private ClientStream clientStream;
  private ListeningServerStream listeningServerStream;
  private ClientStreamProcessorFactory clientStreamProcessorFactory;
  private Address address;
  private Bootstrap bootstrap;

  @Before
  public void setUp() {
    bootstrap = new Bootstrap()
        .group(new NioEventLoopGroup(0))
        .channel(NioSocketChannel.class)
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
        .option(ChannelOption.TCP_NODELAY, true)
        .option(ChannelOption.SO_KEEPALIVE, true)
        .option(ChannelOption.SO_REUSEADDR, true);

    clientStream = ClientStream.newClientStream(bootstrap);
    clientStreamProcessorFactory = ClientStreamProcessorFactory.newClientStreamProcessorFactory(clientStream);

    listeningServerStream = ListeningServerStream.newServerStream().withListenAddress("localhost").bind();
    address = listeningServerStream.listenBind().single().toBlocking().first();

    // setup echo service
    listeningServerStream.listenReadSuccess()
        .map(Event::getMessageOrThrow)
        .filter(message -> "q/echo".equalsIgnoreCase(message.getQualifier()))
        .subscribe(message -> {
          // send original message back to client then send onCompleted
          listeningServerStream.send(copyFrom(message).build());
          listeningServerStream.send(copyFrom(message).qualifier(Qualifier.Q_ON_COMPLETED).build());
        });

    // setup echo service replying with void
    listeningServerStream.listenReadSuccess()
        .map(Event::getMessageOrThrow)
        .filter(message -> "q/echoVoid".equalsIgnoreCase(message.getQualifier()))
        .subscribe(message -> {
          // just send onCompleted
          listeningServerStream.send(copyFrom(message).qualifier(Qualifier.Q_ON_COMPLETED).build());
        });

    // setup error service
    listeningServerStream.listenReadSuccess()
        .map(Event::getMessageOrThrow)
        .filter(message -> "q/echoError".equalsIgnoreCase(message.getQualifier()))
        .subscribe(message -> {
          // respond with error
          listeningServerStream.send(copyFrom(message).qualifier(Qualifier.Q_GENERAL_FAILURE).build());
        });

    // setup service with several responses with onCompleted message following everyting sent
    listeningServerStream.listenReadSuccess()
        .map(Event::getMessageOrThrow)
        .filter(message -> "q/echoStream".equalsIgnoreCase(message.getQualifier()))
        .subscribe(message -> {
          // respond with several response messages then send onCompleted
          IntStream.rangeClosed(1, 42)
              .forEach(i -> listeningServerStream.send(copyFrom(message).qualifier("q/" + i).build()));
          listeningServerStream.send(copyFrom(message).qualifier(Qualifier.Q_ON_COMPLETED).build());
        });
  }

  @After
  public void cleanUp() {
    clientStreamProcessorFactory.close();
    clientStream.close();
    listeningServerStream.close();
    listeningServerStream.listenUnbind().single().toBlocking().first();
    bootstrap.group().shutdownGracefully();
  }

  @Test
  public void testEcho() throws Exception {
    StreamProcessor streamProcessor = clientStreamProcessorFactory.newClientStreamProcessor(address);
    try {
      AssertableSubscriber<ServiceMessage> subscriber = streamProcessor.listen().test();
      streamProcessor.onNext(ServiceMessage.withQualifier("q/echo").build());
      subscriber
          .awaitTerminalEventAndUnsubscribeOnTimeout(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
          .awaitValueCount(1, TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
          .assertCompleted();
    } finally {
      streamProcessor.close();
    }
  }

  @Test
  public void testEchoVoid() throws Exception {
    StreamProcessor streamProcessor = clientStreamProcessorFactory.newClientStreamProcessor(address);
    try {
      AssertableSubscriber<ServiceMessage> subscriber = streamProcessor.listen().test();
      streamProcessor.onNext(ServiceMessage.withQualifier("q/echoVoid").build());
      subscriber
          .awaitTerminalEventAndUnsubscribeOnTimeout(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
          .assertCompleted()
          .assertNoValues();
    } finally {
      streamProcessor.close();
    }
  }

  @Test
  public void testEchoError() throws Exception {
    StreamProcessor streamProcessor = clientStreamProcessorFactory.newClientStreamProcessor(address);
    try {
      AssertableSubscriber<ServiceMessage> subscriber = streamProcessor.listen().test();
      streamProcessor.onNext(ServiceMessage.withQualifier("q/echoError").build());
      subscriber
          .awaitTerminalEventAndUnsubscribeOnTimeout(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
          .assertNoValues()
          .assertError(RuntimeException.class);
    } finally {
      streamProcessor.close();
    }
  }

  @Test
  public void testEchoStream() throws Exception {
    StreamProcessor streamProcessor = clientStreamProcessorFactory.newClientStreamProcessor(address);
    try {
      AssertableSubscriber<ServiceMessage> subscriber = streamProcessor.listen().test();
      streamProcessor.onNext(ServiceMessage.withQualifier("q/echoStream").build());
      subscriber
          .awaitTerminalEventAndUnsubscribeOnTimeout(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
          .awaitValueCount(42, TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
          .assertCompleted();
    } finally {
      streamProcessor.close();
    }
  }

  @Test
  public void testListenFailedWhenSendFailed() throws Exception {
    Address failingAddress = Address.from("localhost:0");
    StreamProcessor streamProcessor = clientStreamProcessorFactory.newClientStreamProcessor(failingAddress);
    try {
      AssertableSubscriber<ServiceMessage> subscriber = streamProcessor.listen().test();
      streamProcessor.onNext(ServiceMessage.withQualifier("q/echo").build());
      subscriber
          .awaitTerminalEventAndUnsubscribeOnTimeout(CONNECT_TIMEOUT_MILLIS * 2, TimeUnit.MILLISECONDS)
          .assertNoValues()
          .assertError(ConnectException.class);
    } finally {
      streamProcessor.close();
    }
  }

  @Test
  public void testListenFailedWhenRemotePartyClosed() throws Exception {
    StreamProcessor streamProcessor = clientStreamProcessorFactory.newClientStreamProcessor(address);
    try {
      // send and receive echo message
      AssertableSubscriber<ServiceMessage> subscriber = streamProcessor.listen().test();
      streamProcessor.onNext(ServiceMessage.withQualifier("q/echo").build());
      subscriber
          .awaitTerminalEventAndUnsubscribeOnTimeout(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
          .awaitValueCount(1, TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
          .assertCompleted();

      // close remote server stream
      AssertableSubscriber<ServiceMessage> subscriber1 = streamProcessor.listen().test();
      listeningServerStream.close();
      Address unbindAddress = listeningServerStream.listenUnbind().single().toBlocking().first();
      assertEquals(address, unbindAddress);

      // wait few seconds (it's not determined how long
      // connecting party, i.e. ClientStream, should wait for signal that remote has closed socket)
      subscriber1
          .awaitTerminalEventAndUnsubscribeOnTimeout(3, TimeUnit.SECONDS)
          .assertNoValues()
          .assertError(IOException.class);
    } finally {
      streamProcessor.close();
    }
  }
}
