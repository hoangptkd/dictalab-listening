package com.dictalab.support;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import java.time.Duration;
import java.util.stream.Stream;

/** Starts before Spring test discovery; shared for the fork, cleaned by Testcontainers' resource reaper. */
public final class ReliabilityContainers implements LauncherSessionListener {
    private static boolean started;
    private static RabbitMQContainer rabbitContainer;
    private static PostgreSQLContainer postgresContainer;

    public static PostgreSQLContainer disposablePostgres() {
        if (!started || postgresContainer == null) throw new IllegalStateException("Disposable reliability PostgreSQL required");
        return postgresContainer;
    }

    public static void setRabbitPaused(boolean paused) {
        if (!started || rabbitContainer == null) throw new IllegalStateException("Disposable reliability RabbitMQ required");
        var docker = org.testcontainers.DockerClientFactory.instance().client();
        if (paused) docker.pauseContainerCmd(rabbitContainer.getContainerId()).exec();
        else docker.unpauseContainerCmd(rabbitContainer.getContainerId()).exec();
    }

    public static int crashAndRestartRabbit() {
        if (!started || rabbitContainer == null) throw new IllegalStateException("Disposable reliability RabbitMQ required");
        var docker=org.testcontainers.DockerClientFactory.instance().client();
        docker.killContainerCmd(rabbitContainer.getContainerId()).withSignal("KILL").exec();
        docker.startContainerCmd(rabbitContainer.getContainerId()).exec();
        // Preserve the same container/data; CLI may see a stale PID file immediately after SIGKILL.
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(45)).ignoreExceptions().until(()->
                rabbitContainer.execInContainer("rabbitmq-diagnostics","-q","ping").getExitCode()==0);
        var bindings=docker.inspectContainerCmd(rabbitContainer.getContainerId()).exec().getNetworkSettings().getPorts();
        int port=Integer.parseInt(bindings.getBindings().get(com.github.dockerjava.api.model.ExposedPort.tcp(5672))[0].getHostPortSpec());
        System.setProperty("spring.rabbitmq.port",Integer.toString(port));
        return port;
    }

    @Override public void launcherSessionOpened(LauncherSession session) {
        if (!Boolean.getBoolean("dictalab.testcontainers")) return;
        start();
    }

    private static synchronized void start() {
        if (started) return;
        var postgres = new PostgreSQLContainer(System.getProperty("dictalab.testcontainers.postgres-image", "postgres:17.11-alpine"))
                .withDatabaseName("dictalab_test").withUsername("dictalab_test").withPassword("test-only-password");
        var redis = new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379)
                .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));
        var rabbit = new RabbitMQContainer("rabbitmq:4.3.6-management-alpine")
                .withAdminUser("dictalab_test").withAdminPassword("test-only-password");
        postgres.withStartupTimeout(Duration.ofSeconds(90));
        redis.withStartupTimeout(Duration.ofSeconds(90));
        rabbit.withStartupTimeout(Duration.ofSeconds(90));
        // No disabledWithoutDocker: selecting this profile must fail if infrastructure cannot start.
        try { Startables.deepStart(Stream.of(postgres, redis, rabbit)).join(); }
        catch (RuntimeException failure) {
            rabbit.stop(); redis.stop(); postgres.stop(); throw failure;
        }
        System.setProperty("spring.datasource.url", postgres.getJdbcUrl());
        System.setProperty("spring.datasource.username", postgres.getUsername());
        System.setProperty("spring.datasource.password", postgres.getPassword());
        System.setProperty("spring.datasource.driver-class-name", "org.postgresql.Driver");
        var yaml=new org.springframework.beans.factory.config.YamlPropertiesFactoryBean();
        yaml.setResources(new org.springframework.core.io.FileSystemResource("src/main/resources/application.yml"));
        System.setProperty("spring.datasource.hikari.connection-init-sql",
                yaml.getObject().getProperty("spring.datasource.hikari.connection-init-sql"));
        System.setProperty("TEST_REDIS_HOST", redis.getHost());
        System.setProperty("TEST_REDIS_PORT", redis.getMappedPort(6379).toString());
        System.setProperty("spring.data.redis.url", "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379) + "/0");
        System.setProperty("spring.rabbitmq.host", rabbit.getHost());
        System.setProperty("spring.rabbitmq.port", rabbit.getAmqpPort().toString());
        System.setProperty("spring.rabbitmq.username", rabbit.getAdminUsername());
        System.setProperty("spring.rabbitmq.password", rabbit.getAdminPassword());
        System.setProperty("dictalab.testcontainers.postgres-id", postgres.getContainerId());
        System.setProperty("dictalab.testcontainers.redis-id", redis.getContainerId());
        System.setProperty("dictalab.testcontainers.rabbit-id", rabbit.getContainerId());
        rabbitContainer = rabbit;
        postgresContainer = postgres;
        started = true;
    }
}
