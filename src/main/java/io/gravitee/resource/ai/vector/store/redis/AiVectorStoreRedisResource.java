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
package io.gravitee.resource.ai.vector.store.redis;

import static io.gravitee.resource.ai.vector.store.api.IndexType.HNSW;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static java.util.Comparator.comparingDouble;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.resource.ai.vector.store.api.*;
import io.gravitee.resource.ai.vector.store.redis.configuration.AiVectorStoreRedisConfiguration;
import io.gravitee.resource.ai.vector.store.redis.configuration.DistanceMetric;
import io.gravitee.resource.ai.vector.store.redis.configuration.RedisConfiguration;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.json.Path2;
import redis.clients.jedis.search.Document;
import redis.clients.jedis.search.FTCreateParams;
import redis.clients.jedis.search.IndexDataType;
import redis.clients.jedis.search.Query;
import redis.clients.jedis.search.schemafields.SchemaField;
import redis.clients.jedis.search.schemafields.TagField;
import redis.clients.jedis.search.schemafields.VectorField;

/**
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */

@Slf4j
public class AiVectorStoreRedisResource extends AiVectorStoreResource<AiVectorStoreRedisConfiguration> {

  private static final String VECTOR_PARAM = "vector";
  private static final String MAX_RESULTS_PARAM = "max_results";

  private static final String VECTOR_TYPE_PROP_KEY = "TYPE";
  private static final String DIM_TYPE_PROP_KEY = "DIM";
  private static final String DISTANCE_METRIC_PROP_KEY = "DISTANCE_METRIC";
  private static final String INITIAL_CAP_PROP_KEY = "INITIAL_CAP";
  private static final String BLOCK_SIZE_PROP_KEY = "BLOCK_SIZE";
  private static final String VECTOR_TYPE_FLOAT32 = "FLOAT32";

  private static final String VECTOR_ATTR = "vector";
  private static final String TEXT_ATTR = "text";
  private static final String RETRIEVAL_CONTEXT_KEY_ATTR = "retrieval_context_key";

  private static final int REDIS_DIALECT_VALUE = 2;
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static final Pattern PARAMETERS_PATTERN = Pattern.compile("\\$(?!vector\\b|max_results\\b)(\\w+)");
  public static final String OK_REDIS_RESPONSE = "OK";

  private AiVectorStoreProperties properties;
  private RedisConfiguration redisConfig;
  private JedisPooled client;

  @Override
  public void doStart() throws Exception {
    super.doStart();

    properties = super.configuration().properties();
    redisConfig = super.configuration().redisConfig();
    client = buildClient();

    if (!properties.readOnly()) {
      if (indexDoNotExist()) {
        Map<String, Object> vectorAttrs = new HashMap<>();
        vectorAttrs.put(VECTOR_TYPE_PROP_KEY, VECTOR_TYPE_FLOAT32);
        vectorAttrs.put(DIM_TYPE_PROP_KEY, properties.embeddingSize());
        vectorAttrs.put(DISTANCE_METRIC_PROP_KEY, getDistanceMetric().name());
        vectorAttrs.put(INITIAL_CAP_PROP_KEY, redisConfig.vectorStoreConfig().initialCapacity());

        if (!HNSW.equals(properties.indexType())) {
          vectorAttrs.put(BLOCK_SIZE_PROP_KEY, redisConfig.vectorStoreConfig().blockSize());
        }

        SchemaField[] schema = new SchemaField[] {
          TagField.of("$." + RETRIEVAL_CONTEXT_KEY_ATTR).as(RETRIEVAL_CONTEXT_KEY_ATTR),
          VectorField
            .builder()
            .fieldName("$." + VECTOR_ATTR)
            .algorithm(getVectorAlgorithm())
            .attributes(vectorAttrs)
            .as(VECTOR_ATTR)
            .build(),
        };

        var finalPrefix = getFinalPrefixName();
        String response = client.ftCreate(
          redisConfig.index(),
          FTCreateParams.createParams().on(IndexDataType.JSON).addPrefix(finalPrefix),
          schema
        );

        if (!OK_REDIS_RESPONSE.equals(response)) {
          log.error("Failed to create redis vector store index [{}] --> {}", redisConfig.index(), response);
        }
      } else {
        log.debug("[{}] index already exists", redisConfig.index());
      }
    } else {
      log.debug("AilVectorStoreRedisResource is read-only");
    }
  }

  private boolean indexDoNotExist() {
    return !client.ftList().contains(redisConfig.index());
  }

  private JedisPooled buildClient() throws URISyntaxException {
    URI uri = new URI(redisConfig.url());
    if (nullOrEmpty(redisConfig.username()) || nullOrEmpty(redisConfig.password())) {
      return new JedisPooled(uri);
    }
    return new JedisPooled(getUsernameAndPasswordUri(uri));
  }

