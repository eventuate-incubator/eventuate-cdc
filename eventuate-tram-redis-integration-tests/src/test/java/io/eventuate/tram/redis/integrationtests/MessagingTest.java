package io.eventuate.tram.redis.integrationtests;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.eventuate.javaclient.commonimpl.JSonMapper;
import io.eventuate.tram.consumer.common.TramConsumerCommonConfiguration;
import io.eventuate.tram.consumer.common.TramNoopDuplicateMessageDetectorConfiguration;
import io.eventuate.tram.consumer.redis.*;
import io.eventuate.tram.consumer.common.coordinator.CoordinatorFactory;
import io.eventuate.tram.data.producer.redis.EventuateRedisProducer;
import io.eventuate.tram.messaging.common.MessageImpl;
import io.eventuate.tram.redis.common.CommonRedisConfiguration;
import io.eventuate.tram.redis.common.RedissonClients;
import io.eventuate.util.test.async.Eventually;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = MessagingTest.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class MessagingTest {

  @Configuration
  @EnableAutoConfiguration
  @Import({CommonRedisConfiguration.class, TramConsumerCommonConfiguration.class, TramNoopDuplicateMessageDetectorConfiguration.class})
  public static class Config {
  }

  private static class EventuallyConfig {
    public final int iterations;
    public final int timeout;
    public final TimeUnit timeUnit;

    public EventuallyConfig(int iterations, int timeout, TimeUnit timeUnit) {
      this.iterations = iterations;
      this.timeout = timeout;
      this.timeUnit = timeUnit;
    }
  }

  private static class TestSubscription {
    private MessageConsumerRedisImpl consumer;
    private ConcurrentLinkedQueue<Integer> messageQueue;
    private Set<Integer> currentPartitions = Collections.emptySet();

    public TestSubscription(MessageConsumerRedisImpl consumer, ConcurrentLinkedQueue<Integer> messageQueue) {
      this.consumer = consumer;
      this.messageQueue = messageQueue;
    }

    public MessageConsumerRedisImpl getConsumer() {
      return consumer;
    }

    public ConcurrentLinkedQueue<Integer> getMessageQueue() {
      return messageQueue;
    }

    public Set<Integer> getCurrentPartitions() {
      return currentPartitions;
    }

    public void setCurrentPartitions(Set<Integer> currentPartitions) {
      this.currentPartitions = currentPartitions;
    }

    public void clearMessages() {
      messageQueue.clear();
    }

    public void close() {
      consumer.close();
      messageQueue.clear();
    }
  }

  private AtomicInteger consumerIdCounter;
  private AtomicInteger subscriptionIdCounter;
  private AtomicInteger messageIdCounter;

  private Supplier<String> consumerIdSupplier = () -> "consumer" + consumerIdCounter.getAndIncrement();
  private Supplier<String> subscriptionIdSupplier = () -> "subscription" + subscriptionIdCounter.getAndIncrement();
  private Supplier<String> messageIdSupplier = () -> "msg" + messageIdCounter.getAndIncrement();

  private Supplier<String> subscriberIdSupplier = () -> "subscriber" + System.nanoTime();
  private Supplier<String> channelIdSupplier = () -> "channel" + System.nanoTime();

  private Logger logger = LoggerFactory.getLogger(getClass());

  @Autowired
  private ApplicationContext applicationContext;

  @Autowired
  private RedisTemplate<String, String> redisTemplate;

  @Autowired
  private RedissonClients redissonClients;

  private static final int DEFAULT_PARTITION_COUNT = 2;
  private static final int DEFAULT_MESSAGE_COUNT = 20;
  private static final EventuallyConfig EVENTUALLY_CONFIG = new EventuallyConfig(200, 400, TimeUnit.MILLISECONDS);


  private String destination;
  private String subscriberId;

  @Before
  public void init() {
    consumerIdCounter = new AtomicInteger(1);
    subscriptionIdCounter = new AtomicInteger(1);
    messageIdCounter = new AtomicInteger(1);

    destination = channelIdSupplier.get();
    subscriberId = subscriberIdSupplier.get();
  }

  @Test
  public void test1Consumer2Partitions() throws Exception {
    TestSubscription subscription = subscribe();

    assertSubscriptionPartitionsBalanced(ImmutableList.of(subscription));

    sendMessages();

    assertMessagesConsumed(subscription);
  }

  @Test
  public void test2Consumers2Partitions() {
    TestSubscription subscription1 = subscribe();
    TestSubscription subscription2 = subscribe();

    assertSubscriptionPartitionsBalanced(ImmutableList.of(subscription1, subscription2));

    sendMessages();

    assertMessagesConsumed(ImmutableList.of(subscription1, subscription2));
  }

  @Test
  public void test1Consumer2PartitionsThenAddedConsumer() {
    TestSubscription testSubscription1 = subscribe();

    assertSubscriptionPartitionsBalanced(ImmutableList.of(testSubscription1));

    sendMessages();

    assertMessagesConsumed(testSubscription1);

    testSubscription1.clearMessages();
    TestSubscription testSubscription2 = subscribe();

    assertSubscriptionPartitionsBalanced(ImmutableList.of(testSubscription1, testSubscription2));

    sendMessages();

    assertMessagesConsumed(ImmutableList.of(testSubscription1, testSubscription2));
  }

  @Test
  public void test2Consumers2PartitionsThenRemovedConsumer() {

    TestSubscription testSubscription1 = subscribe();
    TestSubscription testSubscription2 = subscribe();

    assertSubscriptionPartitionsBalanced(ImmutableList.of(testSubscription1, testSubscription2));

    sendMessages();

    assertMessagesConsumed(ImmutableList.of(testSubscription1, testSubscription2));

    testSubscription1.clearMessages();
    testSubscription2.close();

    assertSubscriptionPartitionsBalanced(ImmutableList.of(testSubscription1));

    sendMessages();

    assertMessagesConsumed(testSubscription1);
  }

  @Test
  public void test5Consumers9PartitionsThenRemoved2ConsumersAndAdded3Consumers() {
    int partitionCount = 9;
    int initialConsumers = 5;
    int removedConsumers = 2;
    int addedConsumers = 3;

    LinkedList<TestSubscription> testSubscriptions = createConsumersAndSubscribe(initialConsumers, partitionCount);

    assertSubscriptionPartitionsBalanced(testSubscriptions, partitionCount);

    sendMessages(partitionCount);

    assertMessagesConsumed(testSubscriptions);

    closeAndRemoveSubscribers(testSubscriptions, removedConsumers);

    testSubscriptions.forEach(TestSubscription::clearMessages);

    testSubscriptions.addAll(createConsumersAndSubscribe(addedConsumers, partitionCount));

    assertSubscriptionPartitionsBalanced(testSubscriptions, partitionCount);

    sendMessages(partitionCount);

    assertMessagesConsumed(testSubscriptions);
  }

  @Test
  public void testReassignment() {
    runReassignmentIteration();
    runReassignmentIteration();
  }

  private void runReassignmentIteration() {
    TestSubscription testSubscription1 = subscribe();

    assertSubscriptionPartitionsBalanced(ImmutableList.of(testSubscription1));

    sendMessages();

    try {
      assertMessagesConsumed(testSubscription1);
    } catch (Throwable t){
      testSubscription1.close();
      throw t;
    }

    testSubscription1.clearMessages();
    TestSubscription testSubscription2 = subscribe();

    assertSubscriptionPartitionsBalanced(ImmutableList.of(testSubscription1, testSubscription2));

    sendMessages();

    try {
      assertMessagesConsumed(ImmutableList.of(testSubscription1, testSubscription2));
    } finally {
      testSubscription1.close();
      testSubscription2.close();
    }
  }

  private void assertMessagesConsumed(TestSubscription testSubscription) {
    assertMessagesConsumed(testSubscription, DEFAULT_MESSAGE_COUNT);
  }

  private void assertMessagesConsumed(TestSubscription testSubscription, int messageCount) {
    Eventually.eventually(EVENTUALLY_CONFIG.iterations,
            EVENTUALLY_CONFIG.timeout,
            EVENTUALLY_CONFIG.timeUnit,
            () -> Assert.assertEquals(String.format("consumer %s did not receive expected messages", testSubscription.getConsumer().consumerId),
                    messageCount,
                    testSubscription.messageQueue.size()));
  }

  private void assertMessagesConsumed(List<TestSubscription> testSubscriptions) {
    assertMessagesConsumed(testSubscriptions, DEFAULT_MESSAGE_COUNT);
  }

  private void assertMessagesConsumed(List<TestSubscription> testSubscriptions, int messageCount) {
    Eventually.eventually(EVENTUALLY_CONFIG.iterations,
            EVENTUALLY_CONFIG.timeout,
            EVENTUALLY_CONFIG.timeUnit,
            () -> {

      List<TestSubscription> emptySubscriptions = testSubscriptions
              .stream()
              .filter(testSubscription -> testSubscription.messageQueue.isEmpty())
              .collect(Collectors.toList());

      emptySubscriptions.forEach(testSubscription -> logger.info("[{}] consumer is empty", testSubscription.getConsumer().consumerId));

      Assert.assertTrue("There are non-empty subscriptions", emptySubscriptions.isEmpty());

      Assert.assertEquals("sent messages not equal to consumed messages",
              (long) messageCount,
              (long) testSubscriptions
                      .stream()
                      .map(testSubscription -> testSubscription.getMessageQueue().size())
                      .reduce((a, b) -> a + b)
                      .orElse(0));
    });
  }

  private void assertSubscriptionPartitionsBalanced(List<TestSubscription> subscriptions) {
    assertSubscriptionPartitionsBalanced(subscriptions, DEFAULT_PARTITION_COUNT);
  }

  private void assertSubscriptionPartitionsBalanced(List<TestSubscription> subscriptions, int expectedPartitionCount) {
    Eventually.eventually(EVENTUALLY_CONFIG.iterations,
            EVENTUALLY_CONFIG.timeout,
            EVENTUALLY_CONFIG.timeUnit,
            () -> {
              Assert.assertTrue("not all subscriptions have assigned partitions", subscriptions
                      .stream()
                      .noneMatch(testSubscription -> testSubscription.getCurrentPartitions().isEmpty()));

              List<Integer> allPartitions = subscriptions
                      .stream()
                      .map(TestSubscription::getCurrentPartitions)
                      .flatMap(Collection::stream)
                      .collect(Collectors.toList());

              Set<Integer> uniquePartitions = new HashSet<>(allPartitions);

              Assert.assertEquals("partitions are not unique across subscriptions", allPartitions.size(), uniquePartitions.size());
              Assert.assertEquals("actual partition count not equals to expected partition count", expectedPartitionCount, uniquePartitions.size());
            });
  }

  private LinkedList<TestSubscription> createConsumersAndSubscribe(int consumerCount, int partitionCount) {

    LinkedList<TestSubscription> subscriptions = new LinkedList<>();

    for (int i = 0; i < consumerCount; i++) {
      subscriptions.add(subscribe(partitionCount));
    }

    return subscriptions;
  }

  private void closeAndRemoveSubscribers(LinkedList<TestSubscription> subscriptions, int count) {
    for (int i = 0; i < count; i++) {
      subscriptions.poll().close();
    }
  }


  private TestSubscription subscribe() {
    return subscribe(DEFAULT_PARTITION_COUNT);
  }

  private TestSubscription subscribe(int partitionCount) {
    ConcurrentLinkedQueue<Integer> messageQueue = new ConcurrentLinkedQueue<>();

    MessageConsumerRedisImpl consumer = createConsumer(partitionCount);

    consumer.subscribe(subscriberId, ImmutableSet.of(destination), message ->
            messageQueue.add(Integer.parseInt(message.getPayload())));

    TestSubscription testSubscription = new TestSubscription(consumer, messageQueue);

    consumer.setSubscriptionLifecycleHook((channel, subscriptionId, currentPartitions) -> {
      testSubscription.setCurrentPartitions(currentPartitions);
    });

    return testSubscription;
  }

  private MessageConsumerRedisImpl createConsumer(int partitionCount) {

    CoordinatorFactory coordinatorFactory = new RedisCoordinatorFactoryImpl(new RedisAssignmentManager(redisTemplate, 3600000),
            (groupId, memberId, assignmentUpdatedCallback) -> new RedisAssignmentListener(redisTemplate, groupId, memberId, 50, assignmentUpdatedCallback),
            (groupId, groupMembersUpdatedCallback) -> new RedisMemberGroupManager(redisTemplate, groupId, 50, groupMembersUpdatedCallback),
            (groupId, leaderSelectedCallback, leaderRemovedCallback) -> new RedisLeaderSelector(redissonClients, groupId, 10000, leaderSelectedCallback, leaderRemovedCallback),
            (groupId, memberId) -> new RedisGroupMember(redisTemplate, groupId, memberId, 1000),
            partitionCount);

    MessageConsumerRedisImpl messageConsumerRedis = new MessageConsumerRedisImpl(subscriptionIdSupplier,
            consumerIdSupplier.get(),
            redisTemplate,
            coordinatorFactory,
            100,
            10);

    applicationContext.getAutowireCapableBeanFactory().autowireBean(messageConsumerRedis);

    return messageConsumerRedis;
  }

  private void sendMessages() {
    sendMessages(DEFAULT_MESSAGE_COUNT, DEFAULT_PARTITION_COUNT);
  }

  private void sendMessages(int partitions) {
    sendMessages(DEFAULT_MESSAGE_COUNT, partitions);
  }

  private void sendMessages(int messageCount, int partitions) {
    EventuateRedisProducer eventuateRedisProducer = new EventuateRedisProducer(redisTemplate, partitions);

    for (int i = 0; i < messageCount; i++) {
      eventuateRedisProducer.send(destination,
              String.valueOf(i),
              JSonMapper.toJson(new MessageImpl(String.valueOf(i),
                      Collections.singletonMap("ID", messageIdSupplier.get()))));
    }
  }
}
