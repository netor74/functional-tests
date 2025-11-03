package io.rubuy74.e2e;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

public class E2EFunctionalTest {
    private static String uniqueMarketId;
    private static String uniqueEventId;

    @BeforeAll
    static void startDockerCompose() throws IOException, InterruptedException {
        // Build images first
        ProcessBuilder buildPb = new ProcessBuilder("docker-compose", "build");
        buildPb.directory(new java.io.File(System.getProperty("user.dir")));
        Process buildProcess = buildPb.start();
        int buildExitCode = buildProcess.waitFor();
        if (buildExitCode != 0) {
            throw new RuntimeException("Failed to build docker-compose images");
        }

        // Start services
        ProcessBuilder pb = new ProcessBuilder("docker-compose", "up", "-d");
        pb.directory(new java.io.File(System.getProperty("user.dir")));
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Failed to start docker-compose");
        }
        // Wait for services to be ready
        await().atMost(Duration.ofMinutes(5)).until(() -> {
            try {
                return RestAssured.get("http://localhost:8080/api/v1/events").statusCode() == 200;
            } catch (Exception e) {
                return false;
            }
        });
        // Additional wait for Kafka
        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @AfterAll
    static void stopDockerCompose() throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("docker-compose", "down");
        pb.directory(new java.io.File(System.getProperty("user.dir")));
        Process process = pb.start();
        process.waitFor();
    }

    @BeforeEach
    void setup() {
        RestAssured.baseURI = "http://localhost";
        RestAssured.port = 8080;
        uniqueMarketId = UUID.randomUUID().toString();
        uniqueEventId = UUID.randomUUID().toString();
        // Clean database
        try (Connection conn = DriverManager.getConnection("jdbc:postgresql://localhost:5432/postgres", "postgres", "postgres")) {
            try (Statement stmt = conn.createStatement()) {
                // List tables
                var rs = stmt.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname = 'public'");
                while (rs.next()) {
                    System.out.println("Table: " + rs.getString(1));
                }
                rs.close();
                // Truncate
                // stmt.execute("TRUNCATE TABLE event, market, selection CASCADE");
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to truncate DB", e);
        }
    }

    private static String eventPayload(String marketId, String eventId) {
        return String.format("""
            {
              "marketId": "%s",
              "marketName": "Match Odds",
              "event": {
                "id": "%s",
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
        """, marketId, eventId);
    }

    private static String updateEventPayload(String marketId, String eventId) {
        return String.format("""
            {
              "marketId": "%s",
              "marketName": "Match Odds",
              "event": {
                "id": "%s",
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
        """, marketId, eventId);
    }

    @Test
    void shouldCreateEventAndListIt() {
        given()
                .contentType(ContentType.JSON)
                .body(eventPayload(uniqueMarketId, uniqueEventId))
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
                                .body("events.find { it.id == '" + uniqueEventId + "' }.name", equalTo("Sporting vs Benfica"))
                                .body("events.find { it.id == '" + uniqueEventId + "' }.markets[0].id", equalTo(uniqueMarketId))
                                .body("events.find { it.id == '" + uniqueEventId + "' }.markets[0].selections", hasSize(3))
                );
    }
    @Test
    void shouldUpdateEventAndListIt() {
        // create event
        given().contentType(ContentType.JSON).body(eventPayload(uniqueMarketId, uniqueEventId)).when().post("/api/v1/market-change");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                given().get("/api/v1/events").then().body("events.find { it.id == '" + uniqueEventId + "' }.markets[0].selections", hasSize(3))
        );

        // update market
        given()
                .contentType(ContentType.JSON)
                .body(updateEventPayload(uniqueMarketId, uniqueEventId))
                .when().put("/api/v1/market-change")
                .then().statusCode(202);
        await()
                .atMost(Duration.ofSeconds(15))
                .untilAsserted(() ->
                        given()
                                .get("/api/v1/events")
                                .then()
                                .body("events.find { it.id == '" + uniqueEventId + "' }.markets[0].selections", hasSize(2))
                );
    }
    @Test
    void shouldDeleteEventAndListIt() {
        // create event
        given().contentType(ContentType.JSON).body(eventPayload(uniqueMarketId, uniqueEventId)).when().post("/api/v1/market-change");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                given().get("/api/v1/events").then().body("events.find { it.id == '" + uniqueEventId + "' }.markets[0].selections", hasSize(3))
        );

        // delete market
        given()
                .contentType(ContentType.JSON)
                .body(eventPayload(uniqueMarketId, uniqueEventId))
                .when().delete("/api/v1/market-change")
                .then().statusCode(202);

        await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() ->
                        given()
                                .get("/api/v1/events")
                                .then().body("events.find { it.id == '" + uniqueEventId + "' }.markets", hasSize(0))
                );
    }

    @Test
    void shouldShowAddStatusForEvent() {
        // create event
        Response response = given().contentType(ContentType.JSON).body(eventPayload(uniqueMarketId, uniqueEventId)).when().post("/api/v1/market-change");
        String location = response.headers().get("Location").getValue();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                given().get("/api/v1/events").then().body("events.find { it.id == '" + uniqueEventId + "' }", notNullValue())
        );

        // check status
        given()
                .get(location)
                .then()
                .statusCode(200);
    }

    @Test
    void shouldShowDeleteStatusForEvent() {
        // create event
        given().contentType(ContentType.JSON).body(eventPayload(uniqueMarketId, uniqueEventId)).when().post("/api/v1/market-change");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                given().get("/api/v1/events").then().body("events.find { it.id == '" + uniqueEventId + "' }", notNullValue())
        );

        // delete
        Response response = given().contentType(ContentType.JSON).body(eventPayload(uniqueMarketId, uniqueEventId)).when().delete("/api/v1/market-change");
        String location = response.headers().get("Location").getValue();

        // check status
        given()
                .get(location)
                .then()
                .statusCode(200);
    }

    @Test
    void shouldShowUpdateStatusForEvent() {
        // create event
        given().contentType(ContentType.JSON).body(eventPayload(uniqueMarketId, uniqueEventId)).when().post("/api/v1/market-change");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                given().get("/api/v1/events").then().body("events.find { it.id == '" + uniqueEventId + "' }", notNullValue())
        );

        // update
        Response response = given().contentType(ContentType.JSON).body(updateEventPayload(uniqueMarketId, uniqueEventId)).when().put("/api/v1/market-change");
        String location = response.headers().get("Location").getValue();

        // check status
        given()
                .get(location)
                .then()
                .statusCode(200);
    }

    @Test
    void shouldRejectInvalidPayload() {
        String invalidPayload = "{ invalid json }";

        given()
                .contentType(ContentType.JSON)
                .body(invalidPayload)
                .when()
                .post("/api/v1/market-change")
                .then()
                .statusCode(400);
    }

    @Test
    void shouldRejectInvalidOperation() {
        given()
                .contentType(ContentType.JSON)
                .body(eventPayload(uniqueMarketId, uniqueEventId))
                .when()
                .patch("/api/v1/market-change")
                .then()
                .statusCode(405);
    }
}
