/*
 * Copyright © 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.resource.ai.vector.store.local;

import static io.gravitee.resource.ai.vector.store.api.IndexType.FLAT;
import static io.gravitee.resource.ai.vector.store.api.IndexType.HNSW;
import static io.gravitee.resource.ai.vector.store.api.Similarity.*;
import static io.gravitee.resource.ai.vector.store.redis.configuration.VectorType.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.gravitee.resource.ai.vector.store.api.AiVectorStoreProperties;
import io.gravitee.resource.ai.vector.store.api.VectorEntity;
import io.gravitee.resource.ai.vector.store.api.VectorResult;
import io.gravitee.resource.ai.vector.store.redis.AiVectorStoreRedisResource;
import io.gravitee.resource.ai.vector.store.redis.configuration.AiVectorStoreRedisConfiguration;
import io.gravitee.resource.ai.vector.store.redis.configuration.RedisConfiguration;
import io.gravitee.resource.ai.vector.store.redis.configuration.RedisVectorStoreConfiguration;
import io.gravitee.resource.ai.vector.store.redis.configuration.VectorType;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import io.vertx.rxjava3.core.Vertx;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.ApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

class AiVectorStoreRedisResourceWithVectorPrecisionTest {

  static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis/redis-stack:7.4.0-v5"))
    .withExposedPorts(6379);

  public static float[] vector1 = new float[] {
    0.66953415f,
    0.18902819f,
    0.11021819f,
    0.07593438f,
    -0.04711385f,
    0.03122021f,
    -0.19437398f,
    -0.16093858f,
    0.03285817f,
    -0.17519756f,
  };

  public static float[] vector2 = new float[] {
    0.6766241f,
    0.1793094f,
    0.07874545f,
    0.03933726f,
    -0.06496269f,
    0.06297384f,
    -0.19972953f,
    -0.21079123f,
    0.03588087f,
    0.16885366f,
  };

  @BeforeAll
  static void startRedis() {
    redis.start();
  }

  @AfterAll
  static void stopRedis() {
    redis.stop();
  }

  @ParameterizedTest
  @MethodSource("params_that_must_test_different_vector_types")
  void must_test_different_vector_types(int index, VectorType vectorType, float[] v1, float[] v2, float score)
    throws Exception {
    var config = new AiVectorStoreRedisConfiguration(
      new AiVectorStoreProperties(vector1.length, 1, COSINE, 0.0f, HNSW, false, true, 2, TimeUnit.SECONDS),
      new RedisConfiguration(
        "redis://" + redis.getHost() + ":" + redis.getFirstMappedPort(),
        null,
        null,
        "test_vector" + index,
        "test_vector" + index,
        "@retrieval_context_key:{\n\t$retrieval_context_key\n}=>[\n\tKNN $max_results @vector $vector AS score\n]",
        "score",
        6,
        new RedisVectorStoreConfiguration(vectorType, 16, 200, 10, 0.01f, 5, 10)
      )
    );

    AiVectorStoreRedisResource resource = new AiVectorStoreRedisResource();
    injectConfiguration(resource, config);

    var appCtx = mock(ApplicationContext.class);
    when(appCtx.getBean(Vertx.class)).thenReturn(Vertx.vertx());
    resource.setApplicationContext(appCtx);

    resource.doStart();
    Thread.sleep(4000);

    try {
      var metadata = Map.<String, Object>of("retrieval_context_key", "ctx1", "category", "test");

      String id = UUID.randomUUID().toString();
      var entity = new VectorEntity(
        id,
        "The big brown fox jumps over the lazy dog",
        v1,
        metadata,
        System.currentTimeMillis()
      );
      var similarEntity = new VectorEntity(
        id,
        "The brown fox jumps over the lazy dog",
        v2,
        metadata,
        System.currentTimeMillis()
      );

      TestSubscriber<VectorResult> subscriber = resource
        .add(entity)
        .andThen(resource.findRelevant(similarEntity))
        .test()
        .awaitDone(5, TimeUnit.SECONDS)
        .assertComplete()
        .assertNoErrors()
        .assertValueCount(1);

      VectorResult result = subscriber.values().get(0);

      assertEquals(config.redisConfig().prefix() + ":" + id, result.entity().id());
      assertEquals("ctx1", result.entity().metadata().get("retrieval_context_key"));
      assertTrue(result.score() >= score);
    } finally {
      resource.doStop();
    }
  }

  public static Stream<Arguments> params_that_must_test_different_vector_types() {
    return Stream.of(
      Arguments.of(1, FLOAT32, vector1, vector2, 0.5f),
      Arguments.of(2, FLOAT64, vector1, vector2, 0.5f),
      Arguments.of(3, FLOAT16, vector1, vector2, 0.5f),
      Arguments.of(4, BFLOAT16, vector1, vector2, 0.5f)
    );
  }

  private void injectConfiguration(AiVectorStoreRedisResource resource, AiVectorStoreRedisConfiguration config)
    throws Exception {
    Field field = resource.getClass().getSuperclass().getSuperclass().getDeclaredField("configuration");
    field.setAccessible(true);
    field.set(resource, config);
  }
}
