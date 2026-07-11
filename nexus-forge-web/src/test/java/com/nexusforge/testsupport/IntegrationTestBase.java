package com.nexusforge.testsupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

import java.net.URI;

/**
 * 集成测试基类 —— @SpringBootTest + Testcontainers (PG / Redis / RustFS)
 *
 * Spring Boot 4 已移除 TestRestTemplate,改用 RestTemplate(由 spring-boot-restclient 自动配置)。
 * RestTemplate.exchange(...) 返回 ResponseEntity,跟旧 TestRestTemplate 行为兼容。
 *
 * - 容器 static + withReuse(true) 跨测试类复用,避免每个 IT 都重启容器
 * - storage.* 是自定义前缀,@ServiceConnection 不识别,统一在 wireProperties 里手动绑定
 *   并覆盖 StorageProperties.vendor 字段默认值 "rustfs" 对应的子段 storage.rustfs.default.*
 * - 默认 -Pintegration=true 才跑,本地 ./gradlew test 跳过(@Tag("integration"))
 * - Redis 用 GenericContainer("redis:latest"),无需 com.redis:testcontainers-redis 额外依赖
 * - RustFS 用 GenericContainer("rustfs/rustfs:latest"),S3 兼容协议,S3StorageProvider 直接吃
 */
@Tag("integration")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    @LocalServerPort
    protected int port;

    @Autowired
    protected DatabaseCleaner db;

    @Autowired
    protected RedisCleaner redis;

    @Autowired
    protected RestTemplateBuilder restBuilder;

    protected AuthTestHelper auth;

    public static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:latest"))
                    .withReuse(true)
                    .withDatabaseName("nexus_forge_test");

    public static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:latest"))
                    .withExposedPorts(6379)
                    .withReuse(true);

    /** RustFS 是 S3 兼容对象存储,S3StorageProvider 直接复用。
     *  默认端口 9000(S3 API);控制台 9001 调试用。 */
    public static final GenericContainer<?> RUSTFS =
            new GenericContainer<>(DockerImageName.parse("rustfs/rustfs:latest"))
                    .withEnv("RUSTFS_ACCESS_KEY", "rustfsadmin")
                    .withEnv("RUSTFS_SECRET_KEY", "rustfsadmin")
                    .withExposedPorts(9000, 9001)
                    .withReuse(true);

    /** 测试用 bucket,跨 IT 类共享 */
    protected static final String TEST_BUCKET = "nexus-forge-test";

    static {
        POSTGRES.start();
        REDIS.start();
        RUSTFS.start();
        ensureBucket();
    }

    /** RustFS 容器启动后不会自动建 bucket;IT 类共用同一个 bucket,启动时建一次即可。 */
    private static void ensureBucket() {
        try {
            AwsBasicCredentials creds = AwsBasicCredentials.create("rustfsadmin", "rustfsadmin");
            S3Client s3 = S3Client.builder()
                    .endpointOverride(URI.create(rustfsEndpoint()))
                    .credentialsProvider(StaticCredentialsProvider.create(creds))
                    .region(Region.US_EAST_1)
                    .forcePathStyle(true)
                    .build();
            try {
                s3.headBucket(b -> b.bucket(TEST_BUCKET));
            } catch (NoSuchBucketException e) {
                s3.createBucket(b -> b.bucket(TEST_BUCKET));
            }
            s3.close();
        } catch (Exception ex) {
            throw new IllegalStateException("无法初始化 RustFS bucket", ex);
        }
    }

    @DynamicPropertySource
    static void wireProperties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);

        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));

        // RustFS S3 API 在 9000;endpoint 形如 http://host:port
        r.add("storage.rustfs.default.endpoint", IntegrationTestBase::rustfsEndpoint);
        r.add("storage.rustfs.default.access-key", () -> "rustfsadmin");
        r.add("storage.rustfs.default.secret-key", () -> "rustfsadmin");
        r.add("storage.rustfs.default.bucket", () -> TEST_BUCKET);
        r.add("storage.rustfs.default.path-style", () -> "true");
        r.add("storage.rustfs.default.region", () -> "us-east-1");

        // 显式声明 vendor=rustfs,虽然 StorageProperties 字段默认就是 "rustfs",
        // 但写出来更明确,也防止后续有人改字段默认值的隐性回归。
        r.add("storage.vendor", () -> "rustfs");
    }

    @BeforeEach
    void initAuthHelper() {
        auth = new AuthTestHelper(rest());
    }

    /** 每次拿一个新 RestTemplate,默认走 baseUri 指向本机 random port */
    protected RestTemplate rest() {
        return restBuilder.baseUri("http://localhost:" + port).build();
    }

    /** 子类要拿 RustFS 容器地址时用这个,避免硬编码 */
    protected static String rustfsEndpoint() {
        return "http://" + RUSTFS.getHost() + ":" + RUSTFS.getMappedPort(9000);
    }
}