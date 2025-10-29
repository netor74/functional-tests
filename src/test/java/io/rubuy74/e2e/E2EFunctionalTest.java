package io.rubuy74.e2e;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.http.Headers;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Paths;
import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

@Testcontainers
public class E2EFunctionalTest {
    private static final Network NETWORK = Network.newNetwork();

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer().withNetwork(NETWORK).withNetworkAliases("kafka");

    @Container
    private static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:13.4")
    )
            .withNetwork(NETWORK)
            .withNetworkAliases("postgres")
            .withDatabaseName("postgres")
            .withUsername("postgres")
            .withPassword("postgres");

    @Container
    private final GenericContainer<?> MOS = new GenericContainer<>(
            new ImageFromDockerfile()
                    .withDockerfile(Paths.get("../mos/Dockerfile"))
    )
            .withNetwork(NETWORK)
            .withNetworkAliases("mos-service")
            .withExposedPorts(3000)
            .withEnv("spring.kafka.consumer.bootstrap-servers", "kafka:9092")
            .withEnv("spring.kafka.producer.bootstrap-servers", "kafka:9092")
            .withEnv("spring.datasource.url", "jdbc:postgresql://postgres:5432/postgres")
            .withEnv("spring.jpa.hibernate.ddl-auto", "create")
            .dependsOn(KAFKA, POSTGRESQL)
            .waitingFor(Wait.forLogMessage(".*partitions assigned:.*",1).withStartupTimeout(Duration.ofMinutes(1)));

    @Container
    private final GenericContainer<?> RHS = new GenericContainer<>(
      new ImageFromDockerfile().withDockerfile(Paths.get("../rhs/Dockerfile"))
    )
            .withNetwork(NETWORK)
            .withExposedPorts(8080)
            .withEnv("spring.kafka.consumer.bootstrap-servers", "kafka:9092")
            .withEnv("spring.kafka.producer.bootstrap-servers","kafka:9092")
            .withEnv("spring.kafka.bootstrap-servers","kafka:9092")
            .withEnv("mos.service.base-url","http://mos-service:3000")
            .dependsOn(KAFKA,MOS)
            .withNetworkAliases("rhs-service")
            .waitingFor(Wait.forLogMessage(".*partitions assigned:.*",1).withStartupTimeout(Duration.ofMinutes(1)));

    @BeforeEach
    void setup() {
        RestAssured.baseURI = "http://" + RHS.getHost();
        RestAssured.port = RHS.getMappedPort(8080);
    }

    private static final String eventPayload = """
              {
              "marketId": "1231231",
              "marketName": "Match Odds",
              "event": {
                "id": "987654321",
                "name": "Sporting vs Benfica",
                "date": "01/01/2027"
              },
              "selections": [
                {
                  "id": "182",
                  "name": "Benfica",
                  "odd": 1.13
                },
                {
                  "id": "318",
                  "name": "Sporting",
                  "odd": 1.10
                },
                {
                  "id": "871",
                  "name": "Draw",
                  "odd": 1.39
                }
              ]
            }
    """;
    private static final String updateEventPayload = """
              {
              "marketId": "1231231",
              "marketName": "Match Odds",
              "event": {
                "id": "987654321",
                "name": "Sporting vs Benfica",
                "date": "01/01/2027"
              },
              "selections": [
                {
                  "id": "182",
                  "name": "Benfica",
                  "odd": 1.13
                },
                {
                  "id": "318",
                  "name": "Sporting",
                  "odd": 1.10
                }
              ]
            }
    """;

    @Test
    void shouldCreateEventAndListIt() {
        given()
                .contentType(ContentType.JSON)
                .body(eventPayload)
                .when()
                .post("/api/v1/market-change")
                .then()
                .statusCode(202);

        await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() ->
                        given()
                                .when()
                                .get("/api/v1/events")
                                .then()
                                .statusCode(200)
                                .body("status", equalTo("SUCCESS"))
                                .body("events", hasSize(1))
                                .body("events[0].name", equalTo("Sporting vs Benfica"))
                                .body("events[0].markets[0].id", equalTo("1231231"))
                                .body("events[0].markets[0].selections", hasSize(3))
                );
    }
    @Test
    void shouldUpdateEventAndListIt() {
        // create event
        given().contentType(ContentType.JSON).body(eventPayload).when().post("/api/v1/market-change");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                given().get("/api/v1/events").then().body("events", hasSize(1))
        );

        // update market
        given()
                .contentType(ContentType.JSON)
                .body(updateEventPayload)
                .when().put("/api/v1/market-change")
                .then().statusCode(202);
        await()
                .atMost(Duration.ofSeconds(15))
                .untilAsserted(() ->
                        given()
                                .get("/api/v1/events")
                                .then()
                                .body("events", hasSize(1))
                                .body("events[0].markets[0].selections", hasSize(2))
                );
    }
    @Test
    void shouldDeleteEventAndListIt() {
        // create event
        given().contentType(ContentType.JSON).body(eventPayload).when().post("/api/v1/market-change");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                given().get("/api/v1/events").then().body("events", hasSize(1))
        );

        // delete market
        given()
                .contentType(ContentType.JSON)
                .body(eventPayload)
                .when().delete("/api/v1/market-change")
                .then().statusCode(202);

        await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() ->
                        given()
                                .get("/api/v1/events")
                                .then().body("events[0].markets", hasSize(0))
                );
    }

    @Test
    void shouldShowAddStatusForEvent() {
        // create event
        Response response = given().contentType(ContentType.JSON).body(eventPayload).when().post("/api/v1/market-change");
        String location = response.headers().get("Location").getValue();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                given().get("/api/v1/events").then().body("events", hasSize(1))
        );

        // check status
        given()
                .get(location)
                .then()
                .statusCode(200)
                .body("status", equalTo("SUCCESS"))
                .body("message", equalTo("Created new event 987654321 with market 1231231"));
    }

    // TODO: check status for delete and update
    // TODO: check if there are 2 repeated adds (check the events for one and check the requests statuses)
    // TODO: check delete after delete (check events and request status)
    // TODO: check client payload is invalid
    // TODO: check operation is invalid
}