  private boolean nullOrEmpty(String value) {
    return value == null || value.isBlank();
  }

  private String getFinalPrefixName() {
    return redisConfig.prefix() + ":";
  }

  private VectorField.VectorAlgorithm getVectorAlgorithm() {
    return switch (properties.indexType()) {
      case FLAT -> VectorField.VectorAlgorithm.FLAT;
      case HNSW -> VectorField.VectorAlgorithm.HNSW;
      case IVF -> throw new IllegalArgumentException("IVF not supported");
    };
  }

  private DistanceMetric getDistanceMetric() {
    return switch (properties.similarity()) {
      case EUCLIDEAN -> DistanceMetric.L2;
      case COSINE -> DistanceMetric.COSINE;
      case DOT -> DistanceMetric.IP;
    };
  }

  @Override
  public void doStop() throws Exception {
    super.doStop();
    client.close();
  }

  @Override
  public void add(VectorEntity vectorEntity) {
    if (!properties.readOnly()) {
      Map<String, Object> doc = new HashMap<>();
      doc.put(VECTOR_ATTR, vectorEntity.vector());
      doc.put(TEXT_ATTR, vectorEntity.text());
      doc.putAll(vectorEntity.metadata());

      String id = getFinalPrefixName() + vectorEntity.id();
      client.jsonSetWithEscape(id, Path2.ROOT_PATH, doc);

      if (properties.allowEviction()) {
        TimeUnit timeUnit = properties.evictTimeUnit();
        long duration = properties.evictTime();
        long evictionTime = timeUnit.toSeconds(duration);
        client.expireAt(id, (vectorEntity.timestamp() / 1000) + evictionTime);
      }
    } else {
      log.debug("AiVectorStoreRedisResource.add is read-only");
    }
  }

  @Override
  public Flowable<VectorResult> findRelevant(VectorEntity vectorEntity) {
    return Maybe
      .fromCallable(() -> toByteArray(vectorEntity.vector()))
      .map(byteVector -> getQuery(vectorEntity, byteVector))
      .map(query -> client.ftSearch(redisConfig.index(), query))
      .onErrorResumeNext(e -> {
        log.error(e.toString(), e);
        return Maybe.empty();
      })
      .toFlowable()
      .flatMap(searchResult -> Flowable.fromIterable(searchResult.getDocuments()))
      .map(document -> {
        var metadata = extractMetadata(document.getString("$"));
        var text = metadata.get(TEXT_ATTR).toString();

        metadata.remove(TEXT_ATTR);
        metadata.remove(VECTOR_ATTR);

        return new VectorResult(new VectorEntity(document.getId(), text, metadata), normalizeSore(document));
      })
      .sorted(comparingDouble(result -> -result.score()))
      .filter(result -> result.score() >= properties.threshold());
  }

  private float normalizeSore(Document document) {
    String stringScore = document.getString(redisConfig.scoreField());
    return switch (this.properties.similarity()) {
      case EUCLIDEAN -> 2 / (2 + Float.parseFloat(stringScore));
      case COSINE, DOT -> (2 - Float.parseFloat(stringScore)) / 2;
    };
  }

  private Query getQuery(VectorEntity vectorEntity, byte[] byteVector) {
    var query = new Query(redisConfig.query())
      .addParam(VECTOR_PARAM, byteVector)
      .addParam(MAX_RESULTS_PARAM, properties.maxResults())
      .dialect(REDIS_DIALECT_VALUE)
      .setSortBy(redisConfig.scoreField(), true);

    var matcher = PARAMETERS_PATTERN.matcher(redisConfig.query());
    while (matcher.find()) {
      String param = matcher.group().substring(1);
      query.addParam(param, vectorEntity.metadata().get(param));
    }

    return query;
  }

  private static Map<String, Object> extractMetadata(String json) {
    try {
      return OBJECT_MAPPER.readValue(json, HashMap.class);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

  public static byte[] toByteArray(float[] input) {
    byte[] bytes = new byte[Float.BYTES * input.length];
    ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN).asFloatBuffer().put(input);
    return bytes;
  }

  private URI getUsernameAndPasswordUri(URI u) throws URISyntaxException {
    return new URI(
      u.getScheme(),
      redisConfig.username() + ":" + redisConfig.password(),
      u.getHost(),
      u.getPort(),
      u.getPath(),
      u.getQuery(),
      u.getFragment()
    );
  }

  @Override
  public void remove(VectorEntity vectorEntity) {
    throw new UnsupportedOperationException("AiVectorStoreRedisResource.remove not supported.");
  }
}
