package org.etocrm.redisServer.config;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.parser.ParserConfig;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.alibaba.fastjson.support.config.FastJsonConfig;
import com.alibaba.fastjson.support.spring.FastJsonHttpMessageConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurerSupport;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;

import java.nio.charset.Charset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * @author: LHL
 * @ProjectName: sophia_scaffolding
 * @Package: com.scaffolding.sophia.common.config.redis
 * @ClassName: RedisConfig
 * @Description: redis??????
 * @Version: 1.0
 */
@Configuration
@EnableCaching
public class RedisConfig extends CachingConfigurerSupport {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    /**
     * ?????? RedisConnectionFactory
     */
    @Autowired
    RedisConnectionFactory redisConnectionFactory;

    /**
     * ????????? RedisTemplate ??????
     *
     * @return
     */
    @Bean
    public RedisTemplate<String, Object> functionDomainRedisTemplate() {
        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        initDomainRedisTemplate(redisTemplate, redisConnectionFactory);
        log.info("RedisTemplate????????????");
        return redisTemplate;
    }

    /**
     * ?????????????????? redis ??????????????????
     * @param redisTemplate
     * @param factory
     */
    private void initDomainRedisTemplate(RedisTemplate<String, Object> redisTemplate, RedisConnectionFactory factory) {
        //??????fastjson?????????
        FastJsonRedisSerializer fastJsonRedisSerializer = new FastJsonRedisSerializer(Object.class);
        // value?????????????????????fastJsonRedisSerializer
        redisTemplate.setValueSerializer(fastJsonRedisSerializer);
        redisTemplate.setHashValueSerializer(fastJsonRedisSerializer);
        // key??????????????????StringRedisSerializer
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setHashKeySerializer(new StringRedisSerializer());
        redisTemplate.setConnectionFactory(factory);
        log.debug("?????????RedisTemplate????????????");
    }


