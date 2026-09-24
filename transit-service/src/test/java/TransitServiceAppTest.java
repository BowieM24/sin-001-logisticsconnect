package co.wethinkcode.logisticsconnect;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TransitServiceAppTest {

    private static final HttpClient client = HttpClient.newHttpClient();

    @BeforeAll
    public static void setup() throws InterruptedException {
        // Start the application in a separate background thread so tests don't freeze
        new Thread(() -> TransitServiceApp.main(new String[0])).start();
        // Give the Javalin server a 3-second buffer to initialize and open port 7053
        Thread.sleep(3000);
    }

    @Test
    public void testHealthEndpoint() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:7053/health"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("OK", response.body());
    }

    @Test
    public void testTransitEstimateMissingParam() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:7053/transit/estimate"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode());
        assertEquals("Missing 'hub' query parameter", response.body());
    }

    @Test
    public void testTransitEstimateValidRequest() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:7053/transit/estimate?hub=H-515"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertEquals(200, response.statusCode());
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(""));
        
        String body = response.body();
        assertTrue(body.contains("\"hub\": \"H-515\""));
        assertTrue(body.contains("\"delayStage\""));
        assertTrue(body.contains("\"estimatedArrivalWindowHours\""));
    }
}
