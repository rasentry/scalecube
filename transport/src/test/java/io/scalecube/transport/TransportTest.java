package io.scalecube.transport;

import static com.google.common.base.Throwables.propagate;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.scalecube.testlib.BaseTest;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import io.netty.channel.ConnectTimeoutException;

import org.junit.After;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Subscriber;

import java.net.BindException;
import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.UnresolvedAddressException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.stream.LongStream;

public class TransportTest extends BaseTest {
  private static final Logger LOGGER = LoggerFactory.getLogger(TransportTest.class);

  private Transport client;
  private Transport server;

  @After
  public void tearDown() throws Exception {
    destroyTransport(client);
    destroyTransport(server);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testInvalidListenConfig() {
    Transport transport = null;
    try {
      TransportConfig config = TransportConfig.builder().listenInterface("eth0").listenAddress("10.10.10.10").build();
      transport = Transport.bindAwait(config);
    } finally {
      if (transport != null) {
        transport.stop();
      }
    }
  }

  @Test(expected = IllegalArgumentException.class)
  public void testInvalidListenInterface() {
    Transport transport = null;
    try {
      TransportConfig config = TransportConfig.builder().listenInterface("yadayada").build();
      transport = Transport.bindAwait(config);
    } finally {
      if (transport != null) {
        transport.stop();
      }
    }
  }

  @Test(expected = IllegalArgumentException.class)
  public void testInvalidListenAddress() {
    Transport transport = null;
    try {
      TransportConfig config = TransportConfig.builder().listenAddress("0.0.0.0").build();
      transport = Transport.bindAwait(config);
    } finally {
      if (transport != null) {
        transport.stop();
      }
    }
  }

  @Test
  public void testPortAutoIncrementRaceConditions() throws Exception {
    int count = 30;
    TransportConfig config = TransportConfig.builder()
        .port(6000)
        .portAutoIncrement(true)
        .portCount(count)
        .build();

    Map<CompletableFuture<Transport>, Boolean> transports = new ConcurrentHashMap<>();
    ExecutorService executor = Executors.newFixedThreadPool(4);
    for (int i = 0; i < count; i++) {
      executor.execute(() -> transports.put(Transport.bind(config), true));
    }
    executor.shutdown();
    executor.awaitTermination(60, TimeUnit.SECONDS);

    CompletableFuture<Void> allFuturesResult =
        CompletableFuture.allOf(transports.keySet().toArray(new CompletableFuture[transports.size()]));

    try {
      allFuturesResult.get(60, TimeUnit.SECONDS);
    } finally {
      for (CompletableFuture<Transport> transportFuture : transports.keySet()) {
        if (transportFuture.isDone()) {
          transportFuture.get().stop();
        }
      }
    }
  }

  @Test
  public void testBindExceptionWithoutPortAutoIncrement() throws Exception {
    TransportConfig config = TransportConfig.builder()
        .port(6000)
        .portAutoIncrement(false)
        .portCount(100)
        .build();


    Transport transport1 = null;
    Transport transport2 = null;
    try {
      transport1 = Transport.bindAwait(config);
      transport2 = Transport.bindAwait(config);
    } catch (Throwable throwable) {
      // Check that get address already in use exception
      assertTrue(throwable instanceof BindException || throwable.getMessage().contains("Address already in use"));
      return;
    } finally {
        if (transport1 != null) {
          transport1.stop();
        }
        if (transport2 != null) {
          transport2.stop();
        }
    }
    fail("Didn't get expected bind exception");
  }

  @Test
  public void testValidListenAddress() {
    Transport transport = null;
    try {
      TransportConfig config = TransportConfig.builder().listenAddress("127.0.0.1").build();
      transport = Transport.bindAwait(config);
    } finally {
      if (transport != null) {
        transport.stop();
      }
    }
  }

  @Test
  public void testUnresolvedHostConnection() throws Exception {
    client = createTransport();
    // create transport with wrong host
    CompletableFuture<Void> sendPromise0 = new CompletableFuture<>();
    client.send(Address.from("wronghost:49255"), Message.fromData("q"), sendPromise0);
    try {
      sendPromise0.get(5, TimeUnit.SECONDS);
      fail();
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      assertNotNull(cause);
      assertAmongExpectedClasses(cause.getClass(), UnresolvedAddressException.class);
    }
  }

  @Test
  public void testInteractWithNoConnection() throws Exception {
    Address serverAddress = Address.from("localhost:49255");
    for (int i = 0; i < 10; i++) {
      LOGGER.info("####### {} : iteration = {}", testName.getMethodName(), i);

      client = createTransport();

      // create transport and don't wait just send message
      CompletableFuture<Void> sendPromise0 = new CompletableFuture<>();
      client.send(serverAddress, Message.fromData("q"), sendPromise0);
      try {
        sendPromise0.get(3, TimeUnit.SECONDS);
        fail();
      } catch (ExecutionException e) {
        Throwable cause = e.getCause();
        assertNotNull(cause);
        assertAmongExpectedClasses(cause.getClass(),
            ClosedChannelException.class, ConnectException.class, ConnectTimeoutException.class);
      }

      // send second message: no connection yet and it's clear that there's no connection
      CompletableFuture<Void> sendPromise1 = new CompletableFuture<>();
      client.send(serverAddress, Message.fromData("q"), sendPromise1);
      try {
        sendPromise1.get(3, TimeUnit.SECONDS);
        fail();
      } catch (ExecutionException e) {
        Throwable cause = e.getCause();
        assertNotNull(cause);
        assertAmongExpectedClasses(cause.getClass(),
            ClosedChannelException.class, ConnectException.class, ConnectTimeoutException.class);
      }

      destroyTransport(client);
    }
  }

  @Test
  public void testPingPongClientTFListenAndServerTFListen() throws Exception {
    client = createTransport();
    server = createTransport();

    server.listen().subscribe(message -> {
      Address address = message.sender();
      assertEquals("Expected clientAddress", client.address(), address);
      send(server, address, Message.fromQualifier("hi client"));
    });

    CompletableFuture<Message> messageFuture = new CompletableFuture<>();
    client.listen().subscribe(messageFuture::complete);

    send(client, server.address(), Message.fromQualifier("hello server"));

    Message result = messageFuture.get(3, TimeUnit.SECONDS);
    assertNotNull("No response from serverAddress", result);
    assertEquals("hi client", result.header(MessageHeaders.QUALIFIER));
  }

  @Test
  public void testSendOrderSingleThreadWithoutPromises() throws Exception {
    server = createTransport();

    int iterationNum = 10;
    int sentPerIteration = 1000;
    long[] iterationTimeSeries = new long[iterationNum];
    for (int i = 0; i < iterationNum; i++) {
      LOGGER.info("####### {} : iteration = {}", testName.getMethodName(), i);

      client = createTransport();
      final List<Message> received = new ArrayList<>();
      final CountDownLatch latch = new CountDownLatch(sentPerIteration);
      server.listen().subscribe(message -> {
        received.add(message);
        latch.countDown();
      });

      long startAt = System.currentTimeMillis();
      for (int j = 0; j < sentPerIteration; j++) {
        client.send(server.address(), Message.fromQualifier("q" + j));
      }

      latch.await(20, TimeUnit.SECONDS);
      long iterationTime = System.currentTimeMillis() - startAt;
      iterationTimeSeries[i] = iterationTime;
      assertSendOrder(sentPerIteration, received);

      LOGGER.info("Iteration time: {} ms", iterationTime);

      destroyTransport(client);
    }

    LongSummaryStatistics iterationTimeStats = LongStream.of(iterationTimeSeries).summaryStatistics();
    LOGGER.info("Iteration time stats (ms): {}", iterationTimeStats);
  }

  @Test
  public void testSendOrderSingleThread() throws Exception {
    server = createTransport();

    int iterationNum = 10;
    int sentPerIteration = 1000;
    long[] iterationTimeSeries = new long[iterationNum];
    List<Long> totalSentTimeSeries = new ArrayList<>(sentPerIteration * iterationNum);
    for (int i = 0; i < iterationNum; i++) {
      LOGGER.info("####### {} : iteration = {}", testName.getMethodName(), i);
      List<Long> iterSentTimeSeries = new ArrayList<>(sentPerIteration);

      client = createTransport();
      final List<Message> received = new ArrayList<>();
      final CountDownLatch latch = new CountDownLatch(sentPerIteration);
      server.listen().subscribe(message -> {
        received.add(message);
        latch.countDown();
      });

      long startAt = System.currentTimeMillis();
      for (int j = 0; j < sentPerIteration; j++) {
        CompletableFuture<Void> sendPromise = new CompletableFuture<>();
        long sentAt = System.currentTimeMillis();
        client.send(server.address(), Message.fromQualifier("q" + j), sendPromise);
        sendPromise.whenComplete((aVoid, exception) -> {
          if (exception == null) {
            long sentTime = System.currentTimeMillis() - sentAt;
            iterSentTimeSeries.add(sentTime);
          } else {
            LOGGER.error("Failed to send message in {} ms", System.currentTimeMillis() - sentAt, exception);
          }
        });
      }

      latch.await(20, TimeUnit.SECONDS);
      long iterationTime = System.currentTimeMillis() - startAt;
      iterationTimeSeries[i] = iterationTime;
      assertSendOrder(sentPerIteration, received);

      Thread.sleep(10); // await a bit for last msg confirmation

      LongSummaryStatistics iterSentTimeStats = iterSentTimeSeries.stream().mapToLong(v -> v).summaryStatistics();
      totalSentTimeSeries.addAll(iterSentTimeSeries);
      LongSummaryStatistics totalSentTimeStats = totalSentTimeSeries.stream().mapToLong(v -> v).summaryStatistics();
      LOGGER.info("Iteration time: {} ms", iterationTime);
      LOGGER.info("Sent time stats iter  (ms): {}", iterSentTimeStats);
      LOGGER.info("Sent time stats total (ms): {}", totalSentTimeStats);

      destroyTransport(client);
    }

    LongSummaryStatistics iterationTimeStats = LongStream.of(iterationTimeSeries).summaryStatistics();
    LOGGER.info("Iteration time stats (ms): {}", iterationTimeStats);
  }

  @Test
  public void testSendOrderMultiThread() throws Exception {
    Transport server = createTransport();

    final int total = 1000;
    for (int i = 0; i < 10; i++) {
      LOGGER.info("####### {} : iteration = {}", testName.getMethodName(), i);
      ExecutorService exec = Executors.newFixedThreadPool(4, new ThreadFactoryBuilder().setDaemon(true).build());

      Transport client = createTransport();
      final List<Message> received = new ArrayList<>();
      final CountDownLatch latch = new CountDownLatch(4 * total);
      server.listen().subscribe(message -> {
        received.add(message);
        latch.countDown();
      });

      Future<Void> f0 = exec.submit(sender(0, client, server.address(), total));
      Future<Void> f1 = exec.submit(sender(1, client, server.address(), total));
      Future<Void> f2 = exec.submit(sender(2, client, server.address(), total));
      Future<Void> f3 = exec.submit(sender(3, client, server.address(), total));

      latch.await(20, TimeUnit.SECONDS);

      f0.get(1, TimeUnit.SECONDS);
      f1.get(1, TimeUnit.SECONDS);
      f2.get(1, TimeUnit.SECONDS);
      f3.get(1, TimeUnit.SECONDS);

      exec.shutdownNow();

      assertSenderOrder(0, total, received);
      assertSenderOrder(1, total, received);
      assertSenderOrder(2, total, received);
      assertSenderOrder(3, total, received);

      destroyTransport(client);
    }

    destroyTransport(client);
    destroyTransport(server);
  }

  @Test
  public void testNetworkSettings() throws InterruptedException {
    client = createTransport();
    server = createTransport();

    int lostPercent = 50;
    int mean = 0;
    client.setNetworkSettings(server.address(), lostPercent, mean);

    final List<Message> serverMessageList = new ArrayList<>();
    server.listen().subscribe(serverMessageList::add);

    int total = 1000;
    for (int i = 0; i < total; i++) {
      client.send(server.address(), Message.fromData("q" + i));
    }

    Thread.sleep(1000);

    int expectedMax = total / 100 * lostPercent + total / 100 * 5; // +5% for maximum possible lost messages
    int size = serverMessageList.size();
    assertTrue("expectedMax=" + expectedMax + ", actual size=" + size, size < expectedMax);
  }

  @Test
  public void testPingPongOnSingleChannel() throws Exception {
    server = createTransport();
    client = createTransport();

    server.listen().buffer(2).subscribe(messages -> {
      for (Message message : messages) {
        Message echo = Message.fromData("echo/" + message.header(MessageHeaders.QUALIFIER));
        server.send(message.sender(), echo);
      }
    });

    final CompletableFuture<List<Message>> targetFuture = new CompletableFuture<>();
    client.listen().buffer(2).subscribe(targetFuture::complete);

    client.send(server.address(), Message.fromData("q1"));
    client.send(server.address(), Message.fromData("q2"));

    List<Message> target = targetFuture.get(1, TimeUnit.SECONDS);
    assertNotNull(target);
    assertEquals(2, target.size());
  }

  @Test
  public void testPingPongOnSeparateChannel() throws Exception {
    server = createTransport();
    client = createTransport();

    server.listen().buffer(2).subscribe(messages -> {
      for (Message message : messages) {
        Message echo = Message.fromData("echo/" + message.header(MessageHeaders.QUALIFIER));
        server.send(message.sender(), echo);
      }
    });

    final CompletableFuture<List<Message>> targetFuture = new CompletableFuture<>();
    client.listen().buffer(2).subscribe(targetFuture::complete);

    client.send(server.address(), Message.fromData("q1"));
    client.send(server.address(), Message.fromData("q2"));

    List<Message> target = targetFuture.get(1, TimeUnit.SECONDS);
    assertNotNull(target);
    assertEquals(2, target.size());
  }

  @Test
  public void testCompleteObserver() throws Exception {
    server = createTransport();
    client = createTransport();

    final CompletableFuture<Boolean> completeLatch = new CompletableFuture<>();
    final CompletableFuture<Message> messageLatch = new CompletableFuture<>();

    server.listen().subscribe(new Subscriber<Message>() {
      @Override
      public void onCompleted() {
        completeLatch.complete(true);
      }

      @Override
      public void onError(Throwable e) {}

      @Override
      public void onNext(Message message) {
        messageLatch.complete(message);
      }
    });

    CompletableFuture<Void> send = new CompletableFuture<>();
    client.send(server.address(), Message.fromData("q"), send);
    send.get(1, TimeUnit.SECONDS);

    assertNotNull(messageLatch.get(1, TimeUnit.SECONDS));

    CompletableFuture<Void> close = new CompletableFuture<>();
    server.stop(close);
    close.get();

    assertTrue(completeLatch.get(1, TimeUnit.SECONDS));
  }

  @Test
  public void testObserverThrowsException() throws Exception {
    server = createTransport();
    client = createTransport();

    server.listen().subscribe(message -> {
      String qualifier = message.data();
      if (qualifier.startsWith("throw")) {
        throw new RuntimeException("" + message);
      }
      if (qualifier.startsWith("q")) {
        Message echo = Message.fromData("echo/" + message.header(MessageHeaders.QUALIFIER));
        server.send(message.sender(), echo);
      }
    }, Throwable::printStackTrace);

    // send "throw" and raise exception on server subscriber
    final CompletableFuture<Message> messageFuture0 = new CompletableFuture<>();
    client.listen().subscribe(messageFuture0::complete);
    client.send(server.address(), Message.fromData("throw"));
    Message message0 = null;
    try {
      message0 = messageFuture0.get(1, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      // ignore since expected behavior
    }
    assertNull(message0);

    // send normal message and check whether server subscriber is broken (no response)
    final CompletableFuture<Message> messageFuture1 = new CompletableFuture<>();
    client.listen().subscribe(messageFuture1::complete);
    client.send(server.address(), Message.fromData("q"));
    Message transportMessage1 = null;
    try {
      transportMessage1 = messageFuture1.get(1, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      // ignore since expected behavior
    }
    assertNull(transportMessage1);
  }

  @Test
  public void testBlockAndUnblockTraffic() throws Exception {
    client = createTransport();
    server = createTransport();

    server.listen().subscribe(message -> server.send(message.sender(), message));

    final List<Message> resp = new ArrayList<>();
    client.listen().subscribe(resp::add);

    // test at unblocked transport
    send(client, server.address(), Message.fromQualifier("q/unblocked"));

    // then block client->server messages
    Thread.sleep(1000);
    client.block(server.address());
    send(client, server.address(), Message.fromQualifier("q/blocked"));

    Thread.sleep(1000);
    assertEquals(1, resp.size());
    assertEquals("q/unblocked", resp.get(0).header(MessageHeaders.QUALIFIER));
  }

  @Test
  public void naiveTransportStressTest() throws Exception {
    // Create transport provider
    Transport echoServer = Transport.bindAwait();
    echoServer.listen().subscribe(msg -> echoServer.send(msg.sender(), msg));

    // Create transport consumer
    int warmUpCount = 1_000;
    int count = 5_000;
    CountDownLatch warmUpLatch = new CountDownLatch(warmUpCount);
    CountDownLatch latch = new CountDownLatch(warmUpCount + count);
    Transport client = Transport.bindAwait();
    client.listen().subscribe(msg -> {latch.countDown(); warmUpLatch.countDown();});

    // Warm up
    for (int i = 0; i < warmUpCount; i++) {
      client.send(echoServer.address(), Message.fromData("naive_stress_test"));
    }
    warmUpLatch.await(10, TimeUnit.SECONDS);
    assertTrue(warmUpLatch.getCount() == 0);

    // Measure
    long startTime = System.currentTimeMillis();
    for (int i = 0; i < count; i++) {
      client.send(echoServer.address(), Message.fromData("naive_stress_test"));
    }
    LOGGER.info("Finished sending {} messages in {} ms", count, System.currentTimeMillis() - startTime);
    latch.await(30, TimeUnit.SECONDS);
    LOGGER.info("Finished receiving {} messages in {} ms", count, System.currentTimeMillis() - startTime);
    assertTrue(latch.getCount() == 0);

    destroyTransport(echoServer);
    destroyTransport(client);
  }

  private void assertSendOrder(int total, List<Message> received) {
    ArrayList<Message> messages = new ArrayList<>(received);
    assertEquals(total, messages.size());
    for (int k = 0; k < total; k++) {
      assertEquals("q" + k, messages.get(k).header(MessageHeaders.QUALIFIER));
    }
  }

  private Callable<Void> sender(final int id, final Transport client, final Address address, final int total) {
    return () -> {
      for (int j = 0; j < total; j++) {
        String correlationId = id + "/" + j;
        CompletableFuture<Void> sendPromise = new CompletableFuture<>();
        client.send(address, Message.withQualifier("q").correlationId(correlationId).build(), sendPromise);
        try {
          sendPromise.get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
          LOGGER.error("Failed to send message: j = {} id = {}", j, id, e);
          propagate(e);
        }
      }
      return null;
    };
  }

  private void assertSenderOrder(int id, int total, List<Message> received) {
    ArrayList<Message> messages = new ArrayList<>(received);
    ArrayListMultimap<Integer, Message> group = ArrayListMultimap.create();
    for (Message message : messages) {
      group.put(Integer.valueOf(message.correlationId().split("/")[0]), message);
    }

    assertEquals(total, group.get(id).size());
    for (int k = 0; k < total; k++) {
      assertEquals(id + "/" + k, group.get(id).get(k).correlationId());
    }
  }

  private void send(final ITransport from, final Address to, final Message msg) {
    final CompletableFuture<Void> promise = new CompletableFuture<>();
    promise.thenAccept(aVoid -> {
      if (promise.isDone()) {
        try {
          promise.get();
        } catch (Exception e) {
          LOGGER.error("Failed to send {} to {} from transport: {}, cause: {}", msg, to, from, e.getCause());
        }
      }
    });
    from.send(to, msg, promise);
  }

  private Transport createTransport() {
    TransportConfig config = TransportConfig.builder()
        .connectTimeout(1000)
        .useNetworkEmulator(true)
        .port(5800)
        .build();
    return Transport.bindAwait(config);
  }

  private void destroyTransport(Transport transport) throws Exception {
    if (transport != null && !transport.isStopped()) {
      CompletableFuture<Void> close = new CompletableFuture<>();
      transport.stop(close);
      close.get(1, TimeUnit.SECONDS);
    }
  }

  private void assertAmongExpectedClasses(Class actualClass, Class... expectedClasses) {
    for (Class expectedClass : expectedClasses) {
      if (expectedClass.equals(actualClass)) {
        return;
      }
    }
    fail("Expected classes " + Arrays.toString(expectedClasses) + ", but actual: " + actualClass);
  }

}