    @Bean
    @ConditionalOnMissingBean(StringRedisTemplate.class)
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory redisConnectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(redisConnectionFactory);
        log.info("?????? StringRedisTemplate ??????");
        return template;
    }

    /**
     *  ?????? redis ????????????????????????
     *  ??????@cacheable ???????????????
     * @return
     */
    @Bean
    public RedisCacheConfiguration redisCacheConfiguration(){
        FastJsonRedisSerializer<Object> fastJsonRedisSerializer = new FastJsonRedisSerializer<>(Object.class);
        RedisCacheConfiguration configuration = RedisCacheConfiguration.defaultCacheConfig();
        configuration = configuration
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(fastJsonRedisSerializer)).entryTtl(Duration.ofDays(1));
        return configuration;
    }

    @Bean
    @Primary//???????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        //???????????????RedisCacheWriter
        RedisCacheWriter redisCacheWriter = RedisCacheWriter.nonLockingRedisCacheWriter(connectionFactory);
        //???????????????fastjson
        FastJsonRedisSerializer<Object> fastJsonRedisSerializer = new FastJsonRedisSerializer<>(Object.class);
        RedisSerializationContext.SerializationPair<Object> pair = RedisSerializationContext.SerializationPair.fromSerializer(fastJsonRedisSerializer);
        RedisCacheConfiguration defaultCacheConfig=RedisCacheConfiguration.defaultCacheConfig().serializeValuesWith(pair);
        //??????????????????
        defaultCacheConfig = defaultCacheConfig.entryTtl(Duration.ofDays(1))
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .disableCachingNullValues();
        //?????????RedisCacheManager
        RedisCacheManager cacheManager = new RedisCacheManager(redisCacheWriter, defaultCacheConfig);
        // ???????????????,??????fastjson???????????????????????????class?????????????????????????????????,fastjson????????????????????????autoType??????????????????????????????????????????,??????????????????????????????????????????autoType???,??????????????????com.alibaba.fastjson.JSONException: autoType is not support?????????
        // ParserConfig.getGlobalInstance().addAccept("com.scaffolding.sophia.admin.api.entity.user.User");
        ParserConfig.getGlobalInstance().setAutoTypeSupport(true);
        log.debug("?????????RedisCacheManager????????????");
        return cacheManager;
    }

    /**
     * ?????????fastJson
     * @param <T>
     */
    private static class FastJsonRedisSerializer<T> implements RedisSerializer<T> {

        private static final Charset DEFAULT_CHARSET = Charset.forName("UTF-8");

        private Class<T> clazz;

        public FastJsonRedisSerializer(Class<T> clazz) {
            this.clazz = clazz;
        }

        @Override
        public byte[] serialize(T t) {
            if (t == null) {
                return new byte[0];
            }
            try {
                return JSON.toJSONString(t, SerializerFeature.WriteClassName).getBytes(DEFAULT_CHARSET);
            } catch (Exception ex) {
                throw new SerializationException("Could not write JSON: " + ex.getMessage(), ex);
            }
        }

        @Override
        public T deserialize(byte[] bytes) {
            if (bytes == null || bytes.length <= 0) {
                return null;
            }
            String str = new String(bytes, DEFAULT_CHARSET);
            try {
                return (T) JSON.parseObject(str, clazz);
            } catch (Exception ex) {
                throw new SerializationException("Could not parse JSON: " + ex.getMessage(), ex);
            }

        }
    }

    @Override
    @Bean
    public CacheErrorHandler errorHandler() {
        // ??????????????????Redis??????????????????????????????????????????????????????
        log.info("????????? -> [{}]", "Redis CacheErrorHandler");
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                log.error("Redis occur handleCacheGetError???key -> [{}]", key, e);
            }

            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                log.error("Redis occur handleCachePutError???key -> [{}]???value -> [{}]", key, value, e);
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                log.error("Redis occur handleCacheEvictError???key -> [{}]", key, e);
            }

            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                log.error("Redis occur handleCacheClearError???", e);
            }
        };
    }

    /**
     *  ??????@Bean??????fastJsonHttpMessageConvert,fastJson?????????
     */
    @Bean
    public HttpMessageConverters fastJsonHttpMessageConverters(){
        // 1???????????????????????? convert ?????????????????????;
        FastJsonHttpMessageConverter fastConverter = new FastJsonHttpMessageConverter();
        //2?????????fastJson ??????????????????????????????????????????????????????json??????;
        FastJsonConfig fastJsonConfig = new FastJsonConfig();
        fastJsonConfig.setSerializerFeatures(SerializerFeature.PrettyFormat);

        //2-1 ????????????????????????
        List<MediaType> fastMediaTypes = new ArrayList<>();
        fastMediaTypes.add(MediaType.APPLICATION_JSON_UTF8);
        fastConverter.setSupportedMediaTypes(fastMediaTypes);

        //3??????convert?????????????????????.
        fastConverter.setFastJsonConfig(fastJsonConfig);
        HttpMessageConverter<?> converter = fastConverter;
        return new HttpMessageConverters(converter);
    }

    /**
     * ????????? HashOperations ??????,???????????? Hash ????????????
     * @param redisTemplate
     * @return
     */
    @Bean
    public HashOperations<String, String, Object> hashOperations(RedisTemplate<String, Object> redisTemplate) {
        return redisTemplate.opsForHash();
    }

    /**
     * ????????? ValueOperations ??????,???????????? String ??????
     *
     * @param redisTemplate
     * @return
     */
    @Bean
    public ValueOperations<String, Object> valueOperations(RedisTemplate<String, Object> redisTemplate) {
        return redisTemplate.opsForValue();
    }

    /**
     * ????????? ListOperations ??????,???????????? List ??????
     *
     * @param redisTemplate
     * @return
     */
    @Bean
    public ListOperations<String, Object> listOperations(RedisTemplate<String, Object> redisTemplate) {
        return redisTemplate.opsForList();
    }

    /**
     * ????????? SetOperations ??????,???????????? Set ??????
     *
     * @param redisTemplate
     * @return
     */
    @Bean
    public SetOperations<String, Object> setOperations(RedisTemplate<String, Object> redisTemplate) {
        return redisTemplate.opsForSet();
    }

    /**
     * ????????? ZSetOperations ??????,???????????? ZSet ??????
     *
     * @param redisTemplate
     * @return
     */
    @Bean
    public ZSetOperations<String, Object> zSetOperations(RedisTemplate<String, Object> redisTemplate) {
        return redisTemplate.opsForZSet();
    }

}