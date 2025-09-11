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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.gravitee.resource.ai.vector.store.api.AiVectorStoreProperties;
import io.gravitee.resource.ai.vector.store.api.VectorEntity;
import io.gravitee.resource.ai.vector.store.api.VectorResult;
import io.gravitee.resource.ai.vector.store.redis.AiVectorStoreRedisResource;
import io.gravitee.resource.ai.vector.store.redis.configuration.AiVectorStoreRedisConfiguration;
import io.gravitee.resource.ai.vector.store.redis.configuration.RedisConfiguration;
import io.gravitee.resource.ai.vector.store.redis.configuration.RedisVectorStoreConfiguration;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import io.vertx.rxjava3.core.Vertx;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.ApplicationContext;

class AiVectorStoreRedisResourceExpiryTest extends AbstractAiVectorStoreRedisResourceTest {

  static final String REDIS_URL = "redis://" + redis.getHost() + ":" + redis.getFirstMappedPort();
  static final String RETRIEVAL_QUERY =
    "@retrieval_context_key:{\n\t$retrieval_context_key\n}=>[\n\tKNN $max_results @vector $vector AS score\n]";
  static final RedisVectorStoreConfiguration VECTOR_STORE_CONFIG = new RedisVectorStoreConfiguration(
    FLOAT32,
    16,
    200,
    10,
    0.01f,
    5,
    10
  );
  static final String SCORE_FIELD = "score";

  @ParameterizedTest
  @MethodSource("params_that_must_add_and_retrieve_vectors_and_expire")
  void must_add_and_retrieve_vectors_and_expire(AiVectorStoreRedisConfiguration config, float[] v1, float[] v2, float score)
    throws Exception {
    AiVectorStoreRedisResource resource = new AiVectorStoreRedisResource();
    injectConfiguration(resource, config);

    var appCtx = mock(ApplicationContext.class);
    when(appCtx.getBean(Vertx.class)).thenReturn(Vertx.vertx());
    resource.setApplicationContext(appCtx);

    resource.doStart();
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
        .awaitDone(3, TimeUnit.SECONDS)
        .assertComplete()
        .assertNoErrors()
        .assertValueCount(1);

      VectorResult result = subscriber.values().get(0);

      assertEquals(config.redisConfig().prefix() + ":" + id, result.entity().id());
      assertEquals("ctx1", result.entity().metadata().get("retrieval_context_key"));
      assertTrue(result.score() >= score);

      Flowable
        .timer(4, TimeUnit.SECONDS)
        .flatMap(__ -> resource.findRelevant(similarEntity))
        .test()
        .awaitDone(5, TimeUnit.SECONDS)
        .assertComplete()
        .assertNoErrors()
        .assertValueCount(0);
    } finally {
      resource.doStop();
    }
  }

  static Stream<Arguments> params_that_must_add_and_retrieve_vectors_and_expire() {
    return Stream.of(
      Arguments.of(
        new AiVectorStoreRedisConfiguration(
          new AiVectorStoreProperties(vector1.length, 1, COSINE, 0.0f, HNSW, false, true, 1, TimeUnit.SECONDS),
          new RedisConfiguration(
            REDIS_URL,
            null,
            null,
            "test_4",
            "test_4",
            RETRIEVAL_QUERY,
            SCORE_FIELD,
            6,
            VECTOR_STORE_CONFIG
          )
        ),
        vector1,
        vector2,
        0.5f
      ),
      Arguments.of(
        new AiVectorStoreRedisConfiguration(
          new AiVectorStoreProperties(vector1.length, 1, EUCLIDEAN, 0.0f, FLAT, false, true, 1, TimeUnit.SECONDS),
          new RedisConfiguration(
            REDIS_URL,
            null,
            null,
            "test_5",
            "test_5",
            RETRIEVAL_QUERY,
            SCORE_FIELD,
            6,
            VECTOR_STORE_CONFIG
          )
        ),
        vector1,
        vector2,
        0.5f
      ),
      Arguments.of(
        new AiVectorStoreRedisConfiguration(
          new AiVectorStoreProperties(vector1.length, 1, DOT, 0.0f, HNSW, false, true, 1, TimeUnit.SECONDS),
          new RedisConfiguration(
            REDIS_URL,
            null,
            null,
            "test_6",
            "test_6",
            RETRIEVAL_QUERY,
            SCORE_FIELD,
            6,
            VECTOR_STORE_CONFIG
          )
        ),
        vector1,
        vector2,
        0.5f
      ),
      Arguments.of(
        new AiVectorStoreRedisConfiguration(
          new AiVectorStoreProperties(vector1.length, 1, COSINE, 0.0f, HNSW, false, true, 1, TimeUnit.SECONDS),
          new RedisConfiguration(
            REDIS_URL,
            null,
            null,
            "test_1",
            "test_1",
            RETRIEVAL_QUERY,
            SCORE_FIELD,
            6,
            VECTOR_STORE_CONFIG
          )
        ),
        vector1,
        vector3,
        0.3f
      ),
      Arguments.of(
        new AiVectorStoreRedisConfiguration(
          new AiVectorStoreProperties(vector1.length, 1, EUCLIDEAN, 0.0f, FLAT, false, true, 1, TimeUnit.SECONDS),
          new RedisConfiguration(
            REDIS_URL,
            null,
            null,
            "test_2",
            "test_2",
            RETRIEVAL_QUERY,
            SCORE_FIELD,
            6,
            VECTOR_STORE_CONFIG
          )
        ),
        vector1,
        vector3,
        0.3f
      ),
      Arguments.of(
        new AiVectorStoreRedisConfiguration(
          new AiVectorStoreProperties(vector1.length, 1, DOT, 0.0f, HNSW, false, true, 1, TimeUnit.SECONDS),
          new RedisConfiguration(
            REDIS_URL,
            null,
            null,
            "test_3",
            "test_3",
            RETRIEVAL_QUERY,
            SCORE_FIELD,
            6,
            VECTOR_STORE_CONFIG
          )
        ),
        vector1,
        vector3,
        0.3f
      )
    );
  }
}
