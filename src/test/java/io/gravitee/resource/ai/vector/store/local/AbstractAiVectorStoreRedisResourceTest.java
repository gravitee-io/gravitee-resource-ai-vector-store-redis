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

import io.gravitee.resource.ai.vector.store.redis.AiVectorStoreRedisResource;
import io.gravitee.resource.ai.vector.store.redis.configuration.AiVectorStoreRedisConfiguration;
import java.lang.reflect.Field;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
abstract class AbstractAiVectorStoreRedisResourceTest {

  static final String IMAGE_NAME = "redis/redis-stack:7.4.0-v5";
  static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse(IMAGE_NAME)).withExposedPorts(6379);

  static float[] vector1 = new float[] {
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

  static float[] vector2 = new float[] {
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

  static float[] vector3 = new float[] {
    0.11073259f,
    -0.04860843f,
    -0.14480169f,
    0.26077873f,
    0.37519342f,
    0.3149992f,
    0.2587352f,
    0.1779201f,
    0.39984795f,
    0.00279276f,
  };

  @BeforeAll
  static void startRedis() {
    redis.start();
  }

  @AfterAll
  static void stopRedis() {
    redis.stop();
  }

  void injectConfiguration(AiVectorStoreRedisResource resource, AiVectorStoreRedisConfiguration config) throws Exception {
    Field field = resource.getClass().getSuperclass().getSuperclass().getDeclaredField("configuration");
    field.setAccessible(true);
    field.set(resource, config);
  }
}
